package com.n2bank.infrastructure.postgres;

import com.n2bank.application.port.AccountRepository;
import com.n2bank.domain.model.Account;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/** PostgreSQL adapter for {@link AccountRepository}. */
public final class PostgresAccountRepository implements AccountRepository {
  private final DataSource dataSource;

  public PostgresAccountRepository(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "Data source cannot be null");
  }

  @Override
  public Optional<Account> findById(UUID accountId) {
    Objects.requireNonNull(accountId, "Account ID cannot be null");
    throw schemaNotImplemented();
  }

  @Override
  public void create(Account account) {

    Objects.requireNonNull(account, "Account cannot be null");
    throw schemaNotImplemented();
  }

  private UnsupportedOperationException schemaNotImplemented() {
    return new UnsupportedOperationException(
        "Account persistence requires the PostgreSQL schema and SQL implementation");
  }
}
