package com.n2bank.infrastructure.postgres.mapper;

import com.n2bank.domain.model.Direction;
import com.n2bank.domain.model.Money;
import com.n2bank.domain.model.Posting;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Currency;
import java.util.Objects;

/** Maps one posting query row to the domain model. */
public final class PostingRowMapper implements RowMapper<Posting> {

  @Override
  public Posting map(ResultSet row) throws SQLException {
    Objects.requireNonNull(row, "Result set cannot be null");
    return new Posting(
        row.getObject("account_id", java.util.UUID.class),
        new Money(
            row.getBigDecimal("amount"), Currency.getInstance(row.getString("currency"))),
        Direction.valueOf(row.getString("direction")));
  }
}
