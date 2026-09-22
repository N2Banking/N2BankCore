package com.n2bank.application.command;

/** Operation discriminator stored alongside an idempotency key. */
public enum BankOperationType {
  TRANSFER, DEPOSIT, WITHDRAWAL, CHARGE_FEE, REVERSAL
}
