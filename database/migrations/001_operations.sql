-- Apply once to an existing schema created before operations were introduced.
-- Fresh installations already include these objects in database/schema.sql.
BEGIN;

CREATE TABLE operations (
    idempotency_key VARCHAR(255) PRIMARY KEY
        REFERENCES journal_entries (idempotency_key) DEFERRABLE INITIALLY DEFERRED,
    operation_type VARCHAR(32) NOT NULL CHECK (operation_type IN ('TRANSFER')),
    request_fingerprint VARCHAR(64) NOT NULL CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TRIGGER operations_are_append_only
BEFORE UPDATE OR DELETE ON operations
FOR EACH ROW EXECUTE FUNCTION reject_journal_mutation();

COMMIT;
