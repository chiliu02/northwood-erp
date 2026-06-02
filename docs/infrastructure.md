# Swapping infrastructure under the hexagonal seam — discussion notes

> Captured 2026-05-22. A series of "can we replace infrastructure component X with only an
> `infrastructure/`-package change?" architecture questions about Northwood, grounded in the
> actual code. A reference artifact kept alongside the tracked `docs/` set.

## The one idea that runs through all of it

> **A port (interface) abstracts API *shape*, not runtime *guarantees*.**
>
> - When a leak is *"the method differs per implementation,"* a port closes it completely.
> - When a leak is *"the application relies on a property the runtime provides"* — atomicity,
>   locking, ordering, exactly-once, replay — **no interface can manufacture that property.** A
>   port can only *name it as a contract and require every adapter to supply it*, which works
>   only if they all can. Otherwise the application must depend on *less*.

Hexagonal isolates the **API surface** of an adapter, not its **semantic model**. Swaps that stay
inside the same semantic model are "infrastructure-only"; swaps that cross it leak into
`application/`/`domain/`, because those layers carry ambient assumptions that no single package owns.

---

## 1. Persistence: PostgreSQL → Cassandra

**Verdict: No — not an infrastructure-only change.** The package boundaries mostly hold (you would
not touch `api/`, DTOs, or most of `domain/`), but Cassandra removes guarantees that several
load-bearing invariants quietly depend on, and those invariants live in `application/` and
`domain/`, not `infrastructure/`. "Replace the JDBC URL" it is not — it's a re-platform, because the
application and domain layers lean on the relational/ACID **guarantee bundle**, not just SQL syntax.

### Before touching code: challenge and scope the mandate

"Replace Postgres with Cassandra" is usually a proxy for a real problem, and the first engineering
move is to name it:

- **"We need to scale"** → the *ready-to-split* invariant + read replicas + an Elasticsearch
  read-side for reporting solve that **without** abandoning ACID where it's
  load-bearing (the ledger). Cassandra is for write-heavy, geo-distributed,
  availability-over-consistency workloads with *known* query patterns — an ERP is almost the
  photographic negative of that profile (relational, integrity-critical money, modest volume, ad-hoc
  reporting).
- **"Corporate standard, no exceptions"** → proceed below, but the most defensible position is still
  **polyglot**: Cassandra for the high-volume snapshot/event data, a small SQL island for the general
  ledger.

Assuming a genuine total mandate, here is what happens.

### What genuinely travels well (ports are clean)

- **`api/` controllers, DTOs** — untouched; they depend only on `application/`.
- **The port interfaces** — `SalesOrderRepository`, `*QueryPort`, `*Lookup`, `OutboxPort`,
  `SagaPort`, `*Projection`. None mention SQL; you write `Cassandra*` adapters satisfying the same
  contracts.
- **Domain aggregates as POJOs** — in-memory invariant checks via `Assert` are storage-agnostic.
- **Aggregate-root loads** — loading `sales_order_header` + its `sales_order_line` children maps
  *naturally* to a single Cassandra partition. Aggregate boundary = partition is a genuinely good
  fit.
- **"No cross-schema FK; cross-context links are plain UUIDs maintained by event projection"** —
  already storage-neutral; ports beautifully.

### What leaks past `infrastructure/` (the consistency model)

1. **The atomic outbox dual-write — the big one.** `JdbcSalesOrderRepository.save()` (`:67-78`)
   writes the header, the lines, *and* drains `pullPendingEvents()` into the outbox — all in one
   JDBC transaction opened by `@Transactional` in `SalesOrderService`. That "state change + its
   event commit together or not at all" is a Postgres transaction. Cassandra has no cross-partition
   ACID. Every fix is a *design* change: co-locate aggregate + outbox in one partition behind a
   single-partition `BATCH` (constrains the data model), switch to Cassandra CDC instead of an
   outbox, or accept dual-write risk. This is `docs/messaging.md`-level, not adapter-level.
   And the polled drain itself — `SELECT … WHERE status='pending' ORDER BY sequence LIMIT 100 FOR
   UPDATE SKIP LOCKED` — is an `ALLOW FILTERING` full-cluster scan in Cassandra (the canonical
   anti-pattern), so `OutboxDrainer` / `OutboxPort` / `JdbcOutboxAdapter` are rewritten regardless
   of which dual-write fix you pick.

2. **Pessimistic claim via `FOR UPDATE SKIP LOCKED`.** Used in `JdbcOutboxAdapter` and all three
   `Jdbc*SagaAdapter`s to let concurrent publishers/workers grab disjoint rows. Cassandra has no row
   locks or `SKIP LOCKED` — move to LWT (Paxos) or a different claim scheme. Worse: the
   `OutboxPort` / `SagaPort` Javadoc *prescribes* "Implementations should use
   `FOR UPDATE SKIP LOCKED`" — the Postgres mechanism has already leaked up into the
   application-layer interface contract. And the "find stuck / timed-out sagas" sweep is itself a
   cross-partition scan with no natural partition key — it needs time-bucketed partitions or an
   external index, not a plain `WHERE saga_state = … AND updated_at < …`.

