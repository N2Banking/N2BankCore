package com.n2bank.infrastructure.postgres.mapper;

import com.n2bank.domain.model.Customer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

/** Maps one customer query row to the domain model. */
public final class CustomerRowMapper implements RowMapper<Customer> {

  @Override
  public Customer map(ResultSet row) throws SQLException {
    Objects.requireNonNull(row, "Result set cannot be null");
    throw mappingNotImplemented();
  }

  private UnsupportedOperationException mappingNotImplemented() {
    return new UnsupportedOperationException("Customer row mapping is not implemented yet");
  }
}
