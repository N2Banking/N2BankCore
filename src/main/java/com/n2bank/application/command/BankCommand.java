package com.n2bank.application.command;

import com.n2bank.domain.model.IdempotencyKey;

/**
 * Stable input for one banking operation.
 *
 * <p>The idempotency key identifies the operation. The request fingerprint identifies its semantic
 * contents. Persistence must atomically store both before executing the operation; command objects
 * alone do not provide idempotency.
 */
public interface BankCommand {
  IdempotencyKey idempotencyKey();

  BankOperationType operationType();

  String externalReference();

  /** Stable SHA-256 fingerprint of the fields that affect the operation's economic result. */
  String requestFingerprint();
}
