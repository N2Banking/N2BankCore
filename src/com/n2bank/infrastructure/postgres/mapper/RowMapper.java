package com.n2bank.infrastructure.postgres.mapper;

import java.sql.ResultSet;
import java.sql.SQLException;

/** Converts the current row of a JDBC result set into one object. */
@FunctionalInterface
public interface RowMapper<T> {

  /**
   * Maps the row at the result set's current cursor position.
   *
   * <p>The caller owns the result set and advances its cursor. Implementations must not call
   * {@link ResultSet#next()} or close the result set.
   */
  T map(ResultSet row) throws SQLException;
}
