package com.n2bank.application.fee;

import com.n2bank.domain.model.Account;
import com.n2bank.domain.model.Money;
import java.util.Objects;
import java.util.Optional;

/** Inputs available to a fee policy. The counterparty is present for operations such as transfers. */
public record FeeContext(Money amount, Account account, Optional<Account> counterparty) {
  public FeeContext {
    Objects.requireNonNull(amount, "Operation amount cannot be null");
    Objects.requireNonNull(account, "Account cannot be null");
    Objects.requireNonNull(counterparty, "Counterparty Optional cannot be null");

    if (!amount.isPositive()) {
      throw new IllegalArgumentException("Operation amount must be greater than zero");
    }
    if (!account.currency().equals(amount.currency())) {
      throw new IllegalArgumentException("Operation amount must use the account currency");
    }
    counterparty.ifPresent(
        other -> {
          if (!other.currency().equals(amount.currency())) {
            throw new IllegalArgumentException("Counterparty must use the operation currency");
          }
        });
  }

  public FeeContext(Money amount, Account account) {
    this(amount, account, Optional.empty());
  }
}
