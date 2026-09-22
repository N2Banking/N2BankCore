package com.n2bank.application.command;

import com.n2bank.domain.model.*;
import java.util.*;

/** Stable input for chargefee. */
public record ChargeFeeCommand(IdempotencyKey idempotencyKey, UUID accountId,
    Money amount, String description, String externalReference) implements BankCommand {
  public ChargeFeeCommand {
    Objects.requireNonNull(idempotencyKey);
    Objects.requireNonNull(accountId);
    Objects.requireNonNull(amount);
    if (!amount.isPositive()) throw new IllegalArgumentException("Amount must be positive");
    if (description == null || description.isBlank()) throw new IllegalArgumentException("Description is required");
    description = description.strip();
    if (externalReference != null) externalReference = externalReference.strip();
  }
  @Override public BankOperationType operationType() { return BankOperationType.CHARGE_FEE; }
  @Override public String requestFingerprint() {
    return CommandFingerprint.sha256(operationType(), accountId.toString(),
        amount.amount().stripTrailingZeros().toPlainString(), amount.currency().getCurrencyCode(),
        description, Objects.toString(externalReference, ""));
  }
}
