package com.n2bank.testsupport;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Resolves the Redis used by integration tests.
 *
 * <p>By default a shared {@code redis:8-alpine} Testcontainers instance is started once per JVM.
 * Setting {@code N2BANK_TEST_REDIS_URI} switches back to an external Redis (default compose
 * address when unset downstream is {@code redis://localhost:6379}).
 */
public final class RedisContainerSupport {
  private RedisContainerSupport() {}

  private static final Object LOCK = new Object();
  private static volatile GenericContainer<?> container;

  public static String redisUri() {
    String external = System.getenv("N2BANK_TEST_REDIS_URI");
    if (external != null && !external.isBlank()) {
      return external;
    }
    GenericContainer<?> started = container;
    if (started == null) {
      synchronized (LOCK) {
        if (container == null) {
          GenericContainer<?> fresh =
              new GenericContainer<>(DockerImageName.parse("redis:8-alpine"))
                  .withExposedPorts(6379);
          fresh.start();
          container = fresh;
        }
        started = container;
      }
    }
    return "redis://" + started.getHost() + ":" + started.getMappedPort(6379);
  }
}
