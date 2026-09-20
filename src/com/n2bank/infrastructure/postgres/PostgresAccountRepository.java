package com.n2bank.infrastructure.postgres;

import com.n2bank.application.port.AccountRepository;
import com.n2bank.application.port.RepositoryException;
import com.n2bank.domain.model.Account;
import com.n2bank.domain.model.Customer;
import java.sql.Connection;
import java.sql.PreparedStatement;
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

  private final DataSource dataSource;

  public PostgresAccountRepository(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "Data source cannot be null");
  }

  @Override
  public Optional<Account> findById(UUID accountId) {
    Objects.requireNonNull(accountId, "Account ID cannot be null");
    throw schemaNotImplemented();
  }

  @Override
  public void create(Account account) {
    Objects.requireNonNull(account, "Account cannot be null");

    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(INSERT_ACCOUNT)) {
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

      int affectedRows = statement.executeUpdate();
      if (affectedRows != 1) {
        throw new RepositoryException(
            "Expected to create one account but PostgreSQL affected " + affectedRows + " rows",
            null);
      }
    } catch (SQLException exception) {
      throw new RepositoryException("Could not create account " + account.accountId(), exception);
    }
  }

  private UnsupportedOperationException schemaNotImplemented() {
    return new UnsupportedOperationException(
        "Account persistence requires the PostgreSQL schema and SQL implementation");
  }
}
