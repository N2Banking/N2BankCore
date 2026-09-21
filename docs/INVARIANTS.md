# Invariants and evidence

[Documentation home](../README.md#documentation) · [Accounting model](LEDGER.md) · [Testing](TESTING.md)

These are bounded implementation rules, not a claim of production readiness. “Implemented” and “covered by a dedicated test” are separate statements.

## Domain rules

| Rule | Enforcement | Existing verification |
| --- | --- | --- |
| Monetary values have an amount and currency | [Money](../src/main/java/com/n2bank/domain/model/Money.java) rejects nulls | No dedicated money test class |
| Addition and subtraction require matching currencies | [Money](../src/main/java/com/n2bank/domain/model/Money.java) | No dedicated currency-arithmetic test |
| Posting amounts are greater than zero | [Posting](../src/main/java/com/n2bank/domain/model/Posting.java) | No dedicated posting test class |
| Entries have at least two postings, one currency, and equal debit/credit totals | [JournalEntry](../src/main/java/com/n2bank/domain/model/JournalEntry.java) | Valid entries exercised in repository tests; no dedicated constructor rejection suite |
| A constructed entry's posting list cannot be modified | `List.copyOf` in [JournalEntry](../src/main/java/com/n2bank/domain/model/JournalEntry.java) | No dedicated immutability test |

`Money` permits negative and zero amounts; `Posting` imposes positivity. Decimal storage does not itself impose currency minor-unit rounding. See [precision and equality](LEDGER.md#precision-and-equality).

## Persistence and service rules

The test names below belong to [PostgresJournalEntryRepositoryTest](../src/test/java/com/n2bank/infrastructure/postgres/PostgresJournalEntryRepositoryTest.java), except where a service test is linked.

| Rule | Enforcement | Test or coverage limit |
| --- | --- | --- |
| Referenced accounts exist | Repository lookup and schema foreign keys | `missingAccountsShouldRollback` |
| Posting currencies match account currencies at commit | [Deferred schema trigger](../database/schema.sql) | `currencyMismatchShouldFail` |
| Matching retries reuse one entry | [Repository append](../src/main/java/com/n2bank/infrastructure/postgres/PostgresJournalEntryRepository.java) plus unique key | `identicalRetryReturnsStoredEntry`, `concurrentSameKeyOnlyOneWins` |
| Changed content cannot reuse a key | Repository request comparison | `conflictingRetryShouldFail` |
| One entry ID cannot be reused under a different key | Database primary key and repository error handling | `duplicateEntryIdWithDifferentKeyMustFail` |
| Retry amounts compare numerically across decimal scales | Repository uses `BigDecimal.compareTo` | `scaleNormalizationSameAmountDifferentScaleIsIdempotent` |
| Repository appends reject negative resulting normal balances | Account row locks and balance calculation in the append transaction | `insufficientFundsInsideTransactionPreventsOverdraft`, `concurrentOverdraftOnlyOneSucceeds` |
| Journal/posting updates and deletes are rejected; late posting inserts are rejected | [Schema triggers](../database/schema.sql) | No dedicated mutation/late-insert tests |
| Cache invalidation failure does not fail a committed posting | [PostingService](../src/main/java/com/n2bank/application/service/PostingService.java) | [PostingServiceTest](../src/test/java/com/n2bank/application/service/PostingServiceTest.java) uses test doubles |

## Boundaries that matter

**Append-only is a schema behavior.** Row-level update/delete triggers do not prevent `TRUNCATE`, privileged schema changes, or trigger removal. They do not freeze customer/account records. The test suite itself truncates tables between tests.

**Funds checks belong to the repository path.** The current adapter rejects a negative normal balance for every affected account type. Direct SQL bypasses that application-level funds check. Account lock order is not explicitly sorted and there is no automatic deadlock retry loop.

**Idempotency belongs to append.** Facade validation happens first. A replayed withdrawal or transfer can fail its preliminary balance check even when its original entry already exists. Changed fee configuration can also change the generated entry. See [retry semantics](INTEGRATION.md#retry-semantics).

**Caching allows stale reads.** Invalidation and the 30-second default TTL do not make reads strongly consistent. A concurrent reader can refill an old balance after invalidation; a stale low balance can reject an operation before repository append.

**Coverage is narrower than some test names.** `unbalancedEntryFailsAndRollsBackPostings` actually submits a balanced entry referencing a missing account. It verifies entry absence after failure, not direct SQL rejection of an unbalanced journal or a failure after partial posting insertion.
