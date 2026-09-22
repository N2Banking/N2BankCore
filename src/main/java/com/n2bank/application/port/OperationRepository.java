package com.n2bank.application.port;

import com.n2bank.application.command.BankCommand;
import com.n2bank.application.command.OperationResult;
import com.n2bank.domain.model.Account;
import com.n2bank.domain.model.JournalEntry;
import java.util.UUID;
import java.util.function.Function;

/** Claims a key, builds and posts an entry in one transaction, or returns the stored result. */
public interface OperationRepository {
  OperationResult execute(BankCommand command, OperationWork work);

  @FunctionalInterface
  interface OperationWork {
    /**
     * Runs for a new claim, and may run again after a rolled-back deadlock attempt.
     * Account reads use the operation's transaction. Must have no external effects.
     */
    JournalEntry prepare(OperationContext accounts);
  }

  interface OperationContext extends Function<UUID, Account> {
    /** Loads an existing journal entry on the same transaction connection. */
    JournalEntry journalEntry(UUID id);
  }
}
