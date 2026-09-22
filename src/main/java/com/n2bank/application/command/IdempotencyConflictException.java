package com.n2bank.application.command;

/** A committed operation already uses this key with different input. */
public final class IdempotencyConflictException extends RuntimeException {
  public IdempotencyConflictException(String message) {
    super(message);
  }
}