3. **Optimistic locking via `version` + `WHERE version = ?`.** Aggregates carry a `version`; updates
   compare-and-set on it. Cassandra needs LWT `IF version = ?` (a per-write Paxos round,
   single-partition only) — different semantics and cost.

4. **Running totals kept transactionally consistent.** `stock_balance`, GL-account balance,
   `customer_invoice_header.paid_amount`, plus the `set_updated_at()` / balance triggers
   (`db/northwood_erp.sql`). The "deltas get aggregates, **totals get projections**" rule assumes a
   projection can read-modify-write a running sum *in the same transaction* as ingesting the delta.
   Cassandra has no triggers, and `counter` columns are non-transactional and can't mix with
   conditional logic. The double-entry "total never diverges from the facts" invariant needs a
   different mechanism (recompute-from-log, or eventual reconciliation) — and that logic lives in
   `application/inbox/` projections.

5. **Constraint enforcement that today lives in the schema.** CHECK sets the `dbValue()` enums
   mirror, unique constraints, within-schema line→header FKs, `NOT NULL`. Cassandra enforces almost
   none — validation the DB did silently migrates into `domain`/`application` code. Uniqueness (order
   number, SKU) becomes an LWT `IF NOT EXISTS` write or a lookup-table-as-index, both with race/perf
   caveats; the line→header FK goes unenforced (mitigated by the single-partition aggregate model).

6. **Ad-hoc read queries.** The reporting `*QueryPort` projections do joins, `GROUP BY`, arbitrary
   `WHERE`/`CASE`. Cassandra is query-first: one table per access pattern, no joins. Existing
   interface methods may survive, but every multi-table read must be re-modeled as a denormalized
   read table, and each new query shape forces a new table + projection writer. `reporting-service`
   is the most affected.

7. **Schema-per-service mechanics.** `search_path = <service>, shared`, per-service Postgres roles +
   GRANTs, Liquibase changelogs → Cassandra keyspaces, different access control, CQL migration
   tooling. Mostly config, but the "ready to split" *mechanism* changes wholesale and every
   Liquibase changeset is a rewrite.

### What the system becomes

You don't get "the same ERP on Cassandra" — you get a different architecture:

- **Event-sourcing-leaning** — totals are recomputed and the publish path is outbox-`BATCH` or CDC,
  so the event/delta log becomes the de-facto source of truth rather than the relational tables.
- **Application-enforced integrity** — uniqueness, referential integrity, and balance consistency
  move out of the engine into code, defended by LWT and reconciliation jobs.
- **Query-first read models** — every read is a pre-modeled table; flexibility comes from a separate
  search store (Elasticsearch), not the database.
- **No transactional safety net** — the guarantees that made whole bug classes impossible (a total
  can't drift from its deltas; an event can't be lost relative to its state change) become things you
  defend by design and monitoring.

Several of the project's proudest invariants are casualties: the atomic outbox (gone or
CDC-replaced), the double-entry "totals can't diverge from facts" guarantee (weakened unless you
recompute), and the manual-Liquibase-per-service story (replaced by CQL migration tooling).

### Honest verdict — effort, risk, phasing

- **Effort/risk:** weeks-to-months, not a sprint, and the risk concentrates in **finance** — money
  is exactly where losing ACID hurts most. Do the ledger last and most carefully; it may well justify
  keeping a SQL island even under the mandate.
- **If it must happen, phase it:** pilot one simple service end-to-end first (`product` — mostly
  snapshot data) to prove the patterns (single-partition aggregate, CDC publish, LWT uniqueness,
  recompute-on-read), then the high-volume operational services, then finance — with reconciliation
  jobs and an Elasticsearch read-side standing in for everything Cassandra can't query.

### Principle

Postgres → another **SQL** store (MySQL, CockroachDB, H2-for-tests) is close to "infrastructure
only." Postgres → Cassandra crosses the **consistency-model** boundary — and that boundary isn't
drawn around any package; it's baked into the outbox pattern, the saga workers, the balance
projections, and the constraint-mirroring enums.

---

## 2. Messaging: Kafka → JMS (no XA)

**Verdict: Much cleaner — essentially infrastructure-only, with one conditional leak (ordering).**
This is by design: the team built a broker-neutral seam. `EventPublisher` and `InboxEnvelopeHandler`
are `application/` ports, and `EventEnvelope` is a plain record carrying `aggregateId`, `eventType`,
and the JSON payload — no Kafka types near it. `InboxEnvelopeHandler`'s Javadoc states the intent:
*"Application code does not see Kafka."*

### What stays put

- **`api/`, `domain/`, and almost all of `application/`** — unchanged.
- **The ports**: `EventPublisher.publish(EventEnvelope)`, `InboxEnvelopeHandler`, and the
  `AbstractInboxHandler` boilerplate. Concrete handlers only implement `apply(payload, envelope)` —
  they never touch `ConsumerRecord` or `@KafkaListener`.
