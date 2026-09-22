package com.n2bank.application.command;

import com.n2bank.domain.model.JournalEntry;
import java.util.Objects;

/** The committed journal entry, including whether it came from an earlier execution. */
public record OperationResult(JournalEntry journalEntry, boolean replayed) {
  public OperationResult {
    Objects.requireNonNull(journalEntry, "Journal entry cannot be null");
  }
}
