# Architecture

[Documentation home](../README.md#documentation) · [Integration](INTEGRATION.md) · [Accounting model](LEDGER.md)

N² Bank Core runs inside its host backend's Java process. The host calls the library; the library talks to PostgreSQL and Redis. It does not run a server or dispatch requests.

## Source map

| Package | Responsibility | Entry point |
| --- | --- | --- |
| `bootstrap` | Compose adapters and services; expose operations and own client lifecycle | [BankApplication](../src/main/java/com/n2bank/bootstrap/BankApplication.java) |
| `domain.model` | Define monetary values, customers, accounts, and valid journal entries | [JournalEntry](../src/main/java/com/n2bank/domain/model/JournalEntry.java) |
| `application.service` | Coordinate accounts, customers, posting, balance reads, and fee calculation | [PostingService](../src/main/java/com/n2bank/application/service/PostingService.java) |
| `application.fee` | Calculate zero, fixed, or percentage fees | [FeePolicy](../src/main/java/com/n2bank/application/fee/FeePolicy.java) |
| `application.port` | Define repository and cache interfaces | [JournalEntryRepository](../src/main/java/com/n2bank/application/port/JournalEntryRepository.java) |
| `infrastructure.postgres` | Implement persistence, locking, and balance queries | [PostgresJournalEntryRepository](../src/main/java/com/n2bank/infrastructure/postgres/PostgresJournalEntryRepository.java) |
| `infrastructure.redis` | Store, retrieve, expire, and invalidate cached balances | [RedisBalanceCache](../src/main/java/com/n2bank/infrastructure/redis/RedisBalanceCache.java) |
| `infrastructure.database` | Configure and own shared database clients | [DBHandler](../src/main/java/com/n2bank/infrastructure/database/DBHandler.java) |

The domain uses Java types rather than JDBC or Redis clients. Application services depend on port interfaces. The facade explicitly wires the concrete PostgreSQL and Redis adapters; it is not a configurable dependency-injection container.

## Posting flow

1. The facade checks operation-specific inputs, selects a fee policy where applicable, and constructs a journal entry. Transfer, withdrawal, and fee-charge calls also perform a preliminary balance check.
2. The journal entry constructor validates positive postings through `Posting`, one currency, and matching debit and credit totals.
3. `PostingService` calls the repository's `append` method.
4. The PostgreSQL adapter handles the idempotency key. A matching retry returns the stored entry. A new entry locks affected accounts, checks resulting balances, inserts postings, and commits.
5. Deferred schema validation checks the complete entry at commit, including posting/account currency agreement.
6. After append returns, the service invalidates each affected cached balance. A cache error does not change the committed result.

This separation matters: cache invalidation is outside the database transaction, and constructor validation alone cannot establish whether referenced accounts exist.

## Read flow

`getBalance` checks Redis first, then calculates a balance through PostgreSQL on a miss or cache error. Successful database reads are cached on a best-effort basis. Statements and trial balances bypass Redis.

Balances sum persisted postings. The account type determines the normal sign; see [Accounting model](LEDGER.md). The balance query has no effective-date cutoff, so even a future-dated stored entry participates in the current balance.

## Host responsibilities

Initialize one facade before accepting requests, register stable system-account IDs for each supported currency, and stop or drain requests before closing it. Lifecycle synchronization does not drain work already in progress.

The host supplies user identity, authorization, request validation, response mapping, durable operation metadata, and deployment configuration. The library's customer records are accounting data, not login identities.

For direct composition, services can be constructed with alternative port implementations. [NoOpBalanceCache](../src/main/java/com/n2bank/infrastructure/redis/NoOpBalanceCache.java) is available for that path. The standard facade always constructs a Redis client.
