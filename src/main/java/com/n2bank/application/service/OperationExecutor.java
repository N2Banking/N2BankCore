package com.n2bank.application.service;

import com.n2bank.application.command.*;
import com.n2bank.application.port.*;
import com.n2bank.domain.model.Posting;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Shared transaction and post-commit cache coordination for all command handlers. */
public final class OperationExecutor {
  private static final Logger LOG = LoggerFactory.getLogger(OperationExecutor.class);
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
        LOG.warn("Operation committed; cache invalidation failed for {}", id, exception);
      }
    });
    return result;
  }
}
