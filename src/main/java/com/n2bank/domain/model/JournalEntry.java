package com.n2bank.domain.model;

import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record JournalEntry(
    UUID id,
    Instant effectiveAt,
    String description,
    String externalReference,
    List<Posting> postings) {

  public JournalEntry {
    Objects.requireNonNull(id, "Journal entry ID cannot be null");
    Objects.requireNonNull(effectiveAt, "Effective time cannot be null");

    if (description == null || description.isBlank()) {
      throw new IllegalArgumentException("Description cannot be blank");
    }
    description = description.strip();

    Objects.requireNonNull(postings, "Postings list cannot be null");
    if (postings.size() < 2) {
      throw new IllegalArgumentException(
          "A journal entry requires at least two postings");
    }

    postings = List.copyOf(postings);
    Currency detectedCurrency = postings.getFirst().amount().currency();

    Money totalDebits = Money.zero(detectedCurrency);
    Money totalCredits = Money.zero(detectedCurrency);

    for (Posting posting : postings) {
      Currency postingCurrency = posting.amount().currency();

      if (!postingCurrency.equals(detectedCurrency)) {
        throw new IllegalArgumentException("All postings must use the same currency");
      }

      switch (posting.direction()) {
        case DEBIT -> totalDebits = totalDebits.add(posting.amount());
        case CREDIT -> totalCredits = totalCredits.add(posting.amount());
      }
    }

    if (!totalDebits.subtract(totalCredits).isZero()) {
      throw new IllegalArgumentException("Total debits must equal total credits");
    }
  }
}
