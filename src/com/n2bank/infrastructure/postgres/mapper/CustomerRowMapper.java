package com.n2bank.infrastructure.postgres.mapper;

import com.n2bank.domain.model.Customer;
import com.n2bank.domain.model.CustomerType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

/** Maps one customer query row to the domain model. */
public final class CustomerRowMapper implements RowMapper<Customer> {

  @Override
  public Customer map(ResultSet row) throws SQLException {
    Objects.requireNonNull(row, "Result set cannot be null");
    return new Customer(
        row.getObject("customer_id", java.util.UUID.class),
        row.getString("customer_name"),
        CustomerType.valueOf(row.getString("customer_type")));
  }
}
