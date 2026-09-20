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
}
