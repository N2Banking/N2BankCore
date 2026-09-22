package com.n2bank.application.service;

import com.n2bank.application.command.*;
import com.n2bank.application.fee.FeeContext;
import com.n2bank.domain.model.*;
import java.util.*;
import java.util.function.Function;

/** Applies reversal rules inside the shared operation transaction. */
public final class ReversalHandler {
  private final OperationExecutor executor;

  public ReversalHandler(OperationExecutor executor) {
    this.executor = Objects.requireNonNull(executor);
  }

  public OperationResult handle(ReversalCommand command) {
    Objects.requireNonNull(command);
    return executor.execute(command, accounts -> {
      JournalEntry original = accounts.journalEntry(command.originalJournalEntryId());
      List<Posting> lines = original.postings().stream().map(p ->
          new Posting(p.accountId(), p.amount(),
              p.direction() == Direction.DEBIT ? Direction.CREDIT : Direction.DEBIT)).toList();
      return HandlerSupport.entry(command, "Reversal of " + original.id(), lines);
    });
  }
}
