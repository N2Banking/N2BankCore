package com.n2bank.infrastructure.redis;

import com.n2bank.domain.model.Money;
import java.util.Objects;

/** Stable, locale-independent Redis representation for {@link Money}. */
public final class MoneyCacheCodec implements CacheCodec<Money> {
  private static final String VERSION = "v1";
  private static final String SEPARATOR = "|";

  @Override
  public String encode(Money money) {
    Objects.requireNonNull(money, "Money cannot be null");
    return VERSION
        + SEPARATOR
        + money.amount().toPlainString()
        + SEPARATOR
        + money.currency().getCurrencyCode();
  }

  @Override
  public Money decode(String value) {
    Objects.requireNonNull(value, "Cached money cannot be null");

    String[] fields = value.split("\\|", -1);
    if (fields.length != 3 || !VERSION.equals(fields[0])) {
      throw new IllegalArgumentException("Unsupported cached Money representation");
    }

    return new Money(fields[1], fields[2]);
  }
}
