# Architecture — module layout, events jars, DDD service template

Detail companion to `CLAUDE.md`. Read when authoring a new service, a new events module, a new aggregate, or anything cross-cutting.

## Why this codebase looks the way it does — ERP as applied accounting epistemology

Underneath every convention in this repository is one architectural idea applied repeatedly: **Pacioli's 1494 double-entry bookkeeping discipline, generalised to non-monetary facts.** Northwood is an ERP because it applies that discipline uniformly across functional domains, not because it has a `finance` module.

The discipline in one sentence: *the journal is the system of record; the ledger is a report off it. Facts are immutable; totals are derived; if the total can be edited independently of the facts, fraud or error becomes undetectable.* That is the cardinal accounting invariant, and it is the cardinal invariant of this codebase too — see `docs/conventions.md` → *Aggregate vs projection — deltas get aggregates, totals and snapshot projections get projection ports*.

| Pacioli concept | How it appears in Northwood, across every module |
|---|---|
| **Journal entry** (immutable fact) | Every domain event — `GoodsReceiptPosted`, `ShipmentPosted`, `WorkOrderManufacturingCompleted`, `PaymentReceived`, `StockReservationRequested`, `BomActivated`, … — is a "journal entry" for its functional domain. Identity, timestamp, append-only. |
| **Ledger** (running total) | `inventory.stock_balance`, `inventory.wip_balance`, gl-account balance (a `SUM` over `finance.journal_entry_line`), `customer_invoice_header.paid_amount` (trigger-maintained off `payment_allocation`), `reporting.production_planning_board` — all derived sums. None is an aggregate; each has a `*Writer` / `*Projection` writer port and a `*Lookup` / `*QueryPort` reader port. |
| **Chart of accounts** (reference data) | `finance.gl_account`, `inventory.warehouse`, `manufacturing.work_center`, `finance.tax_code`, `shared.uom` — seeded once by SQL, FK-referenced, never mutated through the domain. Java doesn't carry an aggregate class for these. |
| **Posting** (the ink-and-stamp atomicity) | The **outbox-in-transaction**: `aggregate.save()` writes the new state and every pending event inside one `@Transactional` boundary. Either the event is published AND the state changed, or neither did. The bookkeeper's golden rule, expressed in JDBC. |
| **Audit trail** | `shared.audit_entry` is itself a meta-journal of API calls — same shape one layer up. Who did what to which aggregate when, with full reconstruction. |
| **Trial balance / reconciliation** | The deltas/totals invariant in operation: any projection must be reproducible by replaying its source events. If it isn't, you don't trust the books. The inbox + projection-handler design is exactly this reconciliation guarantee. |

Read this way, only the `finance` schema is "the accounting service" because it speaks in money. **Every other service is keeping the books on something else** — inventory on physical units, manufacturing on WIP and labour, sales on customer commitments, purchasing on supplier obligations. At the boundary they post to finance's books (`SalesOrderShippedHandler`, `SupplierInvoiceApprovedHandler`, etc.), which is the one set of books that has to balance in currency. SAP people say *"everything is a posting"* — they aren't being cute, they mean it literally.

**Where the analogy stops.** ERP also has coordination concerns that aren't accounting:

- **Workflow** — sagas (`sales_order_fulfilment`, `work_order`, `purchase_to_pay`) orchestrate multi-step business transactions across humans and services. A saga is a multi-leg journal entry that may take days to balance, with compensation as the reverse-posting mechanism.
- **Role-based UX** — `@PreAuthorize` + per-persona screens (`erp-web-ui`) are human-factors design, not bookkeeping.
- **Forward-looking computation** — ATP checks against `stock_balance`, the planned production-planning-board view, materials-cost rollups. Real ERPs have MRP runs and capacity scheduling here; Northwood deliberately stops short.

So *"ERP = bookkeeping"* under-claims. The honest framing: **ERP is an accounting-shaped system applied to all business facts, not just monetary ones, with workflow and coordination layered on top to drive the humans who post the entries.** The substrate is Pacioli; the modules are domain-specific journals; the saga layer is the multi-step "transaction" mechanism; finance is just the journal denominated in dollars.

