package com.n2bank.application.service;

import com.n2bank.application.port.BalanceCache;
import com.n2bank.application.port.JournalEntryRepository;
import com.n2bank.domain.model.IdempotencyKey;
import com.n2bank.domain.model.JournalEntry;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Coordinates posting journal entries to the bank's single logical ledger. */
public final class PostingService {
  private final JournalEntryRepository journalEntries;
  private final BalanceCache balances;

  public PostingService(JournalEntryRepository journalEntries, BalanceCache balances) {
    this.journalEntries =
        Objects.requireNonNull(journalEntries, "Journal entry repository cannot be null");
    this.balances = Objects.requireNonNull(balances, "Balance cache cannot be null");
  }

  /**
   * Will call {@link JournalEntryRepository#append} and invalidate affected cached balances only
   * after that call returns a committed entry. Account validation belongs inside that database
   * transaction. A Redis failure after commit must not turn a successful posting into a failure.
   * Retries must reuse the original idempotency key and entry.
   */
  public JournalEntry post(JournalEntry journalEntry, IdempotencyKey idempotencyKey) {
    Objects.requireNonNull(journalEntry, "Journal entry cannot be null");
    Objects.requireNonNull(idempotencyKey, "Idempotency key cannot be null");
    JournalEntry stored = journalEntries.append(journalEntry, idempotencyKey);
    Set<UUID> affectedAccounts =
        stored.postings().stream()
            .map(posting -> posting.accountId())
            .collect(Collectors.toSet());

    for (UUID accountId : affectedAccounts) {
      try {
        balances.invalidate(accountId);
      } catch (UnsupportedOperationException cacheNotImplemented) {
        System.err.printf(
            "Journal entry %s committed; balance cache invalidation is not implemented yet.%n",
            stored.id());
        break;
      } catch (RuntimeException cacheFailure) {
        System.err.printf(
            "Journal entry %s committed, but cache invalidation failed for account %s: %s%n",
            stored.id(), accountId, cacheFailure.getMessage());
      }
    }
    return stored;
  }
}
