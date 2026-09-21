package com.n2bank.application.service;

import com.n2bank.application.port.BalanceCache;
import com.n2bank.application.port.JournalEntryRepository;
import com.n2bank.domain.model.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class PostingServiceTest {

  static class FailingCache implements BalanceCache {
    @Override public Optional<Money> find(UUID accountId) { throw new RuntimeException("redis down"); }
    @Override public void put(UUID accountId, Money balance) { throw new RuntimeException("redis down"); }
    @Override public void invalidate(UUID accountId) { throw new RuntimeException("redis down"); }
  }

  static class NoopJournal implements JournalEntryRepository {
    private final JournalEntry toReturn;
    NoopJournal(JournalEntry e) { this.toReturn = e; }
    @Override public Optional<JournalEntry> findById(UUID id) { return Optional.of(toReturn); }
    @Override public Optional<JournalEntry> findByIdempotencyKey(IdempotencyKey k) { return Optional.of(toReturn); }
    @Override public JournalEntry append(JournalEntry je, IdempotencyKey k) { return toReturn; }
  }

  @Test
  void redisFailureAfterCommitMustNotFailPosting() {
    Currency eur = Currency.getInstance("EUR");
    UUID cash = UUID.randomUUID(), cust = UUID.randomUUID();
    JournalEntry e = new JournalEntry(UUID.randomUUID(), Instant.now(), "test", null, List.of(
        new Posting(cash, new Money("10.00", eur), Direction.DEBIT),
        new Posting(cust, new Money("10.00", eur), Direction.CREDIT)
    ));
    JournalEntryRepository journal = new NoopJournal(e);
    PostingService svc = new PostingService(journal, new FailingCache());
    // Must not throw even though cache.invalidate fails
    JournalEntry result = svc.post(e, new IdempotencyKey("k1"));
    assertEquals(e.id(), result.id());
  }

  @Test
  void cacheInvalidationBestEffortEvenOnRetry() {
    Currency eur = Currency.getInstance("EUR");
    UUID cash = UUID.randomUUID(), cust = UUID.randomUUID();
    JournalEntry e = new JournalEntry(UUID.randomUUID(), Instant.now(), "test", null, List.of(
        new Posting(cash, new Money("5.00", eur), Direction.DEBIT),
        new Posting(cust, new Money("5.00", eur), Direction.CREDIT)
    ));
    // Simulate journal returning same entry for retry (idempotent)
    JournalEntryRepository journal = new NoopJournal(e);
    // Cache that counts invalidations
    class CountingCache implements BalanceCache {
      int count = 0;
      @Override public Optional<Money> find(UUID id) { return Optional.empty(); }
      @Override public void put(UUID id, Money m) {}
      @Override public void invalidate(UUID id) { count++; }
    }
    CountingCache cache = new CountingCache();
    PostingService svc = new PostingService(journal, cache);
    svc.post(e, new IdempotencyKey("k2"));
    assertEquals(2, cache.count); // both accounts invalidated
  }
}
