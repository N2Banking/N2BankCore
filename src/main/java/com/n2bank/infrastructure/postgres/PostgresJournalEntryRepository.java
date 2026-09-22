package com.n2bank.infrastructure.postgres;

import com.n2bank.application.port.JournalEntryRepository;
import com.n2bank.application.port.RepositoryException;
import com.n2bank.domain.model.IdempotencyKey;
import com.n2bank.domain.model.JournalEntry;
import com.n2bank.domain.model.Posting;
import com.n2bank.infrastructure.postgres.mapper.JournalEntryMapper;
import com.n2bank.infrastructure.postgres.mapper.PostingRowMapper;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.sql.DataSource;

/** PostgreSQL adapter for the append-only journal. */
public final class PostgresJournalEntryRepository implements JournalEntryRepository {
  private static final String INSERT_JOURNAL_ENTRY =
      """
      INSERT INTO journal_entries (
          id,
          effective_at,
          description,
          external_reference,
          idempotency_key
      )
      VALUES (?, ?, ?, ?, ?)
      ON CONFLICT (idempotency_key) DO NOTHING
      """;

  private static final String INSERT_POSTING =
      """
      INSERT INTO postings (
          journal_entry_id,
          posting_index,
          account_id,
          amount,
          direction,
          currency
      )
      VALUES (?, ?, ?, ?, ?, ?)
      """;

  private static final String SELECT_ENTRY_BY_IDEMPOTENCY_KEY =
      """
      SELECT id, effective_at, description, external_reference
      FROM journal_entries
      WHERE idempotency_key = ?
      """;

  private static final String SELECT_POSTINGS_BY_ENTRY_ID =
      """
      SELECT account_id, amount, direction, currency
      FROM postings
      WHERE journal_entry_id = ?
      ORDER BY posting_index
      """;

  private static final String SELECT_ENTRY_BY_ID =
      """
      SELECT id, effective_at, description, external_reference
      FROM journal_entries
      WHERE id = ?
      """;

  private static final String SELECT_ENTRIES_BY_ACCOUNT =
      """
      SELECT DISTINCT je.id, je.effective_at, je.description, je.external_reference
      FROM journal_entries je
      JOIN postings p ON p.journal_entry_id = je.id
      WHERE p.account_id = ?
        AND je.effective_at >= ?
        AND je.effective_at <= ?
      ORDER BY je.effective_at, je.id
      """;

  private static final String SELECT_ACCOUNT_FOR_UPDATE =
      """
      SELECT id, account_type, currency
      FROM accounts
      WHERE id = ?
      FOR UPDATE
      """;

  private static final String SELECT_BALANCE =
      """
      SELECT a.currency,
             a.account_type,
             CASE
               WHEN a.account_type IN ('ASSET', 'EXPENSE') THEN
                 COALESCE(SUM(CASE WHEN p.direction = 'DEBIT' THEN p.amount ELSE -p.amount END), 0)
               ELSE
                 COALESCE(SUM(CASE WHEN p.direction = 'CREDIT' THEN p.amount ELSE -p.amount END), 0)
             END AS balance
        FROM accounts a
        LEFT JOIN postings p ON p.account_id = a.id
       WHERE a.id = ?
       GROUP BY a.id, a.currency, a.account_type
      """;

  private final DataSource dataSource;
  private final PostingRowMapper postingMapper = new PostingRowMapper();
  private final JournalEntryMapper journalEntryMapper = new JournalEntryMapper();

