package com.n2bank.infrastructure.postgres;

import com.n2bank.application.command.*;
import com.n2bank.application.port.OperationRepository;
import com.n2bank.application.port.RepositoryException;
import com.n2bank.domain.model.JournalEntry;
import java.sql.*;
import java.util.Objects;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import javax.sql.DataSource;

/** Owns the transaction containing the operation claim and its complete journal entry. */
public final class PostgresOperationRepository implements OperationRepository {
  private final DataSource dataSource;
  private final PostgresAccountRepository accounts;
  private final PostgresJournalEntryRepository journal;

  public PostgresOperationRepository(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource);
    accounts = new PostgresAccountRepository(dataSource);
    journal = new PostgresJournalEntryRepository(dataSource);
  }

  @Override
  public OperationResult execute(BankCommand command, OperationWork work) {
    Objects.requireNonNull(command);
    Objects.requireNonNull(work);
    String fingerprint = command.requestFingerprint();
    for (int attempt = 1; ; attempt++) {
      try {
        return executeOnce(command, fingerprint, work);
      } catch (RepositoryException failure) {
        if (attempt >= 3 || !isDeadlock(failure)) throw failure;
        // The failed transaction and its connection are released before waiting.
        if (Thread.currentThread().isInterrupted()) throw failure;
        long minimumMillis = 25L << (attempt - 1);
        try {
          Thread.sleep(ThreadLocalRandom.current().nextLong(minimumMillis, minimumMillis * 2 + 1));
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new RepositoryException("Interrupted while retrying banking operation", interrupted);
        }
      }
    }
  }

  private static boolean isDeadlock(Throwable failure) {
    Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
    return isDeadlock(failure, visited);
  }

  private static boolean isDeadlock(Throwable failure, Set<Throwable> visited) {
    if (failure == null || !visited.add(failure)) return false;
    if (failure instanceof SQLException sql) {
      if ("40P01".equals(sql.getSQLState())) return true;
      if (isDeadlock(sql.getNextException(), visited)) return true;
    }
    // Suppressed cleanup failures must not turn business errors into retries.
    return isDeadlock(failure.getCause(), visited);
  }

  private OperationResult executeOnce(BankCommand command, String fingerprint, OperationWork work) {
    try (Connection connection = dataSource.getConnection()) {
      // The conflict lookup needs a fresh snapshot after waiting for the winning insert.
      connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      connection.setAutoCommit(false);
      try {
        boolean claimed;
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO operations (idempotency_key, operation_type, request_fingerprint)
            VALUES (?, ?, ?) ON CONFLICT (idempotency_key) DO NOTHING
            """)) {
          statement.setString(1, command.idempotencyKey().value());
          statement.setString(2, command.operationType().name());
          statement.setString(3, fingerprint);
          claimed = statement.executeUpdate() == 1;
        }
        JournalEntry entry;
        if (!claimed) {
          try (PreparedStatement statement = connection.prepareStatement("""
              SELECT operation_type, request_fingerprint FROM operations WHERE idempotency_key = ?
              """)) {
            statement.setString(1, command.idempotencyKey().value());
            try (ResultSet row = statement.executeQuery()) {
              if (!row.next()) throw new SQLException("Committed operation was not found");
              if (!command.operationType().name().equals(row.getString(1))
                  || !fingerprint.equals(row.getString(2))) {
                throw new IdempotencyConflictException("Idempotency key was already used with different input");
              }
            }
          }
          entry = journal.loadByIdempotencyKey(connection, command.idempotencyKey());
        } else {
          entry = work.prepare(new OperationContext() {
            @Override public com.n2bank.domain.model.Account apply(java.util.UUID id) {
            try {
              return accounts.findById(connection, id)
                  .orElseThrow(() -> new IllegalArgumentException("Account does not exist: " + id));
            } catch (SQLException exception) {
              throw new RepositoryException("Could not read operation account", exception);
            }
            }
            @Override public JournalEntry journalEntry(java.util.UUID id) {
              try {
                return journal.loadById(connection, id);
              } catch (SQLException exception) {
                throw new RepositoryException("Could not read original journal entry", exception);
              }
            }
          });
          entry = journal.appendOperation(connection, entry, command.idempotencyKey());
        }
        connection.commit();
        return new OperationResult(entry, !claimed);
      } catch (SQLException | RuntimeException exception) {
        try {
          connection.rollback();
        } catch (SQLException rollbackFailure) {
          exception.addSuppressed(rollbackFailure);
        }
        throw exception;
      }
    } catch (SQLException exception) {
      // Preserve SQLSTATE for the retry policy.
      throw new RepositoryException("Could not execute banking operation", exception);
    }
  }
}
