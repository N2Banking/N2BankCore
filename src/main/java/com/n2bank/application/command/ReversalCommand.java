package com.n2bank.application.command;

import com.n2bank.domain.model.IdempotencyKey;
import java.util.*;

/** Reverse a persisted journal entry; the caller cannot supply replacement postings. */
public record ReversalCommand(IdempotencyKey idempotencyKey, UUID originalJournalEntryId,
    String externalReference) implements BankCommand {
  public ReversalCommand {
    Objects.requireNonNull(idempotencyKey);
    Objects.requireNonNull(originalJournalEntryId);
    if (externalReference != null) externalReference = externalReference.strip();
  }
  @Override public BankOperationType operationType() { return BankOperationType.REVERSAL; }
  @Override public String requestFingerprint() {
    return CommandFingerprint.sha256(operationType(), originalJournalEntryId.toString(),
        Objects.toString(externalReference, ""));
  }
}
