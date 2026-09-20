package com.n2bank.domain.model;

import java.util.Objects;
import java.util.UUID;

public record IdempotencyKey(String value) {
  public IdempotencyKey {
    Objects.requireNonNull(value, "Idempotency key cannot be null");

    value = value.trim();

    if (value.isEmpty()) {
      throw new IllegalArgumentException("Idempotency key cannot be blank");
    }

    if (value.length() > 255) {
      throw new IllegalArgumentException("Idempotency key cannot exceed 255 characters");
    }
  }

  public static IdempotencyKey create() {
    return new IdempotencyKey(UUID.randomUUID().toString());
  }
}
