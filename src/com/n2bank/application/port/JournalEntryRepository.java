package com.n2bank.application.port;

import com.n2bank.domain.model.IdempotencyKey;
import com.n2bank.domain.model.JournalEntry;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence boundary for the append-only journal of one logical ledger.
 *
 * <p>An implementation must persist a journal entry and all of its postings in one database
 * transaction. There is no ledger ID: accounts and idempotency keys share this ledger's scope.
 */
public interface JournalEntryRepository {
  Optional<JournalEntry> findById(UUID journalEntryId);

  Optional<JournalEntry> findByIdempotencyKey(IdempotencyKey idempotencyKey);

  /** Ordered entries affecting the account within the time window (inclusive). Cache must be bypassed. */
  default java.util.List<JournalEntry> findByAccount(
      UUID accountId, java.time.Instant from, java.time.Instant to) {
    throw new UnsupportedOperationException("findByAccount not implemented");
  }

  /**
   * Atomically validates referenced accounts and persists the entry, all postings, and the
   * idempotency key. Each account must exist and its currency must match its posting's currency.
   * Returns only after commit; any failure before commit must roll back the entire operation.
   *
   * <p>A database unique constraint must protect the idempotency key against concurrent requests.
   * Reusing a key with the same entry returns the stored entry without inserting more postings;
   * reusing it with a different entry must fail. Compare monetary values numerically, independent
   * of BigDecimal scale. A duplicate entry ID with a different key must also fail.
   *
   * <p>Do not implement this as a separate find-then-insert sequence outside the transaction.
   */
  JournalEntry append(JournalEntry journalEntry, IdempotencyKey idempotencyKey);
}