**Practical consequence for reading the codebase.** Most of the architectural rules in `CLAUDE.md` and `docs/conventions.md` are easier to remember once you read them as accounting discipline generalised:

- *"Every `*Repository` interface in `domain/` must have a sibling aggregate root that declares `AGGREGATE_TYPE`"* → every aggregate is a class of journal entry; the type code is its account-classification.
- *"Deltas get aggregates, totals get projections"* → the journal-vs-ledger discipline, applied to the schema.
- *"Cross-context relationships are plain UUIDs maintained by event projection"* → schemas reconcile to each other by replaying journal entries, not by sharing a database.
- *"Aggregate-root stamping: an event names the aggregate the fact is about"* → the journal-entry header names which account the entry posts to, independent of which clerk wrote it.
- *"Outbox-in-transaction"* → the bookkeeper's golden rule: the act of posting and the act of changing state are one operation, or neither happens.
- *"No `*Repository` without an aggregate"* → a journal type with no defined accounting treatment is a category error; either define it or use a different filing system.

Internalise this one framing and every other rule in the documentation follows from it rather than being a separate thing to remember. **The deepest "framework" in Northwood isn't Spring; it's Pacioli.**

## Module layout

```
Northwood/
├── pom.xml                      Parent POM; Spring Boot BOM + Testcontainers BOM
├── shared-kernel/               Framework-free shared types — no Spring (Money, Quantity, Sku, DomainEvent, saga/SagaInstance). Service domain aggregates extend types here without crossing into application.
├── shared/                      Outbox/inbox, EventEnvelope, EventPublisher port + KafkaEventPublisher, saga base. Internally split: `com.northwood.shared.application.*` (ports + abstract bases), `com.northwood.shared.infrastructure.*` (JDBC + Kafka adapters + Spring auto-config + Liquibase + security), `com.northwood.shared.api.*` (audit REST controller).
├── product-events/              Wire-format event records emitted by product-service — consumers compile against these
├── product-service/             ★ Reference layout — copy when fleshing out other services
├── sales-events/
├── sales-service/
├── inventory-events/
├── inventory-service/
├── manufacturing-events/
├── manufacturing-service/
├── purchasing-events/
├── purchasing-service/
├── finance-events/
├── finance-service/
├── reporting-service/           Inbox-only — read-side projections (no events jar; reporting emits nothing)
├── erp-web-ui-bff/              BFF for the operational ERP SPA (port 8089)
└── erp-web-ui/                  Operational ERP SPA — business-user personas
```

`shared-kernel` is deliberately framework-free. Do not add Spring deps to it. It hosts the cross-service types that service `domain/` packages need to import — VOs (`Money`, `Quantity`, `Sku`), the `DomainEvent` interface, and the `saga/SagaInstance` abstract base. Anything that needs Spring / JDBC / Kafka lives in the `shared` module instead.

## Tracing data flow: event classes are the navigation anchor

Event-driven systems trade direct call graphs for decoupled producers and consumers — which usually means losing *"where does this go next?"* as a question answerable from source code. Northwood buys that property back by making **event classes the load-bearing artifact for cross-service traceability**: every messaging convention in this codebase exists to keep the data flow answerable through IDE navigation alone.

**Three operational tests** that any messaging-adjacent design decision must preserve:

1. **Find Usages on the event class returns every emitter and every consumer.** No FQN inline references, no Class-by-string lookups, no dynamic dispatch on string keys. Every place an event surfaces — emission, inbox registration, handler payload type, test fixture, exception text, log line — goes through the class or its `EVENT_TYPE` / `AGGREGATE_TYPE` constants.
2. **A consumer's import block is its event-dependency table of contents.** Read the imports of any inbox handler, saga manager, or projection and you have the complete list of events it reacts to. The natural example is `JdbcSalesOrderFulfilmentSagaManager` — its 8 event imports tell the entire story of the saga's inbox surface.
3. **Renaming an event or its wire-format identifier breaks at compile time at every dependent site.** No string drift, no silent rot, no late-binding surprises from a runtime topic mismatch.

**Derived rules**, each documented in its own section / doc:

