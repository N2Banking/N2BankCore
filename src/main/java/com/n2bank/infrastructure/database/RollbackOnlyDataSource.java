package com.n2bank.infrastructure.database;

import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.Objects;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * Supplies one shared connection whose repository-level commits and closes cannot end the outer
 * test transaction.
 */
public final class RollbackOnlyDataSource implements DataSource {
  private final Connection connection;
  private final Connection repositoryConnection;

  public RollbackOnlyDataSource(Connection connection) throws SQLException {
    this.connection = Objects.requireNonNull(connection, "Connection cannot be null");
    connection.setAutoCommit(false);
    this.repositoryConnection = createRepositoryConnection(connection);
  }

  public void rollback() throws SQLException {
    connection.rollback();
  }

  @Override
  public Connection getConnection() {
    return repositoryConnection;
  }

  @Override
  public Connection getConnection(String username, String password) {
    return repositoryConnection;
  }

  private Connection createRepositoryConnection(Connection delegate) {
    return (Connection)
        Proxy.newProxyInstance(
            Connection.class.getClassLoader(),
            new Class<?>[] {Connection.class},
            (_, method, arguments) -> {
              String methodName = method.getName();
              switch (methodName) {
                case "close", "rollback" -> {
                  return null;
                }
                case "commit" -> {
                  validateDeferredConstraints(delegate);
                  return null;
                }
                case "setAutoCommit" -> {
                  if (Boolean.TRUE.equals(arguments[0])) {
                    throw new SQLException("The test transaction cannot enable auto-commit");
                  }
                  return null;
                }
                case "getAutoCommit" -> {
                  return false;
                }
                case "isClosed" -> {
                  return delegate.isClosed();
                }
                default -> {}
              }

              try {
                return method.invoke(delegate, arguments);
              } catch (InvocationTargetException exception) {
                throw exception.getCause();
              }
            });
  }

  private void validateDeferredConstraints(Connection delegate) throws SQLException {
    try (Statement statement = delegate.createStatement()) {
      statement.execute("SET CONSTRAINTS ALL IMMEDIATE");
      statement.execute("SET CONSTRAINTS ALL DEFERRED");
    }
  }

  @Override
  public PrintWriter getLogWriter() {
    return null;
  }

  @Override
  public void setLogWriter(PrintWriter out) {}

  @Override
  public void setLoginTimeout(int seconds) {}

  @Override
  public int getLoginTimeout() {
    return 0;
  }

  @Override
  public Logger getParentLogger() throws SQLFeatureNotSupportedException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public <T> T unwrap(Class<T> iface) throws SQLException {
    if (iface.isInstance(this)) {
      return iface.cast(this);
    }
    throw new SQLException("Not a wrapper for " + iface.getName());
  }

  @Override
  public boolean isWrapperFor(Class<?> iface) {
    return iface.isInstance(this);
  }
}
