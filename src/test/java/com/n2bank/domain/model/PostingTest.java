package com.n2bank.domain.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PostingTest {
  private static final Currency EUR = Currency.getInstance("EUR");

  @Test
  void rejectsNullFields() {
    UUID id = UUID.randomUUID();
    Money amount = new Money("10.00", EUR);
    assertThrows(NullPointerException.class, () -> new Posting(null, amount, Direction.DEBIT));
    assertThrows(NullPointerException.class, () -> new Posting(id, null, Direction.DEBIT));
    assertThrows(NullPointerException.class, () -> new Posting(id, amount, null));
  }

  @Test
  void rejectsZeroAndNegativeAmounts() {
    UUID id = UUID.randomUUID();
    assertThrows(
        IllegalArgumentException.class,
        () -> new Posting(id, new Money("0.00", EUR), Direction.DEBIT));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Posting(id, new Money("-0.01", EUR), Direction.CREDIT));
    assertThrows(
        IllegalArgumentException.class, () -> new Posting(id, new Money("0", EUR), Direction.CREDIT));
  }

  @Test
  void acceptsPositiveAmountInEitherDirection() {
    UUID id = UUID.randomUUID();
    assertEquals(
        Direction.DEBIT, new Posting(id, new Money("0.01", EUR), Direction.DEBIT).direction());
    assertEquals(
        Direction.CREDIT, new Posting(id, new Money("100.00", EUR), Direction.CREDIT).direction());
  }
}