| Rule | Documented in |
|---|---|
| `<Event>.EVENT_TYPE` constants for every event type string | *Events jars* section below |
| `<AggregateRoot>.AGGREGATE_TYPE` (or `<Event>.AGGREGATE_TYPE` for cross-service / no-aggregate cases) for every outbox `aggregate_type` string | `docs/sagas.md` § *Saga manager class shape* |
| Distinct Java class per wire-format suffix, even when the wire format would collide (see `manufacturing.ReplenishmentUndispatchable` / `purchasing.ReplenishmentUndispatchable`) | Javadoc on each affected event class |
| Each inbox handler passes `<Event>.class` + `<Event>.EVENT_TYPE` to its `AbstractInboxHandler` constructor — registration is a structural Java reference | *Events jars* section below |
| Plain Java imports per type (no wildcards, no FQN inline) so the import block stays a faithful TOC | This section + IDE convention |

**Code-review test for any new messaging code.** Apply the *three operational tests* above. If a proposed change makes any of them weaker (an event named by string somewhere new; an event identity routed through configuration; a consumer that hides its event dependencies behind a registry lookup), push back or rework — the cost is a permanent gap in the cross-service navigation graph.

**Why this matters for a showcase codebase.** Northwood is meant to be *read* as much as run. Event-driven decoupling is the architecturally correct choice at the runtime level, but it makes the codebase hostile to comprehension by default. The conventions above buy back navigability without giving up the decoupling: at runtime, producer and consumer are still independent services; at read time, the event class is a shared anchor that both sides reference. Best of both, paid for in a small amount of mechanical discipline (constants instead of literals, distinct class per wire suffix, explicit imports).

## Events jars: producer publishes the wire schema, consumers compile against it

Each producing service ships a sibling `<service>-events` Maven module containing the wire-format event records that downstream services consume. The producing service depends on its own events module; consumers depend on the events modules of every producer they subscribe to. All six events jars shipped: `product-events` (9 events + `ApprovedVendor` VO), `sales-events` (11), `inventory-events` (5), `manufacturing-events` (11), `purchasing-events` (4), `finance-events` (4). 44 cross-service events total, zero `*Payload` records remaining in the codebase.

**Why a shared jar instead of duplicated `*Payload` records on the consumer side.** The consumer always logically depends on the producer's event schema — that dep exists the moment a handler decides to consume the event. Duplicating the type on the consumer side hides the dep from build tooling without removing it; a shared schema jar makes the dep explicit and gives compile-time safety on additive *and* breaking producer changes (Jackson tolerance handles additive changes either way; breaks become compile errors at the consumer instead of runtime deserialisation failures). Schema-narrowing on the consumer side is a real benefit only when the consumer projects a strict subset; in practice the Northwood payloads were 1:1 mirrors paying duplication cost without the benefit.

**Module contents.** `<service>-events` contains:
- `com.northwood.<service>.domain.events.*` — every record implementing `DomainEvent`.
- Any VO under `com.northwood.<service>.domain.*` that an event transitively references, kept at its natural package (e.g. `com.northwood.product.domain.ApprovedVendor` because `ApprovedVendorListChanged` references it). Don't move VOs into `domain.events.*` to satisfy a strict-package rule — non-event code in the producing service still imports them, and the existing `domain/` package signals "VO of the aggregate, used by events" cleanly.
- Nothing else. No Spring, no JDBC, no aggregates, no application services. The pom depends only on `shared-kernel`.

**Each event record exposes its wire-format type as a `public static final String EVENT_TYPE` constant**; `eventType()` returns the constant. Consumers (handlers, tests, anywhere that compares event-type strings) reference `<EventName>.EVENT_TYPE` rather than re-typing the literal. Pattern:

```java
public record CustomerInvoiceCreated(...) implements DomainEvent {
    public static final String EVENT_TYPE = "finance.CustomerInvoiceCreated";
    @Override public String eventType() { return EVENT_TYPE; }
}
```

Handler:
```java
public class CustomerInvoiceCreatedHandler extends AbstractInboxHandler<CustomerInvoiceCreated> {
    public static final String CONSUMER_NAME = "sales.fulfilment-saga.customer-invoice-created";
    // ...
    super(inbox, json, CustomerInvoiceCreated.class, CustomerInvoiceCreated.EVENT_TYPE, CONSUMER_NAME);
}
```

