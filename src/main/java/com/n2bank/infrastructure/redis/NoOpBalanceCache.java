package com.n2bank.infrastructure.redis;

import com.n2bank.application.port.BalanceCache;
import com.n2bank.domain.model.Money;
import java.util.Optional;
import java.util.UUID;

/** Cache adapter for rollback-only workflows; it never exposes uncommitted balances. */
public final class NoOpBalanceCache implements BalanceCache {
  @Override
  public Optional<Money> find(UUID accountId) {
    return Optional.empty();
  }

  @Override
  public void put(UUID accountId, Money balance) {}

  @Override
  public void invalidate(UUID accountId) {}
}
