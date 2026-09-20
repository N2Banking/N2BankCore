package com.n2bank.infrastructure.postgres.mapper;

import com.n2bank.domain.model.Account;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

/** Maps one account query row, including its optional owner, to the domain model. */
public final class AccountRowMapper implements RowMapper<Account> {

  @Override
  public Account map(ResultSet row) throws SQLException {
    Objects.requireNonNull(row, "Result set cannot be null");
    throw mappingNotImplemented();
  }

  private UnsupportedOperationException mappingNotImplemented() {
    return new UnsupportedOperationException("Account row mapping is not implemented yet");
  }
}
