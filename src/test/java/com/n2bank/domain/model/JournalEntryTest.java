package com.n2bank.domain.model;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JournalEntryTest {
  private static final Currency EUR = Currency.getInstance("EUR");
  private static final Currency USD = Currency.getInstance("USD");

  private Posting debit(UUID account, String amount, Currency currency) {
    return new Posting(account, new Money(amount, currency), Direction.DEBIT);
  }

  private Posting credit(UUID account, String amount, Currency currency) {
    return new Posting(account, new Money(amount, currency), Direction.CREDIT);
  }

  private JournalEntry balanced(String debitAmount, String creditAmount) {
    return new JournalEntry(
        UUID.randomUUID(),
        Instant.now(),
        "test entry",
        null,
        List.of(
            debit(UUID.randomUUID(), debitAmount, EUR),
            credit(UUID.randomUUID(), creditAmount, EUR)));
  }

  @Test
  void rejectsNullIdEffectiveAtAndPostings() {
    List<Posting> postings =
        List.of(
            debit(UUID.randomUUID(), "10.00", EUR), credit(UUID.randomUUID(), "10.00", EUR));
    assertThrows(
        NullPointerException.class,
        () -> new JournalEntry(null, Instant.now(), "desc", null, postings));
    assertThrows(
        NullPointerException.class,
        () -> new JournalEntry(UUID.randomUUID(), null, "desc", null, postings));
    assertThrows(
        NullPointerException.class,
        () -> new JournalEntry(UUID.randomUUID(), Instant.now(), "desc", null, null));
  }

  @Test
  void rejectsBlankDescription() {
    List<Posting> postings =
        List.of(
            debit(UUID.randomUUID(), "10.00", EUR), credit(UUID.randomUUID(), "10.00", EUR));
    assertThrows(
        IllegalArgumentException.class,
        () -> new JournalEntry(UUID.randomUUID(), Instant.now(), null, null, postings));
    assertThrows(
        IllegalArgumentException.class,
        () -> new JournalEntry(UUID.randomUUID(), Instant.now(), "   ", null, postings));
  }

  @Test
  void rejectsFewerThanTwoPostings() {
    UUID account = UUID.randomUUID();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new JournalEntry(
                UUID.randomUUID(),
                Instant.now(),
                "single",
                null,
                List.of(debit(account, "10.00", EUR))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new JournalEntry(UUID.randomUUID(), Instant.now(), "empty", null, List.of()));
  }

  @Test
  void rejectsMixedCurrencies() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new JournalEntry(
                UUID.randomUUID(),
                Instant.now(),
                "mixed",
                null,
                List.of(
                    debit(UUID.randomUUID(), "10.00", EUR),
                    credit(UUID.randomUUID(), "10.00", USD))));
  }

  @Test
  void rejectsUnbalancedDebitsAndCredits() {
    assertThrows(IllegalArgumentException.class, () -> balanced("10.00", "9.99"));
    assertThrows(IllegalArgumentException.class, () -> balanced("10.00", "10.01"));
  }

  @Test
  void acceptsMultiplePostingsPerSideWhenTotalsBalance() {
    JournalEntry entry =
        new JournalEntry(
            UUID.randomUUID(),
            Instant.now(),
            "split",
            "ext-1",
            List.of(
                debit(UUID.randomUUID(), "6.00", EUR),
                debit(UUID.randomUUID(), "4.00", EUR),
                credit(UUID.randomUUID(), "10.00", EUR)));
    assertEquals(3, entry.postings().size());
    assertEquals("ext-1", entry.externalReference());
  }

  @Test
  void postingListIsImmutableSnapshot() {
    List<Posting> source =
        new ArrayList<>(
            List.of(
                debit(UUID.randomUUID(), "10.00", EUR),
                credit(UUID.randomUUID(), "10.00", EUR)));
    JournalEntry entry =
        new JournalEntry(UUID.randomUUID(), Instant.now(), "immutable", null, source);

    // The exposed list cannot be modified.
    assertThrows(
        UnsupportedOperationException.class,
        () -> entry.postings().add(debit(UUID.randomUUID(), "1.00", EUR)));

    // Mutating the caller's list after construction does not affect the entry.
    source.add(debit(UUID.randomUUID(), "5.00", EUR));
    source.clear();
    assertEquals(2, entry.postings().size());
  }

  @Test
  void descriptionIsStripped() {
    JournalEntry entry = balanced("10.00", "10.00");
    assertEquals(
        "padded",
        new JournalEntry(
                entry.id(), entry.effectiveAt(), "  padded  ", null, entry.postings())
            .description());
  }
}
