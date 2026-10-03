package com.n2bank.testsupport;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Resolves the PostgreSQL used by integration tests.
 *
 * <p>By default a shared {@code postgres:17-alpine} Testcontainers instance is started once per
 * JVM, so a clean clone only needs {@code mvn test} (plus Docker). Setting
 * {@code N2BANK_TEST_URL} (with optional {@code N2BANK_TEST_USER}/{@code N2BANK_TEST_PASSWORD})
 * switches back to an external database, e.g. the compose service.
 */
public final class PostgresContainerSupport {
  private PostgresContainerSupport() {}

  private static final Object LOCK = new Object();
  private static volatile PostgreSQLContainer<?> container;

  /** Connection coordinates for the test database (container or external override). */
  public record PostgresEndpoint(String jdbcUrl, String username, String password) {}

  public static PostgresEndpoint endpoint() {
    String externalUrl = System.getenv("N2BANK_TEST_URL");
    if (externalUrl != null && !externalUrl.isBlank()) {
      return new PostgresEndpoint(
          externalUrl,
          System.getenv().getOrDefault("N2BANK_TEST_USER", "n2bank"),
          System.getenv().getOrDefault("N2BANK_TEST_PASSWORD", "n2bank_local"));
    }
    PostgreSQLContainer<?> started = container;
    if (started == null) {
      synchronized (LOCK) {
        if (container == null) {
          PostgreSQLContainer<?> fresh = new PostgreSQLContainer<>("postgres:17-alpine");
          fresh.start();
          container = fresh;
        }
        started = container;
      }
    }
    return new PostgresEndpoint(started.getJdbcUrl(), started.getUsername(), started.getPassword());
  }

  /** Pool pointed at the endpoint; callers set schema/pool size before use. */
  public static HikariDataSource newDataSource(int maximumPoolSize) {
    PostgresEndpoint endpoint = endpoint();
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(endpoint.jdbcUrl());
    config.setUsername(endpoint.username());
    config.setPassword(endpoint.password());
    config.setMaximumPoolSize(maximumPoolSize);
    return new HikariDataSource(config);
  }

  /**
   * Creates a disposable schema, loads the current {@code database/schema.sql} into it, and
   * returns the schema name. Callers point their pool at it and drop it afterward.
   */
  public static String createIsolatedSchema(DataSource dataSource, String prefix) throws Exception {
    String schema = prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    try (Connection connection = dataSource.getConnection();
        Statement sql = connection.createStatement()) {
      sql.execute("CREATE SCHEMA " + schema);
      connection.setSchema(schema);
      sql.execute(Files.readString(Path.of("database/schema.sql")));
    }
    return schema;
  }

  /** Drops a schema created by {@link #createIsolatedSchema}. */
  public static void dropSchema(DataSource dataSource, String schema) throws Exception {
    if (schema == null) {
      return;
    }
    try (Connection connection = dataSource.getConnection();
        Statement sql = connection.createStatement()) {
      sql.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
  }
}
