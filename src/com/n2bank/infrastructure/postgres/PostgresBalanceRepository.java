package com.n2bank.infrastructure.postgres;

import com.n2bank.application.port.BalanceRepository;
import com.n2bank.application.port.RepositoryException;
import com.n2bank.domain.model.JournalEntry;
import com.n2bank.domain.model.Money;
import com.n2bank.domain.model.Posting;
import com.n2bank.infrastructure.postgres.mapper.JournalEntryMapper;
import com.n2bank.infrastructure.postgres.mapper.PostingRowMapper;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/** Calculates balances directly from the append-only PostgreSQL ledger. */
public final class PostgresBalanceRepository implements BalanceRepository {
  private static final String SELECT_BALANCE =
      """
      SELECT a.currency,
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

  private static final String SELECT_TRIAL_BALANCE =
      """
      SELECT a.account_type,
             a.currency,
             CASE
               WHEN a.account_type IN ('ASSET', 'EXPENSE') THEN
                 COALESCE(SUM(CASE WHEN p.direction = 'DEBIT' THEN p.amount ELSE -p.amount END), 0)
               ELSE
                 COALESCE(SUM(CASE WHEN p.direction = 'CREDIT' THEN p.amount ELSE -p.amount END), 0)
             END AS balance
        FROM accounts a
        LEFT JOIN postings p ON p.account_id = a.id
       GROUP BY a.account_type, a.currency
       ORDER BY a.account_type, a.currency
      """;

  // Single-query statement: fetch all postings for entries affecting the account, no N+1.
  private static final String SELECT_STATEMENT_FULL =
      """
      SELECT je.id, je.effective_at, je.description, je.external_reference,
             p.account_id, p.amount, p.direction, p.currency, p.posting_index
        FROM journal_entries je
        JOIN postings p ON p.journal_entry_id = je.id
       WHERE EXISTS (
         SELECT 1 FROM postings p2
          WHERE p2.journal_entry_id = je.id AND p2.account_id = ?
       )
         AND je.effective_at >= ?
         AND je.effective_at <= ?
       ORDER BY je.effective_at, je.id, p.posting_index
      """;

  private final DataSource dataSource;

  public PostgresBalanceRepository(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "Data source cannot be null");
  }

  @Override
  public Money getBalance(UUID accountId) {
    Objects.requireNonNull(accountId, "Account ID cannot be null");

    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(SELECT_BALANCE)) {
      statement.setObject(1, accountId);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) {
          throw new RepositoryException("Account does not exist: " + accountId);
        }

        BigDecimal amount = result.getBigDecimal("balance");
        Currency currency = Currency.getInstance(result.getString("currency"));
        return new Money(amount, currency);
      }
    } catch (SQLException exception) {
      throw new RepositoryException("Could not read balance for account " + accountId, exception);
    }
  }

  @Override
  public java.util.Map<String, Money> trialBalance() {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(SELECT_TRIAL_BALANCE);
        ResultSet result = statement.executeQuery()) {
      java.util.Map<String, Money> balances = new java.util.LinkedHashMap<>();
      while (result.next()) {
        String type = result.getString("account_type");
        Currency currency = Currency.getInstance(result.getString("currency"));
        BigDecimal amount = result.getBigDecimal("balance");
        String key = type + ":" + currency.getCurrencyCode();
        balances.put(key, new Money(amount, currency));
      }
      return java.util.Collections.unmodifiableMap(balances);
    } catch (SQLException exception) {
      throw new RepositoryException("Could not read trial balance", exception);
    }
  }

  @Override
  public List<JournalEntry> statement(
      UUID accountId, java.time.Instant from, java.time.Instant to) {
    Objects.requireNonNull(accountId, "Account ID cannot be null");
    Objects.requireNonNull(from, "From cannot be null");
    Objects.requireNonNull(to, "To cannot be null");
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(SELECT_STATEMENT_FULL)) {
      statement.setObject(1, accountId);
      statement.setObject(2, OffsetDateTime.ofInstant(from, ZoneOffset.UTC));
      statement.setObject(3, OffsetDateTime.ofInstant(to, ZoneOffset.UTC));

      JournalEntryMapper jeMapper = new JournalEntryMapper();
      PostingRowMapper postingMapper = new PostingRowMapper();

      // Group rows by journal entry in memory - single round-trip, no N+1.
      Map<UUID, List<Posting>> postingsByEntry = new LinkedHashMap<>();
      Map<UUID, ResultSet> entryMeta = new LinkedHashMap<>();
      // We need to capture entry fields per id without holding ResultSet; store DTO.
      Map<UUID, EntryHeader> headers = new LinkedHashMap<>();

      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          UUID entryId = rows.getObject("id", UUID.class);
          OffsetDateTime effectiveAt = rows.getObject("effective_at", OffsetDateTime.class);
          String description = rows.getString("description");
          String externalRef = rows.getString("external_reference");

          headers.computeIfAbsent(entryId, k -> new EntryHeader(entryId, effectiveAt, description, externalRef));

          Posting posting = postingMapper.map(rows);
          postingsByEntry.computeIfAbsent(entryId, k -> new ArrayList<>()).add(posting);
        }
      }

      List<JournalEntry> entries = new ArrayList<>();
      for (Map.Entry<UUID, EntryHeader> e : headers.entrySet()) {
        UUID id = e.getKey();
        EntryHeader h = e.getValue();
        List<Posting> postings = List.copyOf(postingsByEntry.getOrDefault(id, List.of()));
        // Reconstruct a synthetic ResultSet row via mapper that expects entry columns; use header directly.
        JournalEntry entry = new JournalEntry(
            h.id(),
            h.effectiveAt().toInstant(),
            h.description(),
            h.externalReference(),
            postings);
        entries.add(entry);
      }
      return List.copyOf(entries);
    } catch (SQLException exception) {
      throw new RepositoryException("Could not read statement for account " + accountId, exception);
    }
  }

  private record EntryHeader(UUID id, OffsetDateTime effectiveAt, String description, String externalReference) {}
}