- **The inbox/outbox idempotency** (`InboxPort.alreadyProcessed`). Both Kafka and JMS are
  at-least-once, so the "redeliver-on-exception + dedup = exactly-once application" guarantee in
  `AbstractInboxHandler.handle()` travels untouched. This is what makes a broker swap safe at all.

### The pure-infrastructure work

Mirror `shared/.../infrastructure/messaging/kafka/`:

- `JmsEventPublisher implements EventPublisher` — replaces `KafkaEventPublisher` (the *only*
  producer-side Kafka dependency; topic + key are derived from the envelope).
- A `JmsInboxDispatcher` with `@JmsListener` — replaces `KafkaInboxDispatcher`; `Message` →
  `EventEnvelope` → fan out to handlers.
- A `JmsMessagingAutoConfiguration` for the bus/dispatcher. The outbox-drain wiring is already
  shared in `OutboxDrainAutoConfiguration` (parameterized by `northwood.service-name`), so there's
  no per-service config to touch.

### The one real leak: per-aggregate ordering

`KafkaEventPublisher` keys by `aggregateId` so all events for one aggregate land on one partition
**in order**, and a couple of `application/inbox` projections quietly depend on that:

- `manufacturing/.../ProductReplenishmentProjection`: *"partition keys preserve per-product event
  order on the bus, so latest-* wins."*
- `SalesOrderFulfilmentSagaManager` has explicit *"cross-partition-safe gate"* logic.

Plain JMS has **no partitioning**. A single queue + single consumer is FIFO but unscalable; multiple
consumers lose order. The clean fix is broker **message grouping** (`JMSXGroupID = aggregateId` on
ActiveMQ/Artemis), set in the publisher adapter (infrastructure). So:

> **If the JMS broker supports message groups → infrastructure-only, ordering preserved.** If it
> doesn't, the ordering-dependent projections need defensive version-guarding/reordering — and
> *that* code is in `application/inbox/`.

### Operational / topology, not code

- **Fan-out**: map Kafka topics → JMS **topics with durable subscriptions**, not queues. A queue is
  point-to-point — only one service would get each event, silently breaking multi-service
  consumption.
- **Replay gone**: Kafka retains the log (reset offsets, replay); JMS deletes on ack. No code
  change, but any "replay from the bus" runbook capability disappears.
- **DLT → DLQ**: `AbstractInboxHandler`'s Javadoc references `<topic>.dlt`; rewire to a JMS DLQ.
- **Stale Javadoc**: several `application/`-layer comments name Kafka explicitly
  (`PurchaseOrderTrackingProjection`: *"events arrive on independent kafka topics"*;
  `ReorderPolicyChangedHandler`: *"Kafka @KafkaListener adapter once the kafka profile is wired
  in"*). No compile dependency, but the abstraction is **leaky in documentation** — the application
  layer doesn't import Kafka, yet it *knows* it's riding a partitioned log.

### Cassandra vs JMS contrast

| | Postgres → Cassandra | Kafka → JMS |
|---|---|---|
| Ports clean? | Yes (signatures) | Yes (signatures) |
| What leaks | The **consistency model** — atomic outbox write, locking, totals, constraints — touching `application/` + `domain/` design | The **ordering guarantee** — into ~2 `application/inbox` projections, *and only if* the broker can't group by key |
| Realistic blast radius | Redesign messaging, sagas, balances | New adapters in `infrastructure/messaging/jms/`; maybe touch 2 projections |

---

## 3. Does JTA/XA make the outbox table redundant?

Context: XA/2PC can enlist both a JDBC datasource and a JMS connection factory in one global
transaction — the classic alternative to the outbox for the atomic dual-write.

### Short answer

- **For the current stack (Kafka): no — XA isn't even on the table.** Kafka does not implement
  `XAResource`; it can't be enlisted in a JTA transaction. Kafka's own transactions (idempotent
  producer + `transactional.id`) are Kafka-internal and can't be coordinated with a database commit.
  So with Kafka the outbox does something XA *cannot do at all*.
- **For the JMS world: technically yes, XA can subsume the outbox's primary job** (atomic
  state+event) — but it's a redundancy you'd be unwise to cash in.

### What XA would genuinely replace

The outbox solves the dual-write problem: change DB state *and* emit the event atomically. XA solves
the same problem differently — enlist JDBC + a JMS `XAConnectionFactory` in one global transaction,
and a two-phase commit makes both happen or neither. On the atomic-dual-write axis alone, XA-over-JMS
does make the outbox redundant.

### Why it's a bad trade here — what the outbox buys that XA destroys

1. **Temporal decoupling / availability — the big one.** `OutboxDrainer.drain()` runs on a
   `@Scheduled` poll in a transaction *entirely separate* from the business write. The business
   transaction (`JdbcSalesOrderRepository.save()` → insert header + lines + outbox row, one local
   commit) succeeds **regardless of broker health**. If the bus is down, `bus.publish()` throws, the
   row is marked `failed`, and *"failed rows pick up on the next tick if/when the publisher
   recovers"* (`OutboxDrainer` lines 72-79). XA inverts this: both resources must be live at commit
   time, so **broker down = your business write rolls back**. Order placement becomes hostage to
   message-broker uptime.

