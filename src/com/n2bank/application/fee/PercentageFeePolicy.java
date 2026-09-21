package com.n2bank.application.fee;

import com.n2bank.domain.model.FeeQuote;
import com.n2bank.domain.model.FeeType;
import com.n2bank.domain.model.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** Calculates a percentage of the operation amount. A rate of 0.01 represents one percent. */
public final class PercentageFeePolicy implements FeePolicy {
  private final FeeType type;
  private final BigDecimal rate;
  private final RoundingMode roundingMode;
  private final String description;

  public PercentageFeePolicy(
      FeeType type, BigDecimal rate, RoundingMode roundingMode, String description) {
    this.type = Objects.requireNonNull(type, "Fee type cannot be null");
    this.rate = Objects.requireNonNull(rate, "Fee rate cannot be null");
    this.roundingMode = Objects.requireNonNull(roundingMode, "Rounding mode cannot be null");
    if (rate.signum() < 0) {
      throw new IllegalArgumentException("Fee rate cannot be negative");
    }
    if (description == null || description.isBlank()) {
      throw new IllegalArgumentException("Fee description cannot be blank");
    }
    this.description = description.strip();
  }

  @Override
  public FeeType type() {
    return type;
  }

  @Override
  public FeeQuote calculate(FeeContext context) {
    Objects.requireNonNull(context, "Fee context cannot be null");
    int scale = Math.max(context.amount().currency().getDefaultFractionDigits(), 0);
    Money fee =
        new Money(
            context.amount().amount().multiply(rate).setScale(scale, roundingMode),
            context.amount().currency());
    return new FeeQuote(type, fee, description);
  }
}
