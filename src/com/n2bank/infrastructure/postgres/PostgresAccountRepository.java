package com.n2bank.infrastructure.postgres;

import com.n2bank.application.port.AccountRepository;
import com.n2bank.application.port.RepositoryException;
import com.n2bank.domain.model.Account;
import com.n2bank.domain.model.Customer;
import com.n2bank.infrastructure.postgres.mapper.AccountRowMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/** PostgreSQL adapter for {@link AccountRepository}. */
public final class PostgresAccountRepository implements AccountRepository {
  private static final String INSERT_ACCOUNT =
      """
      INSERT INTO accounts (id, name, customer_id, account_type, currency)
      VALUES (?, ?, ?, ?, ?)
      """;
  private static final String INSERT_ACCOUNT_IF_ABSENT =
      """
      INSERT INTO accounts (id, name, customer_id, account_type, currency)
      VALUES (?, ?, ?, ?, ?)
      ON CONFLICT (id) DO NOTHING
      """;
  private static final String SELECT_ACCOUNT_BY_ID =
      """
      SELECT
          a.id AS account_id,
          a.name AS account_name,
          a.account_type,
          a.currency,
          c.id AS customer_id,
          c.name AS customer_name,
          c.customer_type
      FROM accounts a
      LEFT JOIN customers c ON c.id = a.customer_id
      WHERE a.id = ?
      """;

  private final DataSource dataSource;
  private final AccountRowMapper accountMapper;

  public PostgresAccountRepository(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "Data source cannot be null");
    this.accountMapper = new AccountRowMapper();
  }

  @Override
  public Optional<Account> findById(UUID accountId) {
    Objects.requireNonNull(accountId, "Account ID cannot be null");

    try (Connection connection = dataSource.getConnection()) {
      return findById(connection, accountId);
    } catch (SQLException exception) {
      throw new RepositoryException("Could not find account " + accountId, exception);
    }
  }

  @Override
  public void create(Account account) {
    Objects.requireNonNull(account, "Account cannot be null");

    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(INSERT_ACCOUNT)) {
      bindAccount(statement, account);
      int affectedRows = statement.executeUpdate();
      if (affectedRows != 1) {
        throw new RepositoryException(
            "Expected to create one account but PostgreSQL affected " + affectedRows + " rows");
      }
    } catch (SQLException exception) {
      throw new RepositoryException("Could not create account " + account.accountId(), exception);
    }
  }

  @Override
  public Account createIfAbsent(Account account) {
    Objects.requireNonNull(account, "Account cannot be null");

    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        try (PreparedStatement statement =
            connection.prepareStatement(INSERT_ACCOUNT_IF_ABSENT)) {
          bindAccount(statement, account);
          statement.executeUpdate();
        }

        Account stored =
            findById(connection, account.accountId())
                .orElseThrow(
                    () ->
                        new RepositoryException(
                            "Account " + account.accountId() + " was not found after creation"));
        connection.commit();
        return stored;
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (RepositoryException exception) {
      throw exception;
    } catch (SQLException exception) {
      throw new RepositoryException(
          "Could not ensure account " + account.accountId() + " exists", exception);
    }
  }

  private Optional<Account> findById(Connection connection, UUID accountId) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(SELECT_ACCOUNT_BY_ID)) {
      statement.setObject(1, accountId);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? Optional.of(accountMapper.map(rows)) : Optional.empty();
      }
    }
  }

  private void bindAccount(PreparedStatement statement, Account account) throws SQLException {
    statement.setObject(1, account.accountId());
    statement.setString(2, account.name());

    UUID customerId = account.owner().map(Customer::id).orElse(null);
    if (customerId == null) {
      statement.setNull(3, Types.OTHER);
    } else {
      statement.setObject(3, customerId);
    }

    statement.setString(4, account.type().name());
    statement.setString(5, account.currency().getCurrencyCode());
  }

  private void rollback(Connection connection, Exception original) {
    try {
      connection.rollback();
    } catch (SQLException rollbackFailure) {
      original.addSuppressed(rollbackFailure);
    }
  }

}