  public PostgresJournalEntryRepository(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "Data source cannot be null");
  }

  @Override
  public Optional<JournalEntry> findById(UUID journalEntryId) {
    Objects.requireNonNull(journalEntryId, "Journal entry ID cannot be null");
    try (Connection connection = dataSource.getConnection();
        PreparedStatement entryStatement = connection.prepareStatement(SELECT_ENTRY_BY_ID)) {
      entryStatement.setObject(1, journalEntryId);
      try (ResultSet entryRow = entryStatement.executeQuery()) {
        if (!entryRow.next()) return Optional.empty();
        UUID entryId = entryRow.getObject("id", UUID.class);
        List<Posting> postings = loadPostings(connection, entryId);
        return Optional.of(journalEntryMapper.map(entryRow, postings));
      }
    } catch (SQLException exception) {
      throw new RepositoryException("Could not find journal entry " + journalEntryId, exception);
    }
  }

  @Override
  public Optional<JournalEntry> findByIdempotencyKey(IdempotencyKey idempotencyKey) {
    Objects.requireNonNull(idempotencyKey, "Idempotency key cannot be null");
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(SELECT_ENTRY_BY_IDEMPOTENCY_KEY)) {
      statement.setString(1, idempotencyKey.value());
      try (ResultSet entryRow = statement.executeQuery()) {
        if (!entryRow.next()) return Optional.empty();
        UUID entryId = entryRow.getObject("id", UUID.class);
        List<Posting> postings = loadPostings(connection, entryId);
        return Optional.of(journalEntryMapper.map(entryRow, postings));
      }
    } catch (SQLException exception) {
      throw new RepositoryException("Could not find journal entry by idempotency key " + idempotencyKey.value(), exception);
    }
  }

  @Override
  public List<JournalEntry> findByAccount(UUID accountId, java.time.Instant from, java.time.Instant to) {
    Objects.requireNonNull(accountId, "Account ID cannot be null");
    Objects.requireNonNull(from, "From cannot be null");
    Objects.requireNonNull(to, "To cannot be null");
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(SELECT_ENTRIES_BY_ACCOUNT)) {
      statement.setObject(1, accountId);
      statement.setObject(2, OffsetDateTime.ofInstant(from, ZoneOffset.UTC));
      statement.setObject(3, OffsetDateTime.ofInstant(to, ZoneOffset.UTC));
      List<JournalEntry> entries = new ArrayList<>();
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          UUID entryId = rows.getObject("id", UUID.class);
          List<Posting> postings = loadPostings(connection, entryId);
          entries.add(journalEntryMapper.map(rows, postings));
        }
      }
      return List.copyOf(entries);
    } catch (SQLException exception) {
      throw new RepositoryException("Could not load statement for account " + accountId, exception);
    }
  }

  @Override
  public JournalEntry append(JournalEntry journalEntry, IdempotencyKey idempotencyKey) {
    Objects.requireNonNull(journalEntry, "Journal entry cannot be null");
    Objects.requireNonNull(idempotencyKey, "Idempotency key cannot be null");

    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);

      try {
        boolean inserted = insertJournalEntry(connection, journalEntry, idempotencyKey);
        if (!inserted) {
          JournalEntry stored = loadByIdempotencyKey(connection, idempotencyKey);
          requireSameRequest(stored, journalEntry, idempotencyKey);
          connection.commit();
          return stored;
        }

        enforceSufficientFunds(connection, journalEntry);

        insertPostings(connection, journalEntry);
        connection.commit();
        return journalEntry;
      } catch (SQLException exception) {
        if (isDuplicateIdViolation(exception)) {
          rollback(connection, exception);
          throw new RepositoryException(
              "Journal entry id " + journalEntry.id() + " already exists with a different idempotency key", exception);
        }
        rollback(connection, exception);
        throw exception;
      } catch (RepositoryException exception) {
        rollback(connection, exception);
        throw exception;
      } catch (RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (RepositoryException exception) {
      throw exception;
    } catch (SQLException exception) {
      throw new RepositoryException(
          "Could not append journal entry " + journalEntry.id(), exception);
    }
  }

  /** Uses the caller's transaction; never commits or opens another connection. */
  JournalEntry appendOperation(Connection connection, JournalEntry entry, IdempotencyKey key)
      throws SQLException {
    if (!insertJournalEntry(connection, entry, key)) {
      throw new com.n2bank.application.command.IdempotencyConflictException(
          "This key already belongs to a legacy journal posting");
    }
    enforceSufficientFunds(connection, entry);
    insertPostings(connection, entry);
    return loadByIdempotencyKey(connection, key);
  }

  JournalEntry loadById(Connection connection, UUID id) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(SELECT_ENTRY_BY_ID)) {
      statement.setObject(1, id);
      try (ResultSet row = statement.executeQuery()) {
        if (!row.next()) throw new IllegalArgumentException("Journal entry does not exist: " + id);
        return journalEntryMapper.map(row, loadPostings(connection, id));
      }
    }
  }

  private boolean isDuplicateIdViolation(SQLException exception) {
    if (!"23505".equals(exception.getSQLState())) return false;
    String msg = exception.getMessage();
    return msg != null && (msg.contains("journal_entries_pkey") || msg.contains("journal_entries_id") || msg.contains("journal_entries") && msg.contains("id"));
  }

  private void enforceSufficientFunds(Connection connection, JournalEntry entry) throws SQLException {
    // Every append acquires account locks in the same UUID order.
    List<UUID> affectedAccounts =
        entry.postings().stream().map(Posting::accountId).distinct().sorted().toList();

    Map<UUID, List<Posting>> postingsByAccount =
        entry.postings().stream().collect(Collectors.groupingBy(Posting::accountId));

    Map<UUID, String> accountTypes = new HashMap<>();
    Map<UUID, Currency> accountCurrencies = new HashMap<>();

    for (UUID accountId : affectedAccounts) {
      try (PreparedStatement lock = connection.prepareStatement(SELECT_ACCOUNT_FOR_UPDATE)) {
        lock.setObject(1, accountId);
        try (ResultSet rs = lock.executeQuery()) {
          if (!rs.next()) {
            throw new RepositoryException("Account does not exist: " + accountId);
          }
          accountTypes.put(accountId, rs.getString("account_type"));
          accountCurrencies.put(accountId, Currency.getInstance(rs.getString("currency")));
        }
      }
    }

    for (UUID accountId : affectedAccounts) {
      String accountType = accountTypes.get(accountId);
      BigDecimal oldBalance = readBalance(connection, accountId);
      List<Posting> postings = postingsByAccount.get(accountId);

      BigDecimal netChange = BigDecimal.ZERO;
      for (Posting p : postings) {
        BigDecimal amt = p.amount().amount();
        if ("ASSET".equals(accountType) || "EXPENSE".equals(accountType)) {
          netChange = p.direction().name().equals("DEBIT") ? netChange.add(amt) : netChange.subtract(amt);
        } else {
          netChange = p.direction().name().equals("CREDIT") ? netChange.add(amt) : netChange.subtract(amt);
        }
      }
      BigDecimal newBalance = oldBalance.add(netChange);
      if (newBalance.compareTo(BigDecimal.ZERO) < 0) {
        throw new RepositoryException(
            "Insufficient funds in account " + accountId + ": balance " + oldBalance + " would become " + newBalance);
      }
    }
  }

  private BigDecimal readBalance(Connection connection, UUID accountId) throws SQLException {
    try (PreparedStatement st = connection.prepareStatement(SELECT_BALANCE)) {
      st.setObject(1, accountId);
      try (ResultSet rs = st.executeQuery()) {
        if (!rs.next()) {
          throw new RepositoryException("Account does not exist: " + accountId);
        }
        return rs.getBigDecimal("balance");
      }
    }
  }

  private boolean insertJournalEntry(
      Connection connection, JournalEntry entry, IdempotencyKey idempotencyKey)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(INSERT_JOURNAL_ENTRY)) {
      statement.setObject(1, entry.id());
      statement.setObject(
          2, OffsetDateTime.ofInstant(entry.effectiveAt().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC));
      statement.setString(3, entry.description());
      statement.setString(4, entry.externalReference());
      statement.setString(5, idempotencyKey.value());
      return statement.executeUpdate() == 1;
    }
  }

  private void insertPostings(Connection connection, JournalEntry entry) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(INSERT_POSTING)) {
      for (int index = 0; index < entry.postings().size(); index++) {
        Posting posting = entry.postings().get(index);
        statement.setObject(1, entry.id());
        statement.setInt(2, index);
        statement.setObject(3, posting.accountId());
        statement.setBigDecimal(4, posting.amount().amount().stripTrailingZeros());
        statement.setString(5, posting.direction().name());
        statement.setString(6, posting.amount().currency().getCurrencyCode());
        statement.addBatch();
      }

      int[] results = statement.executeBatch();
      if (results.length != entry.postings().size()) {
        throw new SQLException("PostgreSQL did not report a result for every posting");
      }
      for (int result : results) {
        if (result == Statement.EXECUTE_FAILED || result == 0) {
          throw new SQLException("PostgreSQL failed to insert a posting");
        }
      }
    }
  }

  JournalEntry loadByIdempotencyKey(
      Connection connection, IdempotencyKey idempotencyKey) throws SQLException {
    try (PreparedStatement entryStatement =
        connection.prepareStatement(SELECT_ENTRY_BY_IDEMPOTENCY_KEY)) {
      entryStatement.setString(1, idempotencyKey.value());

      try (ResultSet entryRow = entryStatement.executeQuery()) {
        if (!entryRow.next()) {
          throw new SQLException(
              "Idempotency conflict occurred but the stored journal entry was not found");
        }

        UUID entryId = entryRow.getObject("id", UUID.class);
        List<Posting> postings = loadPostings(connection, entryId);
        return journalEntryMapper.map(entryRow, postings);
      }
    }
  }

  private List<Posting> loadPostings(Connection connection, UUID entryId) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(SELECT_POSTINGS_BY_ENTRY_ID)) {
      statement.setObject(1, entryId);
      try (ResultSet rows = statement.executeQuery()) {
        List<Posting> postings = new ArrayList<>();
        while (rows.next()) {
          postings.add(postingMapper.map(rows));
        }
        return List.copyOf(postings);
      }
    }
  }

  private void requireSameRequest(
      JournalEntry stored, JournalEntry requested, IdempotencyKey idempotencyKey) {
    if (!sameEntry(stored, requested)) {
      throw new RepositoryException(
          "Idempotency key " + idempotencyKey.value() + " was already used for another entry");
    }
  }

  private boolean sameEntry(JournalEntry first, JournalEntry second) {
    if (!first.id().equals(second.id())
        || !first.effectiveAt().truncatedTo(ChronoUnit.MICROS)
            .equals(second.effectiveAt().truncatedTo(ChronoUnit.MICROS))
        || !first.description().equals(second.description())
        || !Objects.equals(first.externalReference(), second.externalReference())
        || first.postings().size() != second.postings().size()) {
      return false;
    }

    for (int index = 0; index < first.postings().size(); index++) {
      Posting left = first.postings().get(index);
      Posting right = second.postings().get(index);
      if (!left.accountId().equals(right.accountId())
          || left.direction() != right.direction()
          || !left.amount().currency().equals(right.amount().currency())
          || left.amount().amount().compareTo(right.amount().amount()) != 0) {
        return false;
      }
    }
    return true;
  }

  private void rollback(Connection connection, Exception original) {
    try {
      connection.rollback();
    } catch (SQLException rollbackFailure) {
      original.addSuppressed(rollbackFailure);
    }
  }

  private UnsupportedOperationException schemaNotImplemented() {
    return new UnsupportedOperationException(
        "Journal persistence requires the PostgreSQL schema and SQL implementation");
  }
}
