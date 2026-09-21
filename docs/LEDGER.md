# How the library represents money

[Documentation home](../README.md#documentation) · [Invariants](INVARIANTS.md) · [Architecture](ARCHITECTURE.md)

An account stores identity, ownership, type, and currency. Its balance is calculated from postings rather than stored as a mutable field.

## Values, lines, and entries

[Money](../src/main/java/com/n2bank/domain/model/Money.java) combines a `BigDecimal` amount and `java.util.Currency`. [Posting](../src/main/java/com/n2bank/domain/model/Posting.java) adds an account ID and direction, requiring a positive amount.

[JournalEntry](../src/main/java/com/n2bank/domain/model/JournalEntry.java) groups postings with an ID, effective time, description, and optional external reference. It copies the list and requires at least two postings, one currency, and equal debit/credit totals.

A valid in-memory entry is not yet persisted. Account existence, account currency, funds checks, and commit are handled during persistence.

```mermaid
classDiagram
    class Money {
        +BigDecimal amount
        +Currency currency
        +isPositive() bool
        +isZero() bool
        +add(Money) Money
        +subtract(Money) Money
    }
    class Posting {
        +UUID accountId
        +Money amount
        +Direction direction
        +amount > 0 enforced
    }
    class JournalEntry {
        +UUID id
        +Instant effectiveAt
        +String description
        +String externalReference
        +List~Posting~ postings
        +List.copyOf immutable
        +>=2 postings one currency debits==credits
    }
    class Account {
        +UUID accountId
        +String name
        +Optional~Customer~ owner
        +AccountType ASSET|LIABILITY|EQUITY|REVENUE|EXPENSE
        +Currency currency
    }
    class Customer {
        +UUID id
        +String name
        +CustomerType PERSON|COMPANY|OTHER
    }
    class IdempotencyKey {
        +String value unique
    }
    class Direction {
        <<enumeration>>
        DEBIT
        CREDIT
    }

    Money --* Posting : amount in
    Posting --* JournalEntry : 1..* postings
    JournalEntry .. IdempotencyKey : append with
    Account o-- Customer : owned or bank-owned
    Posting .. Account : references by id
    Posting .. Direction : has
```

## Deposit, then transfer

Alice deposits EUR 100.00 and transfers EUR 25.00 to Bob, with zero fees:

| Entry | Account | Debit | Credit |
| --- | --- | ---: | ---: |
| Deposit | Bank cash asset | 100.00 | — |
| Deposit | Alice liability | — | 100.00 |
| Transfer | Alice liability | 25.00 | — |
| Transfer | Bob liability | — | 25.00 |

Balances are EUR 100.00 cash, EUR 75.00 owed to Alice, and EUR 25.00 owed to Bob. Each entry balances independently. Asset and liability balances represent opposite sides of the accounting relationship; adding them together does not measure newly created money.

```mermaid
flowchart LR
    subgraph E1["Entry 1: Deposit EUR 100"]
        D1["Bank cash ASSET<br/>DEBIT 100"]
        D2["Alice LIABILITY<br/>CREDIT 100"]
        D1 --- D2
    end

    subgraph E2["Entry 2: Transfer EUR 25"]
        T1["Alice LIABILITY<br/>DEBIT 25"]
        T2["Bob LIABILITY<br/>CREDIT 25"]
        T1 --- T2
    end

    E1 --> B["Balances after both entries<br/>Bank cash ASSET 100<br/>Alice LIABILITY 75 = 100 - 25<br/>Bob LIABILITY 25"]
    B --> N["Each entry balanced independently<br/>Summing ASSET + LIABILITY not meaningful<br/>represents opposite sides"]
```

## Balance signs

| Account type | Normal balance |
| --- | --- |
| ASSET, EXPENSE | Debits minus credits |
| LIABILITY, EQUITY, REVENUE | Credits minus debits |

Customer operations use owned liability accounts: credits increase the amount owed to a customer and debits decrease it.

```mermaid
flowchart TB
    subgraph Left["ASSET, EXPENSE<br/>debit normal"]
        A1["DEBIT increases balance"] --> A2["CREDIT decreases"]
        A2 --> A3["balance = debits - credits"]
    end
    subgraph Right["LIABILITY, EQUITY, REVENUE<br/>credit normal"]
        R1["CREDIT increases balance"] --> R2["DEBIT decreases"]
        R2 --> R3["balance = credits - debits"]
    end
    Q["getBalance sums all postings<br/>no effectiveAt cutoff<br/>future-dated still counts"] -. on .-> Left
    Q -. on .-> Right
    S["statement from,to<br/>filters by effectiveAt"] -. filters vs balance .-> Q
```

Current balance queries include every stored posting, even future-dated ones. Effective timestamps support statement filtering; they do not schedule posting or delay its balance effect.

## Precision and equality

Use explicit decimal strings, such as `new Money("12.50", "EUR")`. Arithmetic preserves decimal precision and rejects cross-currency addition/subtraction. `Money` has no conversion API or universal minor-unit rounding rule.

Record equality inherits `BigDecimal.equals` behavior: scale matters. Money values of `10.0` and `10.00` can compare unequal as records. Journal retry comparison instead uses numeric comparison.

PostgreSQL amounts use `NUMERIC(38,18)`, imposing limits not enforced by the Money constructor. Formatting is locale-sensitive; `toString()` is not a stable serialization format.

## Corrections

`reverse(original, metadata)` appends equal amounts with opposite directions and leaves the original intact. It is an accounting reversal helper, not a refund workflow. It does not verify that the original was persisted or prevent repeated reversals under different keys.

```mermaid
sequenceDiagram
    actor Host as Host backend
    participant Facade as BankApplication
    participant PS as PostingService
    participant PG as PostgresJournalEntryRepository

    Host->>Facade: reverse originalEntry, newMetadata
    Facade->>Facade: map each posting DEBIT<->CREDIT<br/>new id, new effectiveAt
    Facade->>Facade: description = Reversal of original.id
    Facade->>PS: post reversedEntry, newIdempotencyKey
    PS->>PG: append reversedEntry
    PG->>PG: same path as any entry<br/>lock, funds check, INSERT, DEFERRED balance check
    alt insufficient funds after reversal
        PG-->>PS: RepositoryException negative balance
        PS-->>Host: RepositoryException
    else success
        PG-->>PS: committed reversal entry
        PS-->>Host: reversal entry new id
    end
    Note over Host,PG: Original intact<br/>No link constraint<br/>Different keys allow repeated reversals
```
