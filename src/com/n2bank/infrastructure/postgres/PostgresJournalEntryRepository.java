package com.n2bank.infrastructure.postgres;

import com.n2bank.application.port.JournalEntryRepository;
import com.n2bank.domain.model.IdempotencyKey;
import com.n2bank.domain.model.JournalEntry;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/** PostgreSQL adapter for the append-only journal. */
public final class PostgresJournalEntryRepository implements JournalEntryRepository {
  private final DataSource dataSource;

  public PostgresJournalEntryRepository(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "Data source cannot be null");
  }

  @Override
  public Optional<JournalEntry> findById(UUID journalEntryId) {
    Objects.requireNonNull(journalEntryId, "Journal entry ID cannot be null");
    throw schemaNotImplemented();
  }

  @Override
  public Optional<JournalEntry> findByIdempotencyKey(IdempotencyKey idempotencyKey) {
    Objects.requireNonNull(idempotencyKey, "Idempotency key cannot be null");
    throw schemaNotImplemented();
  }

  @Override
  public JournalEntry append(JournalEntry journalEntry, IdempotencyKey idempotencyKey) {
    Objects.requireNonNull(journalEntry, "Journal entry cannot be null");
    Objects.requireNonNull(idempotencyKey, "Idempotency key cannot be null");
    // Implementation point: use one connection and one transaction for account validation,
    // idempotency, the journal entry, and every posting. See DATABASE_IMPLEMENTATION.md.
    throw schemaNotImplemented();
  }

  private UnsupportedOperationException schemaNotImplemented() {
    return new UnsupportedOperationException(
        "Journal persistence requires the PostgreSQL schema and SQL implementation");
  }
}
