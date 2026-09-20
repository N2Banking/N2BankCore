package com.n2bank.infrastructure.postgres.mapper;

import com.n2bank.domain.model.Account;
import com.n2bank.domain.model.AccountType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Currency;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Maps one account query row, including its optional owner, to the domain model. */
public final class AccountRowMapper implements RowMapper<Account> {
  private final CustomerRowMapper customerMapper;

  public AccountRowMapper() {
    this(new CustomerRowMapper());
  }

  AccountRowMapper(CustomerRowMapper customerMapper) {
    this.customerMapper = Objects.requireNonNull(customerMapper, "Customer mapper cannot be null");
  }

  @Override
  public Account map(ResultSet row) throws SQLException {
    Objects.requireNonNull(row, "Result set cannot be null");
    UUID customerId = row.getObject("customer_id", UUID.class);
    return new Account(
        row.getObject("account_id", UUID.class),
        row.getString("account_name"),
        customerId == null ? Optional.empty() : Optional.of(customerMapper.map(row)),
        AccountType.valueOf(row.getString("account_type")),
        Currency.getInstance(row.getString("currency")));
  }
}
