package com.n2bank.domain.model;

public enum Direction {
  DEBIT,
  CREDIT;

  public Direction opposite() {
    return this == DEBIT ? CREDIT : DEBIT;
  }
}
