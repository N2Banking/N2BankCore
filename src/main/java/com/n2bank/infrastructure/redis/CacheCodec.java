package com.n2bank.infrastructure.redis;

/** Converts a cached value to and from Redis' string representation. */
public interface CacheCodec<T> {
  String encode(T value);

  T decode(String value);
}
