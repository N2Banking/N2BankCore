package com.n2bank.application.port;

import com.n2bank.domain.model.Money;
import java.util.UUID;

/** Reads authoritative account balances from the ledger stored in PostgreSQL. */
public interface BalanceRepository {
  Money getBalance(UUID accountId);
}
