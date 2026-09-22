# Banking commands and idempotency

Each supported ledger operation has its own immutable command and handler:

| Facade method | Command | Handler |
| --- | --- | --- |
| `deposit` | `DepositCommand` | `DepositHandler` |
| `transfer` | `TransferCommand` | `TransferHandler` |
| `withdraw` | `WithdrawalCommand` | `WithdrawalHandler` |
| `chargeFee` | `ChargeFeeCommand` | `ChargeFeeHandler` |
| `reverse` | `ReversalCommand` | `ReversalHandler` |

Deposit amounts include their fee; transfer amounts are the total sender debit.
Withdrawal amounts exclude their fee. ChargeFeeCommand takes an explicit amount and
description. ReversalCommand takes the original persisted journal entry ID and reverses
all its postings. It rejects a missing original and is subject to sufficient-funds checks.
It does not prevent a second reversal under a different key; idempotency is per key.

Customer creation and account opening retain their existing ID-based create-if-absent
services. Reads, account lifecycle changes, FX, holds, loans, interest accrual, scheduling,
and external payment execution are not additional implemented journal commands.

Use `BankApplication.transfer(TransferCommand)` for persistent transfer replay:

```java
var command = new TransferCommand(
    new IdempotencyKey(requestKey), senderId, recipientId,
    new Money("20.00", Currency.getInstance("EUR")), "invoice-42");
OperationResult result = bank.transfer(command);
// Retry with the same key and inputs; result.journalEntry() is the original entry.
// result.replayed() identifies a replay.
```

Import the command/result from `com.n2bank.application.command` and money/key from
`com.n2bank.domain.model`. The host must authorize every request, including retries.
Use a new key for every newly intended transfer, even when its amount and accounts match.

## Transaction boundary

Each handler prepares its operation's postings. `OperationExecutor` shares post-commit
cache invalidation. `PostgresOperationRepository` owns a single
READ COMMITTED transaction: claim the key, prepare a new entry, append postings, commit.
Account reads and journal writes share that connection. PostgreSQL account locks protect
the funds check; Redis never decides whether a command transfer has sufficient funds.

The primary key on `operations.idempotency_key` arbitrates concurrent claims. A duplicate
insert waits for the competing transaction. After commit, matching type/fingerprint
returns the stored journal entry, without recalculating fees or rechecking funds.
Different input throws `IdempotencyConflictException`. If the first attempt rolls back,
its claim disappears and another attempt can execute. Failed requests are not memorized.

Each fingerprint includes its operation type and all supplied semantic fields: affected
account IDs, amount/currency, fee description or original entry ID, and external reference.
Numeric scale is ignored (`20.0` equals `20.00`); surrounding reference whitespace is
ignored, and absent/empty references are equivalent. Generated timestamps, entry IDs,
and the currently configured fee policy are excluded. A committed replay retains the
original fee and timestamp even after configuration changes.

The deferred foreign key prevents an operation claim from committing without a journal
entry. Both tables are append-only. Keys are global to this database's journal, not scoped
per customer. Reusing a legacy journal key for a command fails; old entries cannot be
automatically assigned a trustworthy command fingerprint.

## Schema installation

Fresh databases: apply `database/schema.sql`. Existing databases without the operations
table: apply `database/migrations/001_operations.sql`, followed by
`database/migrations/002_operation_types.sql`. Databases already using transfer commands
need only migration 002. These are manual scripts;
application startup does not apply migrations. Do not rerun the full schema over live data.

## Scope and remaining work

All five command overloads provide persistent replay. Deprecated overloads using
`OperationMetadata` retain their earlier entry-based retry behavior. Use commands for
new integrations; legacy calls do not acquire command-level replay semantics.

Every journal append locks accounts in UUID natural order before checking balances.
Command transactions retry PostgreSQL deadlocks (`40P01`) up to three total attempts,
with random waits of 25–50 ms and then 50–100 ms. Each failed attempt rolls back and
releases its connection before waiting. Retries reuse the same command, key, and
fingerprint and repeat all transaction reads and preparation. Preparation callbacks
must have no external effects because they can run more than once. Interruption stops
retries and preserves the interrupted status. Business errors and other SQL errors
are not retried. Exhaustion propagates the failure with its underlying SQL exception.
Deprecated overloads do not use this retry loop.

A bounded correctness workload in `BankingLoadTest` submits 1,000 unique transfers
3 times each across 1 and 16 account pairs on 16 workers, asserting one commit per
key, identical replays, exact balances, and ledger counts, plus a 100-way race that
must commit exactly 33 transfers without overspending. It prints a `BANKING_LOAD`
latency report and is repeatable, but it is not a production capacity guarantee.
`BankApplicationLoadTest` separately exercises the public facade with live Redis:
all five commands, replay after reinitialization, and 3,000 concurrent transfer calls
representing 1,000 unique commands. It verifies final balances and statements through
the facade. See [Testing](TESTING.md#public-library-workload-and-customer-journey)
for configuration and measurement limits.
`DeadlockRetryTest` injects wrapped SQL errors during preparation to verify recovery,
rollback of claims, exhaustion, non-retryable errors, and interruption against a
disposable PostgreSQL schema. It does not simulate a real competing-lock deadlock.
A lost commit response can be resolved by resubmitting
the same command and key. This guarantee covers committed ledger writes, not external
payments, messages or emails.

Tests in `PostgresOperationRepositoryTest` use a disposable schema and cover full-balance
replay, changed fee configuration, payload conflicts, rollback, deferred constraints,
and simultaneous same-key requests. Configure the test database with `N2BANK_TEST_URL`,
`N2BANK_TEST_USER`, and `N2BANK_TEST_PASSWORD`; defaults target the local development DB.
