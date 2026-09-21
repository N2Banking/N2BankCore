package com.n2bank.application.fee;

import com.n2bank.domain.model.FeeQuote;
import com.n2bank.domain.model.FeeType;
import com.n2bank.domain.model.Money;
import java.util.Objects;

/** Explicit policy for an operation that currently has no charge. */
public final class NoFeePolicy implements FeePolicy {
  private final FeeType type;

  public NoFeePolicy(FeeType type) {
    this.type = Objects.requireNonNull(type, "Fee type cannot be null");
  }

  @Override
  public FeeType type() {
    return type;
  }

  @Override
  public FeeQuote calculate(FeeContext context) {
    Objects.requireNonNull(context, "Fee context cannot be null");
    return new FeeQuote(type, Money.zero(context.amount().currency()), "No fee");
  }
}
