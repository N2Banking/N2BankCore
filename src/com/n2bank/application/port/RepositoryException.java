package com.n2bank.application.port;

/** Indicates that a repository operation could not be completed. */
public final class RepositoryException extends RuntimeException {

  public RepositoryException(String message) {
    super(message);
  }

  public RepositoryException(String message, Throwable cause) {
    super(message, cause);
  }
}