2. **2PC cost — latency and lock duration.** The business transaction is currently short and local.
   Under XA it must stay open across the prepare phase, holding DB row locks while waiting on broker
   network round-trips. Longer lock hold → more contention → lower throughput under load. The outbox
   commits the DB fast and pushes broker I/O into the async `drain()`.

3. **The in-doubt window doesn't vanish.** 2PC narrows the dual-write gap but converts it into a
   *recovery* problem: a coordinator crash between prepare and commit leaves resources in-doubt,
   requiring a durable transaction log and heuristic resolution. You trade "a row might sit
   unpublished for a second" (benign, auto-retried) for "a global transaction might hang in-doubt and
   need operator intervention."

4. **Spring Boot 4 has no first-party JTA coordinator.** Just as Boot 4.0.5 dropped Liquibase
   auto-config (hence the manual `LiquibaseConfig`), it carries no Atomikos/Bitronix support — both
   were deprecated and removed in the Boot 3.x line. Adopting XA means hand-wiring a third-party
   Narayana starter, XA drivers, and recovery-log config — real infrastructure not currently run.

5. **You lose the replayable event log.** Moving Kafka→JMS already forfeits log replay. The outbox
   *table* compensates — an inspectable, reconcilable record of every emitted event. XA leaves
   nothing behind; the message is gone on delivery.

6. **The "ready to split" invariant breaks under XA, not under the outbox.** The load-bearing rule is
   schema-per-service, ready to become database-per-service with only an `application.yml` change.
   Once services own separate databases, an XA transaction would have to span *multiple service DBs +
   the broker* — even more untenable. The outbox keeps working unchanged across that split, because
   each service's local transaction only ever touches its own DB.

7. **You still can't delete the inbox.** XA fixes only the producer side. Consumers still face
   at-least-once delivery, so `AbstractInboxHandler`'s `inbox.alreadyProcessed(...)` dedup stays
   regardless. XA wouldn't even let you remove all the messaging-reliability machinery — just the
   outbox half.

### The honest framing

XA and the outbox solve the *same* atomicity problem with opposite philosophies:

- **XA** — one synchronous distributed transaction. Strong coupling: every resource must cooperate at
  commit. Simpler to reason about *if* you're already in a JTA container, JMS-only, single DB, modest
  throughput.
- **Outbox** — one local transaction + asynchronous relay with retry. Decoupled, broker-agnostic
  (works for Kafka *and* JMS), survives broker outages, leaves an audit log, scales past a single
  database.

So it's "redundant-with-tradeoffs," not "redundant-as-a-free-win." The outbox is the choice that
survives this project's actual constraints (Kafka today, Boot 4, the future DB split). XA only looks
redundant-making if you assume JMS-forever *and* accept re-adding a distributed-transaction
coordinator — a deliberate step back from where the architecture landed.

---

## 4. Messaging: Kafka → RabbitMQ

**Verdict: Essentially the same as JMS — infrastructure-only in principle**, because the same
broker-neutral seam (`EventPublisher` + `EventEnvelope`) doesn't care whether the other side is a
partitioned log or a queue/exchange broker. RabbitMQ adds its own topology model and one sharp
correctness gotcha.

### What stays put

Identical to JMS — `api/`, `domain/`, and almost all of `application/` untouched. The inbox dedup
makes RabbitMQ's at-least-once redelivery safe exactly as it does Kafka's.

### Topology mapping (RabbitMQ's AMQP model: publish to an **exchange** → **bindings** → **queues** → consumers)

| Kafka concept (today) | RabbitMQ equivalent |
|---|---|
| Topic `<source-service>.events` | An **exchange** per source service (e.g. `product.events`) |
| N consumer groups on one topic | N **durable queues**, each bound to the exchange (one per consuming service) — the clean fan-out analog |
| Partition key = `aggregateId` | AMQP **routing key** — but routing keys route, they don't order (see below) |
| `<topic>.dlt` dead-letter topic | **Dead-letter exchange (DLX)** + dead-letter queue |

The work: `RabbitMqEventPublisher implements EventPublisher`, a `RabbitInboxDispatcher` with
`@RabbitListener` (deserialize `Message` → `EventEnvelope`, fan out — the `KafkaInboxDispatcher`
twin), a `RabbitMessagingAutoConfiguration`, and per-service binding config replacing
`northwood.kafka.subscribe-topics`. All under `infrastructure/messaging/rabbit/`. Spring AMQP
(`spring-boot-starter-amqp`) is mature on Boot 4.

### The same conditional leak: per-aggregate ordering

RabbitMQ orders messages only **within a single queue delivered to a single consumer**; no
partition-by-key. To reproduce Kafka's per-`aggregateId` ordering:

- the **consistent-hash exchange** plugin — hashes the routing key (`aggregateId`) to a fixed queue,
  so all events for one aggregate land on one queue, ordered if that queue has **one consumer**; or
