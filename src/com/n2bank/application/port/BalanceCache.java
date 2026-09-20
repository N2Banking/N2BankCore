package com.n2bank.application.port;

import com.n2bank.domain.model.Money;
import java.util.Optional;
import java.util.UUID;

/**
 * Optional cache for account balances.
 *
 * <p>A cache miss or Redis outage must never decide whether a journal entry is accepted. The
 * authoritative balance is always derived from PostgreSQL.
 */
public interface BalanceCache {
  Optional<Money> find(UUID accountId);

  void put(UUID accountId, Money balance);

  void invalidate(UUID accountId);
}
