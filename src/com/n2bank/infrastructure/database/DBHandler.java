package com.n2bank.infrastructure.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import javax.sql.DataSource;
import redis.clients.jedis.RedisClient;

/**
 * Owns the application's database clients and their lifecycle.
 *
 * <p>Keep SQL and cache behavior in repositories. This class should only configure, expose, verify,
 * and close shared database resources.
 */
public final class DBHandler implements AutoCloseable {
  private final HikariDataSource postgresDataSource;
  private final RedisClient redisClient;

  public DBHandler(DBConfig config) {
    Objects.requireNonNull(config, "Database configuration cannot be null");

    HikariDataSource createdPostgres = new HikariDataSource(postgresConfig(config));
    RedisClient createdRedisClient = null;

    try {
      createdRedisClient = RedisClient.create(config.redisUri());

      postgresDataSource = createdPostgres;
      redisClient = createdRedisClient;
    } catch (RuntimeException exception) {
      createdPostgres.close();
      throw exception;
    }
  }

  private static HikariConfig postgresConfig(DBConfig config) {
    HikariConfig hikari = new HikariConfig();
    hikari.setJdbcUrl(config.postgresUrl());
    hikari.setUsername(config.postgresUser());
    hikari.setPassword(config.postgresPassword());
    hikari.setMaximumPoolSize(config.postgresMaximumPoolSize());
    hikari.setMinimumIdle(1);
    hikari.setConnectionTimeout(5_000);
    hikari.setValidationTimeout(3_000);
    hikari.setPoolName("N2Bank-Postgres");
    return hikari;
  }

  public DataSource postgres() {
    return postgresDataSource;
  }

  public Connection postgresConnection() throws SQLException {
    return postgresDataSource.getConnection();
  }

  public RedisClient redis() {
    return redisClient;
  }

  public void verifyConnections() throws SQLException {
    try (Connection connection = postgresConnection()) {
      if (!connection.isValid(3)) {
        throw new SQLException("PostgreSQL connection validation failed");
      }
    }

    String response = redisClient.ping();
    if (!"PONG".equals(response)) {
      throw new IllegalStateException("Redis connection validation failed: " + response);
    }
  }

  @Override
  public void close() {
    try {
      redisClient.close();
    } finally {
      postgresDataSource.close();
    }
  }
}