- **RabbitMQ Streams** (3.9+), a Kafka-like append log with offsets — which also restores replay.

Set in the adapter/topology → **infrastructure**. Same caveat as JMS: if you don't reproduce per-key
ordering, the order-dependent projections need defensive version-guarding, and *that* is
application-layer code.

### The RabbitMQ-specific gotcha sharper than JMS: publisher confirms

Outbox correctness depends on `OutboxDrainer.drain()` marking a row `published` **only after**
`bus.publish()` returns successfully (lines 69-71). `KafkaEventPublisher` earns that with a
synchronous `kafkaTemplate.send(...).join()`. RabbitMQ's default publish is **fire-and-forget** —
`rabbitTemplate.convertAndSend(...)` returns before the broker has durably accepted anything. Ported
naively, it reopens the *exact* dual-write gap the outbox exists to close: the row flips to
`published`, the broker silently drops the message, the event is lost.

So `RabbitMqEventPublisher.publish()` must:

- enable **publisher confirms** (`publisher-confirm-type: correlated`) and **block on / verify** the
  confirm before returning;
- send with **persistent** delivery mode to **durable** exchanges/queues so messages survive a broker
  restart.

All in the adapter (infrastructure) — but easy to get subtly wrong, and unlike XA there's no
transaction manager catching it for you.

### Verdict

Infrastructure-only in principle — two adapters + topology config under
`infrastructure/messaging/rabbit/`. Two things to verify before calling it free:

1. **Ordering** — reproduce per-`aggregateId` ordering at the broker (consistent-hash exchange +
   one-consumer-per-queue, or Streams), or accept touching the order-dependent projections.
2. **Publish durability** — wire publisher confirms so the outbox's "mark published only after the
   broker accepts" invariant survives; otherwise you silently un-solve the dual-write problem.

Classic queues lose Kafka's replay (the outbox table remains your event record), and a handful of
`application/`-layer Javadocs still name Kafka and would go stale.

---

## 5. Can a small refactor (one or two interfaces) cover the application leaks?

The leaks split, and the reason they split is the unifying idea at the top: **interfaces abstract API
shape, not runtime guarantees.**

### Publish durability — yes, and it barely needs a refactor

Not actually an application leak. `OutboxDrainer.drain()` already depends only on the existing
`EventPublisher` port: it calls `bus.publish(envelope)` and treats a thrown exception as "mark
failed, retry next tick." The durability requirement is a **contract on a port that already exists**,
not a missing interface. The fix:

1. Tighten the `EventPublisher.publish()` Javadoc contract: *"Returns normally only after the broker
   has durably accepted the message; throws on any failure to do so."*
2. Add one shared abstract conformance test (a TCK) that every adapter extends — Kafka satisfies it
   via `.join()`, RabbitMQ via publisher confirms + persistent/durable, JMS via a
   transacted/synchronous send.

Zero new interfaces, zero application-logic change. The RabbitMQ "leak" was *a way to implement the
adapter wrong*; a contract test pins it.

### Ordering — an interface can't cover it; a small application change can

No method signature differs — the application simply *assumes* events for one `aggregateId` arrive in
emission order, and that assumption is satisfied (or not) by the broker at runtime. Two ways to
resolve it, neither of which is "wrap it in an interface":

**Route A — demand ordering in the port contract.** Declare `InboxEnvelopeHandler`'s contract as
"per-`aggregateId` ordered delivery" and make every adapter honor it (Kafka: free via partition key;
RabbitMQ: consistent-hash exchange + *one consumer per queue*; JMS: message groups). Application
unchanged — but you've pushed a hard constraint onto infrastructure (single consumer per key-bucket
caps parallelism; depends on a broker plugin/feature) and it *breaks the day a target broker can't
honor it*. The obligation moved; it didn't disappear. Brittle for "swap to anything."

**Route B — delete the assumption (recommended).** Make the order-dependent read models
order-*insensitive*, so the application depends on no delivery guarantee and every broker becomes a
truly infrastructure-only swap. The codebase already has the ingredient: `OutboxDrainer` maintains a
monotonic per-service `sequence_number` (its polling cursor — *"never `created_at`"*). It just isn't
propagated — `EventEnvelope` carries `occurredAt` but no sequence. So:

- Surface that `sequence_number` on `EventEnvelope` as an ordering token (more robust than
  timestamps; since the producer owns its aggregate, its own monotonic sequence is a reliable
  "which is newer").
- Change the ~2 "latest-wins" projections (`ProductReplenishmentProjection`, etc.) to apply
  conditionally:

```sql
UPDATE ... SET ... , source_sequence = :seq
WHERE product_id = :id AND source_sequence < :seq   -- ignore stale/out-of-order arrivals
```

That converts "the broker must deliver in order" into "the consumer resolves order locally" —
broker-independent, and more correct anyway (even Kafka reorders across partition rebalances and
producer retries).

This *is* an application-package change — and that's the point, not a failure of abstraction: you're
**removing the application's dependence on a runtime guarantee**, which is inherently application
work. No interface can hide an assumption; someone has to either keep providing it or stop making it.

