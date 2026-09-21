package com.n2bank.domain.model;

public enum AccountType {
  ASSET(Direction.DEBIT),
  LIABILITY(Direction.CREDIT),
  EQUITY(Direction.CREDIT),
  REVENUE(Direction.CREDIT),
  EXPENSE(Direction.DEBIT);

  private final Direction normalBalance;

  AccountType(Direction normalBalance) {
    this.normalBalance = normalBalance;
  }

  public Direction normalBalance() {
    return normalBalance;
  }
}
