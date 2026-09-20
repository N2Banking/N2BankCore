package com.n2bank.domain.model;

import java.util.Currency;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record Account(
    UUID accountId, String name, Optional<Customer> owner, AccountType type, Currency currency) {
  public Account {
    Objects.requireNonNull(accountId, "Account ID cannot be null");
    Objects.requireNonNull(owner, "Account owner Optional cannot be null");
    Objects.requireNonNull(type, "Account type cannot be null");
    Objects.requireNonNull(currency, "Account currency cannot be null");

    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("Account name cannot be blank");
    }
  }
}
