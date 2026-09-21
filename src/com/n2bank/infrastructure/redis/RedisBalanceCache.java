package com.n2bank.infrastructure.redis;

import com.n2bank.application.port.BalanceCache;
import com.n2bank.domain.model.Money;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.RedisClient;

/** Redis adapter for {@link BalanceCache}. */
public final class RedisBalanceCache implements BalanceCache {
  private static final Logger LOGGER = LoggerFactory.getLogger(RedisBalanceCache.class);
  private static final String KEY_PREFIX = "n2bank:balance:v1:";
  /** Short TTL prevents stale reads after a crash where invalidate was missed. */
  public static final long DEFAULT_TTL_SECONDS = 30;

  private final RedisClient redisClient;
  private final CacheCodec<Money> moneyCodec;
  private final long ttlSeconds;

  public RedisBalanceCache(RedisClient redisClient) {
    this(redisClient, new MoneyCacheCodec(), DEFAULT_TTL_SECONDS);
  }

  public RedisBalanceCache(RedisClient redisClient, CacheCodec<Money> moneyCodec) {
    this(redisClient, moneyCodec, DEFAULT_TTL_SECONDS);
  }

  public RedisBalanceCache(RedisClient redisClient, CacheCodec<Money> moneyCodec, long ttlSeconds) {
    this.redisClient = Objects.requireNonNull(redisClient, "Redis client cannot be null");
    this.moneyCodec = Objects.requireNonNull(moneyCodec, "Money codec cannot be null");
    if (ttlSeconds < 1) {
      throw new IllegalArgumentException("TTL must be positive");
    }
    this.ttlSeconds = ttlSeconds;
  }

  @Override
  public Optional<Money> find(UUID accountId) {
    Objects.requireNonNull(accountId, "Account ID cannot be null");
    Optional<Money> balance =
        Optional.ofNullable(redisClient.get(key(accountId))).map(moneyCodec::decode);
    LOGGER.info("Balance cache {} for account {}", balance.isPresent() ? "hit" : "miss", accountId);
    return balance;
  }

  @Override
  public void put(UUID accountId, Money balance) {
    Objects.requireNonNull(accountId, "Account ID cannot be null");
    Objects.requireNonNull(balance, "Balance cannot be null");

    redisClient.setex(key(accountId), ttlSeconds, moneyCodec.encode(balance));
    LOGGER.info("Balance cached for account {} with TTL {}s", accountId, ttlSeconds);
  }

  @Override
  public void invalidate(UUID accountId) {
    Objects.requireNonNull(accountId, "Account ID cannot be null");

    redisClient.del(key(accountId));
    LOGGER.info("Balance cache invalidated for account {}", accountId);
  }

  private String key(UUID accountId) {
    return KEY_PREFIX + accountId;
  }
}
