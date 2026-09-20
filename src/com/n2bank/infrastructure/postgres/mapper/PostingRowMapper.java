package com.n2bank.infrastructure.postgres.mapper;

import com.n2bank.domain.model.Posting;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

/** Maps one posting query row to the domain model. */
public final class PostingRowMapper implements RowMapper<Posting> {

  @Override
  public Posting map(ResultSet row) throws SQLException {
    Objects.requireNonNull(row, "Result set cannot be null");
    throw mappingNotImplemented();
  }

  private UnsupportedOperationException mappingNotImplemented() {
    return new UnsupportedOperationException("Posting row mapping is not implemented yet");
  }
}
