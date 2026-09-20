package com.n2bank.infrastructure.redis;

import com.n2bank.application.port.BalanceCache;
import com.n2bank.domain.model.Money;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import redis.clients.jedis.RedisClient;

/** Redis adapter for {@link BalanceCache}. */
public final class RedisBalanceCache implements BalanceCache {
  private final RedisClient redisClient;

  public RedisBalanceCache(RedisClient redisClient) {
    this.redisClient = Objects.requireNonNull(redisClient, "Redis client cannot be null");
  }

  @Override
  public Optional<Money> find(UUID accountId) {
    Objects.requireNonNull(accountId, "Account ID cannot be null");
    throw serializationNotImplemented();
  }

  @Override
  public void put(UUID accountId, Money balance) {
    Objects.requireNonNull(accountId, "Account ID cannot be null");
    Objects.requireNonNull(balance, "Balance cannot be null");
    throw serializationNotImplemented();
  }

  @Override
  public void invalidate(UUID accountId) {
    Objects.requireNonNull(accountId, "Account ID cannot be null");
    throw serializationNotImplemented();
  }

  private UnsupportedOperationException serializationNotImplemented() {
    return new UnsupportedOperationException(
        "Balance caching requires a Redis key format and Money serialization");
  }
}
