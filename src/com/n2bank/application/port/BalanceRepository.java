package com.n2bank.application.port;

import com.n2bank.domain.model.Money;
import java.util.UUID;

/** Reads authoritative account balances from the ledger stored in PostgreSQL. */
public interface BalanceRepository {
  Money getBalance(UUID accountId);

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
