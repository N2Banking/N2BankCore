package com.n2bank.domain.model;

import java.util.Objects;
import java.util.UUID;

public record Posting(UUID accountId, Money amount, Direction direction) {
  public Posting(UUID accountId, Money amount, Direction direction) {
    this.accountId = Objects.requireNonNull(accountId, "Account ID cannot be null");
    this.amount = Objects.requireNonNull(amount, "Amount cannot be null");
    this.direction = Objects.requireNonNull(direction, "Direction cannot be null");

    if (!amount.isPositive()) {
      throw new IllegalArgumentException("Posting amount must be greater than zero");
    }
  }
}
