package com.n2bank.domain.model;

import java.util.Objects;

/** Result of evaluating a fee policy. */
public record FeeQuote(FeeType type, Money amount, String description) {
  public FeeQuote {
    Objects.requireNonNull(type, "Fee type cannot be null");
    Objects.requireNonNull(amount, "Fee amount cannot be null");
    if (amount.amount().signum() < 0) {
      throw new IllegalArgumentException("Fee amount cannot be negative");
    }
    if (description == null || description.isBlank()) {
      throw new IllegalArgumentException("Fee description cannot be blank");
    }
    description = description.strip();
  }
}
