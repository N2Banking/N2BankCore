package com.n2bank.domain.model;

import java.util.Objects;
import java.util.UUID;

public record Customer(UUID id, String name, CustomerType type) {
  public Customer {
    Objects.requireNonNull(id, "Customer ID cannot be null");
    Objects.requireNonNull(type, "Customer type cannot be null");

    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("Customer name cannot be blank");
    }
  }
}
