package com.n2bank.infrastructure.postgres;

import com.n2bank.application.command.*;
import com.n2bank.application.port.RepositoryException;
import com.n2bank.domain.model.*;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Reuses the disposable-schema integration fixture and its baseline tests. */
class DeadlockRetryTest extends PostgresOperationRepositoryTest {
  @Test
  void retriesWrappedDeadlocksAndCommitsOneTransfer() {
    var calls = new AtomicInteger();
    var request = command(IdempotencyKey.create(), "10");
    var result = new PostgresOperationRepository(database).execute(request, context -> {
      if (calls.incrementAndGet() < 3) throw failure("40P01");
      return new JournalEntry(UUID.randomUUID(), Instant.now(), "Transfer", null, List.of(
          new Posting(source, request.amount(), Direction.DEBIT),
          new Posting(destination, request.amount(), Direction.CREDIT)));
    });
    assertEquals(3, calls.get());
    assertFalse(result.replayed());
    assertEquals(new OperationResult(result.journalEntry(), true), operations.handle(request));
    assertEquals(0, new PostgresBalanceRepository(database).getBalance(source).amount()
        .compareTo(new java.math.BigDecimal("90")));
  }

  @Test
  void exhaustedDeadlocksLeaveKeyReusable() {
    var calls = new AtomicInteger();
    var request = command(IdempotencyKey.create(), "10");
    var thrown = assertThrows(RepositoryException.class,
        () -> new PostgresOperationRepository(database).execute(request, context -> {
          calls.incrementAndGet();
          throw failure("40P01");
        }));
    assertEquals("40P01", ((SQLException) thrown.getCause()).getSQLState());
    assertEquals(3, calls.get());
    assertTrue(journal.findByIdempotencyKey(request.idempotencyKey()).isEmpty());
    assertFalse(operations.handle(request).replayed());
  }

  @Test
  void otherErrorsAreNotRetried() {
    for (RuntimeException failure : List.of(failure("23514"), new IllegalArgumentException("Business error"))) {
      var calls = new AtomicInteger();
      assertSame(failure, assertThrows(RuntimeException.class,
          () -> new PostgresOperationRepository(database).execute(command(IdempotencyKey.create(), "10"), context -> {
            calls.incrementAndGet();
            throw failure;
          })));
      assertEquals(1, calls.get());
    }
  }

  @Test
  void interruptionStopsRetries() {
    var calls = new AtomicInteger();
    try {
      assertThrows(RepositoryException.class,
          () -> new PostgresOperationRepository(database).execute(command(IdempotencyKey.create(), "10"), context -> {
            calls.incrementAndGet();
            Thread.currentThread().interrupt();
            throw failure("40P01");
          }));
      assertEquals(1, calls.get());
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
  }

  private static RepositoryException failure(String state) {
    return new RepositoryException("Injected database error", new SQLException("Injected", state));
  }
}
