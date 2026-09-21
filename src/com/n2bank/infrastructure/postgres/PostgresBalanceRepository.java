package com.n2bank.infrastructure.postgres;

import com.n2bank.application.port.BalanceRepository;
import com.n2bank.application.port.RepositoryException;
import com.n2bank.domain.model.Money;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Currency;
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

  private static final String SELECT_STATEMENT =
      """
      SELECT je.id, je.effective_at, je.description, je.external_reference
        FROM journal_entries je
        JOIN postings p ON p.journal_entry_id = je.id
       WHERE p.account_id = ?
         AND je.effective_at >= ?
         AND je.effective_at <= ?
       GROUP BY je.id, je.effective_at
       ORDER BY je.effective_at, je.id
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
  public java.util.List<com.n2bank.domain.model.JournalEntry> statement(
      UUID accountId, java.time.Instant from, java.time.Instant to) {
    Objects.requireNonNull(accountId, "Account ID cannot be null");
    Objects.requireNonNull(from, "From cannot be null");
    Objects.requireNonNull(to, "To cannot be null");
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(SELECT_STATEMENT)) {
      statement.setObject(1, accountId);
      statement.setObject(2, java.time.OffsetDateTime.ofInstant(from, java.time.ZoneOffset.UTC));
      statement.setObject(3, java.time.OffsetDateTime.ofInstant(to, java.time.ZoneOffset.UTC));
      java.util.List<com.n2bank.domain.model.JournalEntry> entries = new java.util.ArrayList<>();
      com.n2bank.infrastructure.postgres.mapper.JournalEntryMapper jeMapper =
          new com.n2bank.infrastructure.postgres.mapper.JournalEntryMapper();
      com.n2bank.infrastructure.postgres.mapper.PostingRowMapper postingMapper =
          new com.n2bank.infrastructure.postgres.mapper.PostingRowMapper();
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          UUID entryId = rows.getObject("id", UUID.class);
          java.util.List<com.n2bank.domain.model.Posting> postings = new java.util.ArrayList<>();
          try (PreparedStatement ps = connection.prepareStatement(
              "SELECT account_id, amount, direction, currency FROM postings WHERE journal_entry_id=? ORDER BY posting_index")) {
            ps.setObject(1, entryId);
            try (ResultSet pr = ps.executeQuery()) {
              while (pr.next()) postings.add(postingMapper.map(pr));
            }
          }
          entries.add(jeMapper.map(rows, java.util.List.copyOf(postings)));
        }
      }
      return java.util.List.copyOf(entries);
    } catch (SQLException exception) {
      throw new RepositoryException("Could not read statement for account " + accountId, exception);
    }
  }
}
