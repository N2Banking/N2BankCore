package com.n2bank.infrastructure.postgres;

import com.n2bank.application.port.CustomerRepository;
import com.n2bank.application.port.RepositoryException;
import com.n2bank.domain.model.Customer;
import com.n2bank.infrastructure.postgres.mapper.CustomerRowMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/** PostgreSQL adapter for {@link CustomerRepository}. */
public final class PostgresCustomerRepository implements CustomerRepository {
  private static final String INSERT_IF_ABSENT =
      """
      INSERT INTO customers (id, name, customer_type)
      VALUES (?, ?, ?)
      ON CONFLICT (id) DO NOTHING
      """;

  private static final String SELECT_BY_ID =
      """
      SELECT id AS customer_id, name AS customer_name, customer_type
      FROM customers
      WHERE id = ?
      """;

  private final DataSource dataSource;
  private final CustomerRowMapper customerMapper = new CustomerRowMapper();

  public PostgresCustomerRepository(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "Data source cannot be null");
  }

  @Override
  public Optional<Customer> findById(UUID customerId) {
    Objects.requireNonNull(customerId, "Customer ID cannot be null");
    try (Connection connection = dataSource.getConnection()) {
      return findById(connection, customerId);
    } catch (SQLException exception) {
      throw new RepositoryException("Could not find customer " + customerId, exception);
    }
  }

  @Override
  public Customer createIfAbsent(Customer customer) {
    Objects.requireNonNull(customer, "Customer cannot be null");
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_IF_ABSENT)) {
          statement.setObject(1, customer.id());
          statement.setString(2, customer.name());
          statement.setString(3, customer.type().name());
          statement.executeUpdate();
        }

        Customer stored =
            findById(connection, customer.id())
                .orElseThrow(
                    () ->
                        new RepositoryException(
                            "Customer " + customer.id() + " was not found after creation"));
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
          "Could not ensure customer " + customer.id() + " exists", exception);
    }
  }

  private Optional<Customer> findById(Connection connection, UUID customerId) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(SELECT_BY_ID)) {
      statement.setObject(1, customerId);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? Optional.of(customerMapper.map(rows)) : Optional.empty();
      }
    }
  }

  private void rollback(Connection connection, Exception original) {
    try {
      connection.rollback();
    } catch (SQLException rollbackFailure) {
      original.addSuppressed(rollbackFailure);
    }
  }
}