Handlers keep `CONSUMER_NAME` as their own constant (per-handler, used as the inbox dedupe key). They do **not** redeclare `EVENT_TYPE` — that creates a duplicated literal that drifts on rename. The literal lives on the event record exactly once. Rule-of-thumb test for any new event-type usage: would a refactor IDE rename of the event class find this site? If not (i.e. it's a string literal), replace with `<Event>.EVENT_TYPE` and add the import.

Exception: `shared` module tests (e.g. `OutboxDrainerTest`) deliberately use string literals because that module has no compile dep on the events jars — the tests exercise the publisher mechanism with arbitrary throwaway event-type values, not specific business events. Don't introduce events-jar deps into `shared` to "fix" that.

**Status/state constants follow the same hosting rule as `EVENT_TYPE`.** Aggregate / VO / read-model statuses are wire-format strings stored in the DB and carried on events. Where each constant lives depends on its blast radius:

| Blast radius | Hosting class | Example |
|---|---|---|
| Used only inside the producing service | The aggregate or VO that owns the field | `SalesOrder.SHIPPED`, `Customer.STATUS_ACTIVE`, `PurchaseOrder.RECEIVED`, `SalesOrderLine.OPEN` |
| Carried on an event field that consumers compare against | The event class in the `<service>-events` jar | `CustomerPaymentReceived.INVOICE_STATUS_PAID`, `SupplierPaymentMade.INVOICE_STATUS_PAID`, `StockReserved.STATUS_RESERVED` / `STATUS_PARTIALLY_RESERVED` / `STATUS_FAILED`, `RawMaterialsReserved.STATUS_*` |

**Why event-jar hosting for cross-service references.** A consumer service can only import the producer's events jar — not its `domain/` package (that would invert the inter-service dependency rule and pull Spring + JDBC dependencies along the wire). If the constant lived only on the producer's aggregate (e.g. `inventory.domain.StockReservation.RESERVED`), the cross-service consumer would have to redeclare it locally as a private constant, which silently drifts on rename. Hosting on the event class — the only artifact crossing the service boundary — gives every consumer a single compile-time reference. A producer-side rename of the status literal becomes an events-jar update that breaks consumer builds at compile time, which is exactly the safety we want.

Producer-side: the producing service references the event-jar constant from its aggregate / service when emitting (e.g. `SupplierInvoice` references its own `PAID` for internal status + the event-jar publishes the same wire string). Internal aggregate status and the event wire value sometimes diverge across versions; in that case the aggregate constant and the event constant are two distinct authoritative declarations.

**Code-review checks:**
- A `private static final String SOME_STATUS = "..."` in a consumer service is a smell. If the value is a cross-service wire string, move the constant to the producer's event class.
- An aggregate / service that compares against a status string should use a constant (either its own aggregate-side constant or the event-jar constant for cross-service signals); raw `"open"` / `"active"` / `"approved"` literals in production code are a code-review fail.

**Consumer-side rule for `*Payload` records.**
- **1:1 mirror of the producer event** → delete the payload, import the producer's record, use it in the handler / projection / test directly.
- **Genuinely narrowed** (different field names, narrower types, e.g. consumer wants `Money` instead of `BigDecimal + String`, consumer ignores fields it never reads) → keep the payload, add a Javadoc cross-ref naming the producer event so a reader following the flow doesn't have to re-derive the schema. Today's pilot has no genuinely-narrowed payloads; the original 10 against product events were all 1:1 mirrors.

**Dependency direction.** A consumer service pom adds `<groupId>com.northwood</groupId><artifactId><producer>-events</artifactId>` — no version (managed in the parent BOM). A consumer NEVER depends on the producing `*-service` module. The events jar is the only thing crossing the module boundary; the service jar stays internal to the producing context.

## Aggregate-root stamping: an event names the aggregate the fact is *about*

When an event is appended to the outbox, the `aggregate_type` column (and the matching field on `EventEnvelope`) records the **aggregate the fact is about**, not the module that performed the emission. Logical ownership and physical emission are decoupled — any module that has both the knowledge to assert the fact *and* the contract authority to publish under that aggregate's namespace can emit it. This is orthodox DDD properly read; the classical rule has always been about which aggregate the event identifies, not where the emitting code physically lives.

**Three-criterion test for cross-context emission.** A module-A-emits-event-B about aggregate-C-owned-by-module-D arrangement is legitimate when all three hold:

- **(a) Subject match** — the named aggregate is genuinely what the fact is about, not merely correlated with it.
- **(b) Source authority** — the emitter is the natural source of knowledge for that fact (it has inputs no other module can synthesize from its own state).
- **(c) No invariant claim** — the emitter isn't claiming jurisdiction over the named aggregate's invariants (state-machine, lifecycle, business rules).

**Northwood example that passes.** One event in the system today is cross-context-stamped, deliberately:

- **`ProductMaterialsCostComputed` stamped `Product`** (emitted from `MaterialsCostRollupService` in manufacturing-service). Manufacturing has the rollup inputs (vendor prices + active BOM); the conclusion is a fact about a Product. Passes all three: Product is the subject, manufacturing is uniquely positioned to compute the value, no claim on Product's lifecycle invariants.

(A second example, **`ManufacturingDispatched` stamped `SalesOrder`**, was retired in a later cleanup when sales stopped routing shortages through manufacturing — but it remains a clean illustration of the rule: manufacturing was the only source of the per-line accept/reject decision, the fact was what the SalesOrder needed to advance, and it claimed no ownership of SalesOrder's state machine.)

**Northwood counter-examples that would fail.** A hypothetical "manufacturing emits `ProductDiscontinued`" would violate (b) and (c): manufacturing has no special knowledge of why a product should be discontinued, and discontinuation is a Product lifecycle decision. Same logic applies to "purchasing emits `SupplierBlocked`" — that's a Supplier-aggregate lifecycle event that purchasing observes but doesn't decide. Such cases should emit a module-A-owned event (e.g. `BlockingRecommended`) and let the aggregate's owner consume it and decide whether to flip lifecycle state.

**Happy consequence: Kafka partition co-location.** Because `aggregate_type` + `aggregate_id` together drive the partition key, cross-context stamping naturally co-locates every event for a saga's correlation aggregate onto one partition (`StockReserved` → `ShipmentPosted` → `CustomerInvoiceCreated` → `CustomerPaymentReceived` all keyed by the same `SalesOrder` id). The sales fulfilment saga consumes the lot in order without cross-partition joins. This is a payoff of correct modeling, not the *reason* for the choice — the modeling rule stands on its own. If you ever find yourself reaching for cross-context stamping purely for partition reasons (criteria (a)/(b)/(c) don't all pass), invent a producer-owned aggregate for the event instead and accept the cross-partition cost or design the consumer to tolerate it.

## DDD layering inside a service (the product-service template)

```
<service>/src/main/java/com/northwood/<context>/
├── domain/                          Aggregate roots, VOs, domain events, repository ports
├── application/                     @Service @Transactional use cases — no business logic
├── infrastructure/
│   ├── persistence/                 JdbcXxxRepository — domain aggregate CRUD
│   ├── messaging/                   (outbox drain wired by shared OutboxDrainAutoConfiguration via application-kafka.yml — no per-service config)
│   └── saga/                        Jdbc<Flow>SagaAdapter, <Flow>SagaWorker
└── api/                             @RestController + dto/ records
```

Sub-packages under `infrastructure/` are organised by **functional concern**, not implementation technology — `persistence/` for domain aggregate CRUD, `messaging/` for outbox/inbox, `saga/` for saga state + workers, even though all three end up implemented as JDBC.

Conventions enforced by `product-service`:

- **Aggregates emit domain events into a `pendingEvents` list.** `repository.save()` calls `aggregate.pullPendingEvents()` and writes each one to the outbox table inside the same `@Transactional` boundary opened by the application service. Never publish events outside that transaction.
- **Optimistic concurrency uses a `version BIGINT` column.** Updates are `UPDATE ... WHERE id = ? AND version = ?` and bump version. Zero rows affected ⇒ throw `OptimisticLockingFailureException`.
- **Insert vs update is decided by `version == 0`** (newly minted aggregates have version 0; reconstituted ones have whatever the DB returned).
- **Aggregates have a private constructor + two static factories**: `register(...)` (creation, emits Created event) and `reconstitute(...)` (loading, emits nothing).
- **Mutations are intent-named methods** (`changePricing`, `discontinue`), not setters. The application service is a thin pass-through.
- **Domain events are records implementing `DomainEvent`** from `shared-kernel`. `eventId` is reused as the outbox `outbox_message_id`.

### Every aggregate gets a domain unit test covering its guards

Each aggregate class in `domain/` (Product, SalesOrder, Customer, etc.) has a sibling `<AggregateName>Test.java` under `src/test/java/.../domain/`. The test is plain JUnit + AssertJ — no Spring, no Mockito, no JDBC. Aggregates are constructed directly via their factories / `reconstitute(...)` and asserted on. The test file covers:

- **Null-rejection on every `Objects.requireNonNull(arg, "...")`** in factories and mutators — one `rejects_null_<arg>` test per guarded arg, `assertThatThrownBy(...).isInstanceOf(NullPointerException.class)`. Don't lump multiple args into one `rejects_null_required_fields` test — that hides which arg's guard is actually firing and tempts future maintainers into thinking the others are also tested (the original `SalesOrderTest` had this gap: one bundled test that only exercised `orderNumber`).
- **Blank-rejection on every `arg.isBlank()` check** — `rejects_blank_<arg>` test, `IllegalArgumentException`. Paired with the null check.
- **Status-guard rejections** on every state-machine check (`if (status != ACTIVE)` etc.) — one rejection test per disallowed status (typically using `reconstitute(...)` to mint an aggregate in the disallowed state), asserting `IllegalStateException`.
- **No-op suppression** wherever the aggregate (or its application service) skips emitting an event because the new value matches the old — `no_op_on_unchanged_<field>_emits_nothing` asserts `pullPendingEvents().isEmpty()` after the call. Suppression has real semantics (downstream consumers see fewer no-op events); the test prevents accidental re-emission on refactor.
- **Happy-path event emission** — assert the right event type fires with the right field values pulled from `pullPendingEvents()`.

Why this granularity: aggregate guards are the load-bearing invariants. A missed `requireNonNull` lets a null seep through to the outbox JSON / inbox handler / saga payload and surfaces hours later as an NPE in a downstream service (often via `<topic>.dlt` retry-exhausted). A missed status guard lets a discontinued product accept a price change. A missed no-op suppression duplicates events on the bus. All three classes are mechanically catchable on the aggregate directly and pay back the test cost many times over.

Concrete files to model on: `ProductTest` (8 mutators, factory, null + status patterns), `SalesOrderTest` (place + cancel + line invariants), `CustomerTest` (5 mutators + 3-state machine), `PaymentTest` (single + multi variants × supplier + customer, including per-allocation-line null guards).

When adding a new mutator to an aggregate, the same PR adds its null/blank/status/no-op/event tests — the test file is part of the aggregate's interface, not an optional follow-up.

**Exception: pure read-model classes** with no guards and no mutating behavior (e.g. `purchasing.domain.Supplier` — an immutable holder with only getters and a status-equality helper) don't need a test file. The trigger for a test file is "the class has a guard, a state machine, or a mutation that emits an event," not just "it's in `domain/`."

### `Jdbc*` persistence gets an integration test (`*IT`), never a mocked-`JdbcTemplate` unit test

The domain unit test above stops at the aggregate. The `Jdbc*` classes under `infrastructure/persistence/` and `infrastructure/saga/` — aggregate repositories, saga-state adapters, and the shared `JdbcOutboxAdapter` / `JdbcInboxAdapter` / `JdbcAuditQueryAdapter` — are covered by a **Testcontainers integration test** (`<Name>IT.java`), *not* a unit test with a mocked `JdbcTemplate`. Mocking `JdbcTemplate` only asserts "the repo called `update(...)` with this SQL string" — a change-detector that restates the implementation and catches none of what actually breaks in a class whose entire job is talking to PostgreSQL.

**An IT earns its keep on the branches a mock can't reach:** real SQL + column/type correctness, `search_path` resolution, optimistic-lock `WHERE version = ?` → `OptimisticLockingFailureException`, constraint-violation translation (`UNIQUE` → `Duplicate*Exception`), `?::jsonb` casts, enum `dbValue()` / `fromDb()` round-trips, child-collection delete+reinsert + load ordering, the outbox row drained on `save()`, `FOR UPDATE SKIP LOCKED` claim/lease, deferred DB triggers (e.g. `enforce_journal_balance`), and post-only / write-once guards. **Skip** the low-value cases: a happy path already exercised transitively, or a `*QueryPort` that's a single straight `SELECT` with no joins.

**Naming + lifecycle.** `*IT.java`, run by **Failsafe** in the `integration-test` phase (`mvn verify`) — *not* `*Test` / Surefire, so `mvn test` stays fast and Docker-free. A service module gaining its first IT needs the `maven-failsafe-plugin` execution + `org.testcontainers:postgresql` + `:junit-jupiter` (test scope) in its POM.

**The recipe.** A static `PostgreSQLContainer<>("postgres:17")` + `Startables.deepStart`; apply the `db/northwood_erp.sql` baseline through a raw `DriverManager` `Statement.execute` (Testcontainers' `withInitScript` is broken on 1.20.x — see `~/.claude/notes/testcontainers.md`); a `HikariDataSource` with `connection-init-sql = SET search_path = <service>, shared`; construct the adapter directly (`new Jdbc...(jdbc, new ObjectMapper(), new CurrentUserAccessor())`); `@BeforeEach TRUNCATE <touched tables> CASCADE`. Wrap every `save()` in a `TransactionTemplate` — multi-statement saves (header + lines + outbox) need atomicity, and deferred constraint triggers only fire at COMMIT.

**Gotchas:**
- **Seed NOT NULL FK parents in `@BeforeAll`** (`supplier`, `warehouse`, `unit_of_measure`, `customer`…). Cross-context UUID columns are deliberately *not* FKs (schema-per-service), so they need no seed.
- **Supply an explicit `outbox_message_id`** when hand-INSERTing outbox rows — the `shared.uuid_generate_v7()` column default needs `pgcrypto`, which a bare container doesn't install. Every real writer supplies the id, so that default is effectively dead at runtime.
- **Assert outbox _deltas_, not absolute counts**, when an aggregate's factory may also emit (e.g. `register()` *and* `updatePrice()` both emit) — count before/after the mutation and assert the difference.
- **Mint precise state with `reconstitute(<status>, version=0)`** instead of the public factory when the factory needs collaborators you don't want to build (e.g. PO's `fromRequisition(Supplier, …)`): `reconstitute(DRAFT, 0)` + `approve()` exercises insert + update + lock without the factory's args. `reconstitute` emits no events, so it goes through the insert path cleanly.

**Per-shape variation:**
- **Mutable aggregates** (Product, Customer, SalesOrder, PurchaseOrder, SupplierProductPrice…): insert→find round-trip; a status/data mutator via the update path; stale-version → `OptimisticLockingFailureException`; and (where it exists) duplicate-key → `Duplicate*Exception`.
- **Write-once / post-only aggregates** (Payment, CustomerInvoice, JournalEntry; GoodsReceipt, Shipment): no optimistic-lock test — assert the post-only guard (`save` on `version > 0` → `IllegalStateException`) instead, plus any engine-enforced DB trigger (`JournalEntry`'s deferred balance check rejecting an unbalanced posting at COMMIT and rolling back).
- **Saga adapters**: `claimDue` stamps a lease on active + due rows; a sibling worker's immediate re-claim returns empty (lease not expired); future-`next_retry_at` rows are skipped; `save` enforces the optimistic version. This is the `SKIP LOCKED` twin of the outbox drain.

Files to model on: `JdbcProductRepositoryIT` (mutable repo, child collection), `JdbcJournalEntryRepositoryIT` (write-once + deferred trigger), `JdbcSalesOrderFulfilmentSagaAdapterIT` (saga claim/lease), `JdbcOutboxAdapterIT` (shared adapter + the original `SKIP LOCKED` test). The historical reference is `JdbcWorkOrderRepositoryMaterialStatusIT`.

## Outbox / Inbox shared module

The `shared` module provides reusable pieces. The module is internally split — ports + DTOs + abstract bases under `com.northwood.shared.application.*`; concrete JDBC + Kafka adapters, Spring `@AutoConfiguration` classes, and the `@Scheduled` outbox-drain trigger under `com.northwood.shared.infrastructure.*`; one REST controller under `com.northwood.shared.api.audit`.

- `OutboxRow` / `OutboxPort`, `InboxRow` / `InboxPort` (`shared.application.outbox/inbox`) — row types and read/write ports.
- `JdbcOutboxAdapter` / `JdbcInboxAdapter` (`shared.infrastructure.outbox.jdbc` / `inbox.jdbc`) — single shared implementations. SQL references `outbox_message` / `inbox_message` unqualified; per-service `search_path = <service>, shared` resolves to `<service>.<table>`. Auto-registered via `JdbcOutboxAutoConfiguration` / `JdbcInboxAutoConfiguration`.
- `OutboxDrainer` (`shared.application.outbox`) — drains pending rows and publishes via `EventPublisher`; pure orchestration over ports, no concrete tech. The `@Scheduled(fixedDelayString = "${northwood.outbox.poll-interval:1000}")` trigger lives on `OutboxDrainScheduler` (`shared.infrastructure.messaging`, beside `KafkaInboxDispatcher`), wired by `OutboxDrainAutoConfiguration` when a producer sets `northwood.outbox.drain.enabled=true` in `application-kafka.yml`; services need `@EnableScheduling`. They stay **two beans** — `drain()`'s `@Transactional` (which holds the `FOR UPDATE SKIP LOCKED` batch lock) only fires when the scheduler calls it cross-bean through the proxy, so merging them silently drops the transaction. Polling cursor is `sequence_number`, not `created_at`.
- `EventEnvelope` (`shared.application.messaging`) — wire format; maps 1:1 to outbox columns including `correlation_id` / `causation_id`.
- `EventPublisher` (port, `shared.application.messaging`) + `KafkaEventPublisher` (`shared.infrastructure.messaging.kafka`). Registered by `KafkaMessagingAutoConfiguration` under `@Profile("kafka")`.

## Spring Data JDBC, not JPA

JDBC is chosen so aggregate boundaries are explicit — loading a Product loads only the Product, with no implicit lazy traversals into other contexts. The infrastructure layer manages SQL directly via `JdbcTemplate`, which lines up cleanly with `version` columns for optimistic concurrency.

## Localisation lives in the SPAs, not the backend

The codebase commits to *codes + params at the API boundary, translation SPA-side*. The backend stays locale-free: REST responses are the same byte-for-byte regardless of who's calling. Two consequences worth naming explicitly:

- **No `java.util.ResourceBundle`, no Spring `MessageSource`, no `LocaleResolver` / `Accept-Language` handling anywhere in `shared` or any service.** Error responses are typed `ErrorResponse { code, params }` records; status enums emit their `dbValue()` wire format; logs and `Assert.*` messages stay English (operator-facing, not user-facing).
- **The SPA owns the entire translation surface.** `erp-web-ui` will adopt `react-i18next`-style message bundles keyed by feature namespace. The bundle holds the localised text for each backend `code` plus every JSX string.

Why this split rather than backend-side localisation:

1. **The translatable content lives where the rendering happens.** Almost every user-facing string is JSX text in the SPA. The backend's only user-facing strings are exception messages reaching the wire; once those become codes, the backend ships zero localised content. A `ResourceBundle` per service would duplicate infrastructure the SPA already needs.
2. **Locale-free API responses keep contracts stable.** Two SPAs in different locales get identical JSON. No `Accept-Language` plumbing in controllers, no locale-dependent error message strings appearing in audit logs, no test-fixture drift between locales. The `ErrorResponse.code` is a wire-format constant in the same family as `Currencies.AUD` and `WorkOrderStatuses.RELEASED` — stable across deployments and consumers.
3. **The hexagonal layering stays simple.** No `Locale` parameter threading from `api/` down to `application/` services to look up the right message. The application layer would have no reason to know about locale; pulling that in just for error formatting would be a wrong-kind-of-coupling.

`ResourceBundle` / `MessageSource` would only become the right answer for surfaces the backend renders itself: PDF invoices, email notifications, log streams piped to a localised ops console. The codebase has none of these today; if any are added later, that surface gets its own localisation decision — but the API contract stays codes + params.
