# Diagrams index

All diagrams are GitHub-native Mermaid (` ```mermaid`). No site plugin needed. Each diagram lives inline in its guide; this index links to the section that contains it.

| Guide | Diagram | What it clarifies |
| --- | --- | --- |
| [Architecture](ARCHITECTURE.md#dependency-direction) | Dependency direction | Domain has no outward deps; ports/adapters wiring via facade |
| [Architecture](ARCHITECTURE.md#posting-flow) | Posting sequence | Ctor validation vs deferred trigger vs cache invalidation outside tx; idempotency path |
| [Architecture](ARCHITECTURE.md#read-flow) | Read flow | `getBalance` cache-first with Postgres fallback; `statement`/`trialBalance` bypass |
| [Integration](INTEGRATION.md) | Lifecycle state diagram | `Uninitialized → Initialized → Ready → Closed` + illegal transitions |
| [Integration](INTEGRATION.md#operation-semantics) | Operation postings | `deposit` `A-F`, `transfer` `A`, `withdraw` `A+F`, fee omission on `isZero` |
| [Integration](INTEGRATION.md#retry-semantics) | Retry at two boundaries | Facade prelim `requireFunds` with stale cache vs repository `compareTo` idempotency |
| [Ledger](LEDGER.md#values-lines-and-entries) | Class diagram | `Money` → `Posting` → `JournalEntry` + `Account`/`Customer` |
| [Ledger](LEDGER.md#deposit-then-transfer) | T-accounts | Deposit 100 → transfer 25 → balances 100 / 75 / 25 |
| [Ledger](LEDGER.md#balance-signs) | Normal balances | `ASSET/EXPENSE` debit-normal vs `LIABILITY/EQUITY/REVENUE` credit-normal; `effectiveAt` nuance |
| [Ledger](LEDGER.md#corrections) | Reversal | Flipped postings appended, original intact, repeatable under new keys |
| [Invariants](INVARIANTS.md#persistence-and-service-rules) | Trust boundaries | Ctor / tx locks / deferred schema / cache vs not prevented |
| [Getting started](GETTING_STARTED.md) | Pipeline | Build → services → env → schema → example |
| [Getting started](GETTING_STARTED.md#troubleshooting) | Troubleshooting decision tree | Symptom → check mapping |
| [Testing](TESTING.md) | Test lanes | Service doubles vs disposable `n2bank_test` integration; coverage and gaps |
| [README](../README.md#the-accounting-core-inside-your-backend) | Deployment | Host backend → library → Postgres + Redis (dashed best-effort) |

> Tip: On GitHub, mermaid renders in file preview and PR diffs. Locally, VS Code needs a Mermaid preview extension; IntelliJ 2024+ renders natively.
