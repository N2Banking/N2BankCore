package com.n2bank.application.command;

import com.n2bank.domain.model.IdempotencyKey;
import com.n2bank.domain.model.Money;
import java.util.Objects;
import java.util.UUID;

/** Stable input for a transfer between two customer accounts. */
public record TransferCommand(
    IdempotencyKey idempotencyKey,
    UUID sourceAccountId,
    UUID destinationAccountId,
    Money amount,
    String externalReference)
    implements BankCommand {

  public TransferCommand {
    Objects.requireNonNull(idempotencyKey, "Idempotency key cannot be null");
    Objects.requireNonNull(sourceAccountId, "Source account ID cannot be null");
    Objects.requireNonNull(destinationAccountId, "Destination account ID cannot be null");
    Objects.requireNonNull(amount, "Amount cannot be null");

    if (sourceAccountId.equals(destinationAccountId)) {
      throw new IllegalArgumentException("Source and destination accounts must be different");
    }
    if (!amount.isPositive()) {
      throw new IllegalArgumentException("Transfer amount must be greater than zero");
    }
    if (externalReference != null) {
      externalReference = externalReference.strip();
    }
  }

  @Override
  public BankOperationType operationType() {
    return BankOperationType.TRANSFER;
  }

  @Override
  public String requestFingerprint() {
    return CommandFingerprint.sha256(
        operationType(),
        sourceAccountId.toString(),
        destinationAccountId.toString(),
        amount.amount().stripTrailingZeros().toPlainString(),
        amount.currency().getCurrencyCode(),
        Objects.toString(externalReference, ""));
  }
}
