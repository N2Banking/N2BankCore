package com.n2bank.application.command;

import com.n2bank.domain.model.*;
import java.util.*;

/** Stable input for deposit. */
public record DepositCommand(IdempotencyKey idempotencyKey, UUID accountId,
    Money amount, String externalReference) implements BankCommand {
  public DepositCommand {
    Objects.requireNonNull(idempotencyKey);
    Objects.requireNonNull(accountId);
    Objects.requireNonNull(amount);
    if (!amount.isPositive()) throw new IllegalArgumentException("Amount must be positive");

    if (externalReference != null) externalReference = externalReference.strip();
  }
  @Override public BankOperationType operationType() { return BankOperationType.DEPOSIT; }
  @Override public String requestFingerprint() {
    return CommandFingerprint.sha256(operationType(), accountId.toString(),
        amount.amount().stripTrailingZeros().toPlainString(), amount.currency().getCurrencyCode(),
        Objects.toString(externalReference, ""));
  }
}
