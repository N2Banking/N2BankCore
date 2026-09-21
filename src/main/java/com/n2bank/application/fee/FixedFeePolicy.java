package com.n2bank.application.fee;

import com.n2bank.domain.model.FeeQuote;
import com.n2bank.domain.model.FeeType;
import com.n2bank.domain.model.Money;
import java.util.Objects;

/** Charges the same monetary amount for every matching operation. */
public final class FixedFeePolicy implements FeePolicy {
  private final FeeType type;
  private final Money fee;
  private final String description;

  public FixedFeePolicy(FeeType type, Money fee, String description) {
    this.type = Objects.requireNonNull(type, "Fee type cannot be null");
    this.fee = Objects.requireNonNull(fee, "Fee cannot be null");
    if (fee.amount().signum() < 0) {
      throw new IllegalArgumentException("Fee cannot be negative");
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
    if (!fee.currency().equals(context.amount().currency())) {
      throw new IllegalArgumentException("Fixed fee currency does not match operation currency");
    }
    return new FeeQuote(type, fee, description);
  }
}
