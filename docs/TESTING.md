# Testing

[Documentation home](../README.md#documentation) · [Invariants and evidence](INVARIANTS.md)

The suite consists of service tests using doubles and PostgreSQL tests using an external database.

```mermaid
flowchart TD
    subgraph Service["Service lane — no DB, no Redis"]
        S1["PostingServiceTest<br/>doubles + NoOpBalanceCache"] --> S2["cache failure after append<br/>invalidation on retry"]
        S2 --> S3["mvn -Dtest=PostingServiceTest test"]
    end

    subgraph Integration["Integration lane — disposable DB"]
        I1["docker compose up -d"] --> I2["createdb n2bank_test<br/>disposable only"]
        I2 --> I3["DBConfig.fromEnvironment<br/>Direct JDBC, Testcontainers not auto-started"]
        I3 --> I4["mvn test or -Dtest=PostgresJournalEntryRepositoryTest"]
        I4 --> I5["Setup drops+recreates 4 tables+functions<br/>truncate between tests"]
        I5 --> I6["covers: valid persist, missing account, currency mismatch,<br/>identical retry, conflicting retry, duplicate id,<br/>concurrent same-key, funds checks, scale compareTo, statements"]
        I6 --> I7["gaps: Money arithmetic, ctor rejection,<br/>fee policies, facade lifecycle, live Redis, mutation rejection"]
        I7 --> I8["target/surefire-reports 14 tests total"]
    end
    Service -. separate .-> Integration
```

## Service tests without a database

```sh
mvn -Dtest=PostingServiceTest test
```

[PostingServiceTest](../src/test/java/com/n2bank/application/service/PostingServiceTest.java) checks cache failure after append and invalidation on successful retries. It contacts neither PostgreSQL nor Redis.

## Integration tests

**Setup drops and recreates the four library tables and schema functions; tests truncate the tables between cases.** Use a database containing only disposable test data.

Despite a Testcontainers dependency and stale comments, the current test implementation connects directly via `DBConfig.fromEnvironment()`. It does not start a container automatically.

With compose defaults, create a dedicated database once:

```sh
docker compose up -d
docker compose exec -T postgres createdb -U n2bank n2bank_test
```

If it already exists, confirm that it is disposable before using it. The tests install their own schema.

In a separate PowerShell session:

```powershell
$env:DB_URL = "jdbc:postgresql://localhost:5432/n2bank_test"
$env:DB_USER = "n2bank"
$env:DB_PASSWORD = "<your configured local password>"
mvn test
```

Use the actual configured password, user, and port. On POSIX shells, export the same variables. Java does not load compose's `.env`. Close the dedicated shell afterward to avoid using test settings for application work.

For only the database class:

```sh
mvn -Dtest=PostgresJournalEntryRepositoryTest test
```

## Coverage

[PostgresJournalEntryRepositoryTest](../src/test/java/com/n2bank/infrastructure/postgres/PostgresJournalEntryRepositoryTest.java) covers valid persistence, missing-account/currency rejection, matching/conflicting retries, duplicate IDs, concurrent same-key requests, funds checks, decimal-scale comparison, and complete statements.

There are currently 14 tests in total. Reports appear in `target/surefire-reports/`. A passing run supports those scenarios, not a general claim of concurrency safety.

There are no dedicated test classes for money arithmetic, domain constructor rejection, fee policies, facade operations/lifecycle, live Redis, or direct SQL mutation rejection. The test named `unbalancedEntryFailsAndRollsBackPostings` actually submits a balanced entry with a missing account; it does not test unbalanced SQL or failure after partial posting insertion.

The [evidence table](INVARIANTS.md) separates implementation from tested behavior.
