package com.n2bank.application.service;

import com.n2bank.application.command.*;
import com.n2bank.application.port.*;
import com.n2bank.domain.model.Posting;
import java.util.Objects;

/** Shared transaction and post-commit cache coordination for all command handlers. */
public final class OperationExecutor {
  private final OperationRepository repository;
  private final BalanceCache cache;
  public OperationExecutor(OperationRepository repository, BalanceCache cache) {
    this.repository = Objects.requireNonNull(repository);
    this.cache = Objects.requireNonNull(cache);
  }
  public OperationResult execute(BankCommand command, OperationRepository.OperationWork work) {
    OperationResult result = repository.execute(command, work);
    result.journalEntry().postings().stream().map(Posting::accountId).distinct().forEach(id -> {
      try { cache.invalidate(id); }
      catch (RuntimeException exception) {
        System.err.printf("Operation committed; cache invalidation failed for %s: %s%n",
            id, exception.getMessage());
      }
    });
    return result;
  }
}
