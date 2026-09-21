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

  private final RedisClient redisClient;
  private final CacheCodec<Money> moneyCodec;

  public RedisBalanceCache(RedisClient redisClient) {
    this(redisClient, new MoneyCacheCodec());
  }

  public RedisBalanceCache(RedisClient redisClient, CacheCodec<Money> moneyCodec) {
    this.redisClient = Objects.requireNonNull(redisClient, "Redis client cannot be null");
    this.moneyCodec = Objects.requireNonNull(moneyCodec, "Money codec cannot be null");
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

    redisClient.set(key(accountId), moneyCodec.encode(balance));
    LOGGER.info("Balance cached for account {}", accountId);
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
