package com.n2bank.application.service;

import com.n2bank.application.port.BalanceCache;
import com.n2bank.application.port.BalanceRepository;
import com.n2bank.domain.model.Money;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Provides account balances while keeping PostgreSQL as the source of truth. */
public final class BalanceService {
  private final BalanceRepository balances;
  private final BalanceCache cache;

  public BalanceService(BalanceRepository balances, BalanceCache cache) {
    this.balances = Objects.requireNonNull(balances, "Balance repository cannot be null");
    this.cache = Objects.requireNonNull(cache, "Balance cache cannot be null");
  }

  /**
   * Returns the account's normal balance. Asset and expense accounts are debit minus credit;
   * liability, equity, and revenue accounts are credit minus debit.
   *
   * <p>The cache is optional. A cache miss, unfinished adapter, or Redis failure falls back to
   * PostgreSQL and never prevents the balance from being read.
   */
  public Money getBalance(UUID accountId) {
    Objects.requireNonNull(accountId, "Account ID cannot be null");

    Optional<Money> cachedBalance = findCached(accountId);
    if (cachedBalance.isPresent()) {
      return cachedBalance.get();
    }

    Money balance = balances.getBalance(accountId);
    cacheBalance(accountId, balance);
    return balance;
  }

  /** Trial balance is always authoritative from PostgreSQL - never cached. */
  public java.util.Map<String, Money> trialBalance() {
    return balances.trialBalance();
  }

  /** Statement bypasses cache to avoid stale reads; see RedisBalanceCache TTL. */
  public java.util.List<com.n2bank.domain.model.JournalEntry> statement(
      UUID accountId, java.time.Instant from, java.time.Instant to) {
    Objects.requireNonNull(accountId, "Account ID cannot be null");
    Objects.requireNonNull(from, "From cannot be null");
    Objects.requireNonNull(to, "To cannot be null");
    return balances.statement(accountId, from, to);
  }

  private Optional<Money> findCached(UUID accountId) {
    try {
      return cache.find(accountId);
    } catch (RuntimeException cacheFailure) {
      return Optional.empty();
    }
  }

  private void cacheBalance(UUID accountId, Money balance) {
    try {
      cache.put(accountId, balance);
    } catch (RuntimeException cacheFailure) {
      // PostgreSQL already supplied the authoritative balance; caching is best effort.
    }
  }
}
