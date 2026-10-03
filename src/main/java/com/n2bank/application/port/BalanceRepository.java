package com.n2bank.application.port;

import com.n2bank.domain.model.Money;
import java.util.UUID;

/** Reads authoritative account balances from the ledger stored in PostgreSQL. */
public interface BalanceRepository {
  /**
   * Current ledger total: sums every stored posting, including future-dated ones. Effective
   * timestamps only filter statements; they do not defer the balance effect.
   */
  Money getBalance(UUID accountId);

  /**
   * Balance as of an instant: only postings whose entry satisfies {@code effective_at <= asOf}
   * contribute. Use this when future-dated entries must not affect the reported balance.
   */
  default Money getBalanceAsOf(UUID accountId, java.time.Instant asOf) {
    throw new UnsupportedOperationException("As-of balance not implemented");
  }

  /** Trial balance: authoritative sum per currency/account-type for reconciliation. */
  default java.util.Map<String, Money> trialBalance() {
    throw new UnsupportedOperationException("Trial balance not implemented");
  }

  /** Account statement: ordered journal entries affecting the account. */
  default java.util.List<com.n2bank.domain.model.JournalEntry> statement(
      UUID accountId, java.time.Instant from, java.time.Instant to) {
    throw new UnsupportedOperationException("Statement not implemented");
  }
}