### Summary

| Leak | Coverable by an interface? | The actual fix | Touches application? |
|---|---|---|---|
| Publish durability | It's already a port concern | Harden `EventPublisher` contract + conformance TCK | No |
| Ordering | No — it's a guarantee, not a shape | Surface the outbox `sequence_number` on the envelope + highest-wins guards on ~2 projections | Yes, by design |

A small refactor genuinely *can* make Kafka / JMS-without-XA / RabbitMQ all infrastructure-only swaps
— but the two pieces live in different places by their nature: the durability gap closes with
*contract* work (no new interface, no app change), and the ordering gap closes with a *small,
one-time application change* that strips out a broker assumption. After both, the broker really is
swappable in `infrastructure/` alone — but you got there by making the application depend on *less*,
not by adding an interface that papers over the difference. Abstract the *shape* with a port; but
*eliminate* (don't wrap) a guarantee you can't universally provide.

---

## 6. Cassandra write-side, in depth — the CDC synthesis and a risk re-ranking

§1's verdict left the atomic-outbox dual-write as "the big one, redesign-level." Pushing on *how*
you'd actually build the write-side surfaces a cleaner construction than the "CDC **or** a sharded
outbox" fork §1 implied — and, more usefully, it re-ranks which leaks actually hurt.

### The synthesis: keep the outbox rows, change how they're relayed

Co-locating event rows in the aggregate's partition gives atomicity (a single-partition `LOGGED
BATCH` is atomic *and* isolated), but it kills the polled drain — finding pending rows across
partitions is the `ALLOW FILTERING` full-cluster scan. The resolution isn't "outbox **or** CDC"; it's
both at once:

- Keep writing the event rows **inside the aggregate's partition**, in the same `BATCH` as header +
  lines → atomicity preserved.
- Mark that table `cdc=true` → the event rows are **relayed by Cassandra CDC**, not polled.

You never run the `WHERE status='pending'` scan — the commitlog is the cursor. The outbox row stops
being a *poll target* and becomes a *CDC source*. That's the idiomatic shape, and it's strictly
better than either branch §1 named.

### What CDC breaks — and why this codebase barely feels it

Cassandra CDC is weaker than a polled outbox in two notorious ways:

1. **At-least-once with duplicates** — CDC is per-node and commitlog-based; with RF=3 the same
   mutation lands in three nodes' CDC logs, so the relay can emit an event up to three times.
2. **No cross-node ordering** — each node's commitlog is locally ordered; a consumer merging three
   logs has no global order.

Both are *already* defended against, because they are also Kafka's properties:

- **Duplicates** → `AbstractInboxHandler.inbox.alreadyProcessed(eventId)`. The three replica copies
  carry the same event UUID (same mutation), so the inbox collapses them. The dedup machinery built
  for Kafka's at-least-once delivery *is* the CDC dedup layer, for free.
- **Reordering** → a highest-sequence-wins guard on the ~2
  order-dependent projections + the Saga's existing cross-partition-safe gate make consumers
  order-insensitive. CDC's missing ordering is the same gap the ordering fix closes for a broker swap.

The outbox's "never lost" guarantee survives directly: the mutation hits the commitlog *before the
write acks*, the commitlog is the durability point, CDC reads from it.

### The residual cost that is genuinely new

- **Operational weight** — a per-node CDC agent + a connector pipeline (DataStax CDC connector, or a
  Debezium commitlog reader) to run, monitor, dedup, and reason about. Materially heavier and less
  mature than Postgres logical replication. The real new burden.
- **Latency** — CDC segments surface on commitlog flush/discard; publish lag is tunable but looser
  than a fast `@Scheduled` drain.
- **Loss of the inspectable outbox *table*** — today `outbox_message` is a queryable, reconcilable
  "what's stuck?" record. CDC logs are transient; that ops affordance must be rebuilt (e.g. a
  retained Kafka topic).

### The reframe worth keeping: the scary leak is the recoverable one

The naive ranking puts the atomic outbox at #1. Worked through, it inverts: **the outbox is the
*most* recoverable leak**, because the inbox dedup and the highest-sequence-wins ordering fix already compensate for exactly what CDC
loses. The leaks with *no* pre-built cushion are where the pain concentrates:

- **Ad-hoc reporting reads** — joins / `GROUP BY` / arbitrary `WHERE` have no Cassandra
  answer but "a table per query shape" or Elasticsearch. Nothing in the codebase
  softens this.
- **Schema-enforced constraints** — uniqueness, FK, CHECK, `NOT NULL` migrate into app
  code with race/perf caveats and no safety net.

So the sharper "what happens": the headline (atomic dual-write) is cushioned by machinery already
present; the quiet leaks (reporting flexibility, integrity enforcement) — plus finance — are where it
actually bites.

---

## 7. Running totals on Cassandra — counters vs LWT vs recompute

§1 leak #4 ("transactional running totals") deserves its own treatment, because it's where the
codebase's defining invariant — *the total can never diverge from the sum of its facts*
(`docs/architecture.md`'s Pacioli framing) — meets Cassandra most directly.

### The core tension: there is no free transactional running total

On Postgres, `stock_balance` / gl-account balance / `customer_invoice_header.paid_amount` stay
correct for free: the read-modify-write of the total runs in the *same transaction* as recording the
delta (or a synchronous trigger fires). Atomicity + isolation ⇒ no lost update, no divergence. On
Cassandra the delta and the total live in *different partitions* (delta in the aggregate's partition;
balance keyed by `product_id` / `gl_account_id`), and there is no cross-partition transaction. You
must pick a mechanism; each fails differently.

### The three options

- **Counter columns — rejected.** Native increment, no read-before-write, but: dedicated table, no
  conditional logic, can't sit in a batch with the inbox dedup marker, and — fatally — **not
  idempotent**. A retried increment double-counts; a crash between the counter bump and the
  "processed" marker double-applies on replay. Exactly the divergence double-entry exists to prevent.
- **LWT materialized balance (single-partition Paxos).** Keep the balance row; update via `UPDATE …
  SET qty=:new, last_seq=:seq WHERE key=:id IF last_seq=:readSeq`. Single-partition ⇒ LWT is legal;
  conditioning on a monotonic `seq` makes it idempotent and order-insensitive, now
  doubling as concurrency control. Correct — but it's optimistic CAS with re-read-on-conflict, and
  **the hottest accounts are single rows**: a GL cash / AR-control / AP-control account hit by every
  payment becomes one hot partition serialising the system through Paxos retry storms. The busiest
  balances are precisely the ones you can't shard without destroying the "one balance" semantics.
- **Recompute — snapshot + tail (the event-sourcing answer).** Don't store a mutable total; append
  the deltas (append-only, zero contention) and define the balance as their sum, with periodic
  snapshots so reads don't scan all history. Preserves the invariant *by construction*. Costs:
  snapshotting machinery, eventual-consistency reads, wide-partition management (time-bucket the key,
  roll old buckets into snapshots), and cross-account aggregation becomes scatter-gather → punted to
  the reporting store.

### Pick per balance, by contention profile

- **`stock_balance` (per SKU)** and **`paid_amount` (per invoice)** — low contention per key → LWT
  highest-wins fits cleanly.
- **GL control accounts (cash, AR, AP)** — single hot rows → LWT melts down; recompute + snapshot is
  the only contention-free answer, but now you're hand-building ledger snapshotting that Postgres
  gives you with a partial index and a `SUM`.

### Punchline

Double-entry and Cassandra collide at the hottest point in the system. The whole reason a ledger
keeps both a balance *and* a journal is to get a fast read *and* a reconcilable audit trail; Postgres
gives both transactionally, Cassandra forces a choice between a fast-but-contended LWT total and a
contention-free-but-slow recomputed one, with no transactional middle. The codebase is already
philosophically fine with recompute ("deltas get aggregates, **totals get projections**" — the total
is derived, not authoritative); Cassandra only changes the *mechanism* from eager-transactional to
eager-LWT-or-lazy-recompute, and forces that choice hardest on the control accounts. Which is the
strongest single argument for the **finance SQL-island**: not nostalgia for SQL, but that the
busiest, most integrity-critical balances are exactly where "no free running total" hurts most.

---

## 8. Is Cassandra the right store for the *events side*? — the event-sourcing reframe

The sharpest version of the question: *forget the wholesale swap — leverage Cassandra only for its
strengths (HA, fault tolerance, geo-distribution, write-heavy, multi-tenant) on the **events side**,
the architecture cornerstone, and serve projections / queries from other stacks.* That reframe
correctly banishes the §1 read-side leaks (balances, constraints, joins) to "other stacks." The
remaining question is whether the events side *itself* is a good Cassandra fit — and the answer turns
on what "the events side" actually is here.

**Verdict: a good event-sourcing store in the abstract; a poor fit for *this* project's events
side.**

### The linchpin: this project is event-*driven*, not event-*sourced*

`docs/architecture.md` is explicit about two things that pull against the reframe:

- The cornerstone is **"Posting — the ink-and-stamp atomicity": the outbox-in-transaction.**
  `aggregate.save()` writes the new **state** *and* the pending events in one `@Transactional`
  boundary — event published AND state changed, or neither.
- Aggregates are **state-stored** ("Spring Data JDBC, not JPA… so aggregate boundaries are
  explicit"), loaded via `reconstitute(...)`, optimistic concurrency on a `version` column. The event
  stream is **not** the system of record — the state row is. Only *projections* are reproducible by
  replaying events; aggregates are never rebuilt from them.

So the events side isn't an event store you read back to reconstitute aggregates; it's an **outbox +
Kafka bus + inbox**, whose load-bearing property is that the event write is **atomically welded to
the state write**. That welding makes the reframe impossible to execute as stated:

- **Events → Cassandra, state + reads → Postgres:** the atomic dual-write now spans two stores, and
  **Cassandra cannot enlist in a JTA/XA transaction** (no `XAResource` — see §3). The ink-and-stamp
  atomicity dies — you'd destroy the cornerstone in the act of trying to leverage it.
- **Events + state → Cassandra (co-located partition, single-partition `BATCH`):** atomicity survives
  — but that's the full re-platform of §1/§6 (event-sourced aggregates, LWT concurrency, CDC relay,
  recompute balances), not a targeted "events side" move.

There is no middle path where Cassandra owns the events and Postgres keeps the aggregates: the
transaction boundary forbids carving them apart. **The events side and the write model are one
transaction by design.**

### Kafka is already the HA, distributed, geo-replicable event log

The properties wanted on the events side — HA, fault tolerance, geo-distribution, write-heavy
throughput — describe **Kafka**, already in the stack: a distributed, partitioned, replicated commit
log (cluster-linking / MirrorMaker for geo, log retained for replay, built for high write rates). So
on the events side Cassandra is **redundant with Kafka** for propagation. The one thing a Cassandra
event store uniquely adds over Kafka — *per-aggregate stream replay* ("all events for aggregate X, in
order") — is exactly what a state-stored project never does. And Cassandra is conversely *bad* at
"read all events across all streams in commit order" (cross-partition `ALLOW FILTERING`), which is
what projections need — so canonical Cassandra event sourcing **pairs Cassandra with Kafka anyway**.
You'd run both: more moving parts, no net simplification, for ERP volumes that tax neither.

### The project already harvests event-sourcing's payoffs without being event-sourced

- **Audit / temporal reconstruction** → `shared.audit_entry` (a meta-journal, full reconstruction).
- **Replayable read models** → the inbox + projection design *is* the reconciliation guarantee ("any
  projection must be reproducible by replaying its source events").
- **Decoupled propagation + traceability** → outbox + event-classes-as-anchor.

…all while keeping the **state-stored relational model and its integrity safety net** (CHECK sets the
enums mirror, FKs, the `finance` ledger that must balance in currency) — precisely what the Pacioli
discipline at the top of `architecture.md` leans on. True event sourcing would trade hard, in-engine
integrity for replay-time enforcement: a downgrade for an integrity-critical ERP, finance worst of
all.

### Multi-tenancy is orthogonal

Multi-tenancy is a data-isolation / deployment concern, not a reason to reach for Cassandra. The
architecture already has a tenancy / scale story in the schema-per-service **"ready to split"**
invariant (+ per-service scaling). Cassandra is a *scale* tool — justified by
thousands of tenants, multi-region active-active, sustained write volume beyond Postgres + Kafka. ERP
event volume doesn't approach that; reach for it when the load is real, not to acquire tenancy you
can get structurally.

### When it would flip to "yes"

If Northwood committed to genuinely event-sourced aggregates *and* faced a load / tenancy profile
that outgrows Postgres + Kafka (massive multi-tenant, multi-region active-active, very high sustained
writes). That's a different product — and even then the `finance` ledger would stay relational.

The instinct — split write-side from read-side, put the write-heavy log on a write-optimised store —
is the right CQRS reflex. It lands on *no* here only because the write-optimised distributed log being
described is the one already running (Kafka), and the events can't leave the aggregate's transaction
without taking the aggregate with them.

---

## Master summary

| Swap | Infrastructure-only? | What leaks, and where |
|---|---|---|
| Postgres → another SQL store | ~Yes | Dialect/migration tweaks only |
| **Postgres → Cassandra** | **No** | Consistency model: atomic outbox write, `SKIP LOCKED` locking, optimistic `version`, transactional running totals, schema-enforced constraints, ad-hoc joins → `application/` + `domain/` redesign |
| **Kafka → JMS (no XA)** | **Mostly** | Per-aggregate ordering, *iff* broker lacks message groups → ~2 `application/inbox` projections |
| **Kafka → RabbitMQ** | **Mostly** | Same ordering leak (reproduce via consistent-hash exchange/Streams) + publisher-confirm durability in the adapter |
| **JTA/XA instead of outbox** | n/a (not a swap) | Impossible for Kafka; for JMS subsumes the atomic write but loses temporal decoupling, replay, the split-ready invariant, and adds a 2PC coordinator |
| **Refactor to close the leaks** | — | Durability = port-contract + TCK (no app change); ordering = ordering token on envelope + highest-wins guards (small, deliberate app change) |
| **Cassandra as the *event store* (events side only, reads elsewhere)** | **No** | The events side is an outbox-in-transaction, not an event store — the event write is atomically welded to the state write. Carving events onto Cassandra either breaks the atomic-posting cornerstone (cross-store, no XA) or forces a full event-sourcing re-platform. The HA/geo/write-heavy log is already Kafka; a state-stored project never needs per-aggregate replay, and Cassandra needs Kafka anyway for global subscription. |

Sections §6–§8 go deeper: §6 the CDC write-side synthesis + a risk re-ranking (the atomic outbox is
the *recoverable* leak; reporting reads + schema constraints are the painful ones), §7 running totals
on Cassandra (counters rejected / LWT hot-account bottleneck / recompute — the finance SQL-island
argument), §8 the event-sourcing reframe above.
