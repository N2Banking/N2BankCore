package com.n2bank.infrastructure.database;

import java.util.Map;
import java.util.Objects;

public record DBConfig(
    String postgresUrl,
    String postgresUser,
    String postgresPassword,
    int postgresMaximumPoolSize,
    String redisUri) {

  public DBConfig {
    requireNonBlank(postgresUrl, "PostgreSQL URL");
    requireNonBlank(postgresUser, "PostgreSQL user");
    Objects.requireNonNull(postgresPassword, "PostgreSQL password cannot be null");
    requireNonBlank(redisUri, "Redis URI");

    if (postgresMaximumPoolSize < 1) {
      throw new IllegalArgumentException("PostgreSQL maximum pool size must be positive");
    }
  }

  public static DBConfig fromEnvironment() {
    return fromEnvironment(System.getenv());
  }

  static DBConfig fromEnvironment(Map<String, String> environment) {
    Objects.requireNonNull(environment, "Environment cannot be null");

    return new DBConfig(
        environment.getOrDefault("DB_URL", "jdbc:postgresql://localhost:5432/n2bank"),
        environment.getOrDefault("DB_USER", "n2bank"),
        environment.getOrDefault("DB_PASSWORD", "n2bank_local"),
        parsePositiveInt(environment.getOrDefault("DB_MAX_POOL_SIZE", "10")),
        environment.getOrDefault("REDIS_URI", "redis://localhost:6379"));
  }

  private static int parsePositiveInt(String value) {
    try {
      int parsed = Integer.parseInt(value);
      if (parsed < 1) {
        throw new IllegalArgumentException("DB_MAX_POOL_SIZE must be positive");
      }
      return parsed;
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException("DB_MAX_POOL_SIZE must be an integer", exception);
    }
  }

  private static void requireNonBlank(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " cannot be blank");
    }
  }
}
