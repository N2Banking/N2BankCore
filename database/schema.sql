BEGIN;

CREATE TABLE customers (
    id UUID PRIMARY KEY,
    name TEXT NOT NULL CHECK (btrim(name) <> ''),
    customer_type VARCHAR(20) NOT NULL
        CHECK (customer_type IN ('COMPANY', 'PERSON', 'OTHER'))
);

CREATE TABLE accounts (
    id UUID PRIMARY KEY,
    name TEXT NOT NULL CHECK (btrim(name) <> ''),
    customer_id UUID REFERENCES customers (id),
    account_type VARCHAR(20) NOT NULL
        CHECK (account_type IN ('ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE')),
    currency VARCHAR(3) NOT NULL
        CHECK (currency ~ '^[A-Z]{3}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE journal_entries (
    id UUID PRIMARY KEY,
    effective_at TIMESTAMPTZ NOT NULL,
    description TEXT NOT NULL CHECK (btrim(description) <> ''),
    external_reference TEXT,
    idempotency_key VARCHAR(255) NOT NULL UNIQUE
        CHECK (btrim(idempotency_key) <> ''),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- Used by the posting trigger to prevent postings being appended in a later transaction.
    created_in_transaction XID8 NOT NULL DEFAULT pg_current_xact_id()
);

CREATE TABLE postings (
    journal_entry_id UUID NOT NULL REFERENCES journal_entries (id),
    posting_index INTEGER NOT NULL CHECK (posting_index >= 0),
    account_id UUID NOT NULL REFERENCES accounts (id),
    amount NUMERIC(38, 18) NOT NULL CHECK (amount > 0),
    direction VARCHAR(6) NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    currency VARCHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),

    PRIMARY KEY (journal_entry_id, posting_index)
);

CREATE INDEX postings_account_id_idx ON postings (account_id);
CREATE INDEX postings_account_entry_idx ON postings (account_id, journal_entry_id);
CREATE INDEX journal_entries_effective_at_idx ON journal_entries (effective_at, id);

CREATE FUNCTION reject_journal_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION '% rows are append-only', TG_TABLE_NAME
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER journal_entries_are_append_only
BEFORE UPDATE OR DELETE ON journal_entries
FOR EACH ROW EXECUTE FUNCTION reject_journal_mutation();

CREATE TRIGGER postings_are_append_only
BEFORE UPDATE OR DELETE ON postings
FOR EACH ROW EXECUTE FUNCTION reject_journal_mutation();

CREATE FUNCTION require_posting_in_entry_transaction()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    entry_transaction XID8;
BEGIN
    SELECT created_in_transaction
      INTO entry_transaction
      FROM journal_entries
     WHERE id = NEW.journal_entry_id;

    IF entry_transaction IS NULL THEN
        RAISE EXCEPTION 'Journal entry % does not exist', NEW.journal_entry_id
            USING ERRCODE = '23503';
    END IF;

    IF entry_transaction <> pg_current_xact_id() THEN
        RAISE EXCEPTION 'Postings must be inserted in the transaction that creates journal entry %',
            NEW.journal_entry_id
            USING ERRCODE = '55000';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER postings_belong_to_new_entry
BEFORE INSERT ON postings
FOR EACH ROW EXECUTE FUNCTION require_posting_in_entry_transaction();

CREATE FUNCTION validate_complete_journal_entry()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    posting_count INTEGER;
    first_index INTEGER;
    last_index INTEGER;
    currency_count INTEGER;
    debit_total NUMERIC(38, 18);
    credit_total NUMERIC(38, 18);
BEGIN
    SELECT COUNT(*),
           MIN(posting_index),
           MAX(posting_index),
           COUNT(DISTINCT currency),
           COALESCE(SUM(amount) FILTER (WHERE direction = 'DEBIT'), 0),
           COALESCE(SUM(amount) FILTER (WHERE direction = 'CREDIT'), 0)
      INTO posting_count, first_index, last_index, currency_count, debit_total, credit_total
      FROM postings
     WHERE journal_entry_id = NEW.id;

    IF posting_count < 2 THEN
        RAISE EXCEPTION 'Journal entry % requires at least two postings', NEW.id
            USING ERRCODE = '23514';
    END IF;

    IF first_index <> 0 OR last_index <> posting_count - 1 THEN
        RAISE EXCEPTION 'Journal entry % posting indexes must be contiguous and start at zero', NEW.id
            USING ERRCODE = '23514';
    END IF;

    IF currency_count <> 1 THEN
        RAISE EXCEPTION 'Journal entry % postings must use one currency', NEW.id
            USING ERRCODE = '23514';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM postings p
          JOIN accounts a ON a.id = p.account_id
         WHERE p.journal_entry_id = NEW.id
           AND p.currency <> a.currency
    ) THEN
        RAISE EXCEPTION 'Journal entry % contains an account currency mismatch', NEW.id
            USING ERRCODE = '23514';
    END IF;

    IF debit_total <> credit_total THEN
        RAISE EXCEPTION 'Journal entry % is unbalanced: debits %, credits %',
            NEW.id, debit_total, credit_total
            USING ERRCODE = '23514';
    END IF;

    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER journal_entry_is_complete_and_balanced
AFTER INSERT ON journal_entries
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION validate_complete_journal_entry();

COMMENT ON TABLE journal_entries IS
    'Append-only journal for the bank engine single logical ledger';
COMMENT ON COLUMN journal_entries.created_in_transaction IS
    'Internal guard preventing postings from being appended after the entry commits';

COMMIT;
