# Messaging design

How events flow from producer outbox to consumer inbox over Kafka — partition key choice, ordering guarantees, hazards, and the audit items to clear before scaling partition counts past 1.

The current showcase runs on 1-partition topics (see `docker-compose.yml:55` — `KAFKA_AUTO_CREATE_TOPICS_ENABLE=true` with Kafka's default `num.partitions=1`). Multi-partition is planned in `dev-todo.md` §2.14. This doc captures the design choices that hold whether partitions = 1 or partitions = N, and the audit items that only matter at N > 1.

## Status today

- **Topic shape**: one event topic per producer service — `product.events`, `inventory.events`, `sales.events`, `manufacturing.events`, `purchasing.events`, `finance.events`. Decision recorded in `KafkaEventPublisher.java:24` ("never per-event-type"). Per-event-type topics were rejected because Find-Usages-on-the-event-class becomes the cross-service traceability anchor (see `MEMORY.md` — event classes are the load-bearing artifact), and per-service topics keep that 1:1 with the source aggregate's outbox.
- **Partition count**: 1 per topic (auto-created on first publish, default `num.partitions`).
- **Wire format**: JSON via Jackson 3. No schema registry by design.
- **Replication factor**: 1 across the board (single-broker showcase — see the `KAFKA_*_REPLICATION_FACTOR=1` overrides in `docker-compose.yml:62-67`).
- **Auto-create**: enabled today (`KAFKA_AUTO_CREATE_TOPICS_ENABLE=true`). §2.14 plans to flip this off and pre-declare via `KafkaAdmin` + `NewTopic` beans.

## Critical: partition key choice

**The partition key is `envelope.aggregateId().toString()`** — set in `KafkaEventPublisher.java:49`. This is the single load-bearing design choice that determines what ordering guarantees consumers can rely on.

### What this choice guarantees

Kafka guarantees: messages with the same key always land on the same partition, and partition logs are strictly ordered. So with `key = aggregateId`:

- **Per-aggregate event order is preserved.** All events for `Product(id=X)` — `ProductCreated`, `StandardCostChanged`, `ValuationClassChanged`, `ProductDiscontinued` — land on the same partition and are consumed in the order the producer wrote them. The four `finance.product_card` projection handlers can rely on `ProductCreated` arriving before `StandardCostChanged` (so the seed-row exists when the cost-update UPDATE runs).
- **Per-aggregate partition stickiness through schema partition changes.** Adding partitions to an existing topic re-hashes new keys; existing keys keep their partition unless the partitioner uses range-style hashing (Kafka's default `DefaultPartitioner` uses murmur2 hash mod num-partitions, which DOES re-shard). So increasing partition count on a live topic shuffles future events for an aggregate to a possibly-different partition than its past events. **Caveat**: events already in flight before the partition bump stay where they were; the consumer must drain those before new partitions get traffic. Not an issue at deploy time if done before any events flow into new partitions.

### What this choice does NOT guarantee

- **Cross-aggregate ordering is not preserved**, even within the same topic. `PurchaseRequisitionCreated` (key=PR) and `PurchaseOrderCreated` (key=PO, references the PR) can land on different partitions and be consumed concurrently by different threads. A consumer reading the PO-partition may process `PurchaseOrderCreated` before its own group has even fetched `PurchaseRequisitionCreated` from the PR-partition.
- **Cross-topic ordering is never preserved**, regardless of key. Events in `sales.events` and `inventory.events` are independent partition logs; the relative order they reach a multi-topic consumer (sagas, BFF aggregators) is whatever scheduling gives.

### Why aggregateId not eventId or null

- **`eventId`**: every event would hash uniquely → maximum spread, zero per-aggregate ordering. Inbox dedupe still works (dedupe key is `(consumer, event_id)`) but projection writes lose causal order. Rejected.
- **`null` (Kafka's sticky partitioner)**: batches publishes onto rotating partitions for throughput. No per-aggregate ordering. Rejected for the same reason.
- **`sourceService`**: every event from one service lands on one partition → 1 partition's worth of throughput regardless of total partition count. Defeats the purpose of partitioning. Rejected.

The `aggregateId` choice trades cross-aggregate ordering (which design has to compensate for elsewhere) for per-aggregate ordering (which is the bedrock most projections + sagas rely on).

### Open question for cross-saga keying

A saga isn't an aggregate — it's a cross-aggregate coordinator. Events the saga consumes carry different aggregate IDs (a sales-fulfilment saga consumes `SalesOrderPlaced` keyed by SO-id, `StockReserved` keyed by Reservation-id, `WorkOrderManufacturingCompleted` keyed by WO-id, `ShipmentPosted` keyed by Shipment-id). With multi-partition topics, those events arrive on different partition-consumer threads and try to advance the same saga concurrently.

**Today's mitigation is correct** but worth recording:

1. `SagaPort.claimDue` uses `SELECT ... FOR UPDATE SKIP LOCKED` (`SagaPort.java:14`) — only one worker claims a given saga row at a time, and SKIP_LOCKED means sibling workers move on rather than blocking.
2. Optimistic concurrency on `update()` (`SagaPort.java`) — `UPDATE ... WHERE saga_id = ? AND version = ?` with `version = version + 1`; throw on zero rows affected. A second concurrent transition triggers `OptimisticLockingFailureException`, the saga manager catches it and reschedules via `scheduleRetry` (`SagaManager.java:127-137`).

What an inbox handler does when it can't find the saga yet (because `SalesOrderPlaced` from one partition hasn't been processed before `StockReserved` from another partition arrives): **park and retry**. The §2.6 dev-todo entry references a 2026-05-05 cross-partition race fix that landed this pattern. Multi-partition makes the parking path the common case rather than the exception — re-test the parking + replay timing thoroughly before bumping partition counts. See §2.14 audit items.

## Reliability & idempotency: guarantees and where they're tested

Reliable delivery + idempotent consumption are the cornerstone of this architecture, so this matrix is the index for the detailed sections that follow: every guarantee, the mechanism that provides it, and the test that covers it. The **doc-only** rows are inherently not unit-testable (you can't deterministically crash a process mid-commit) — each is absorbed by a mechanism that *is* tested, noted in the row.

### Producer — reliable emission (the outbox)

| Guarantee | Mechanism | Covered by |
|---|---|---|
| Event row written atomically with the aggregate change (no event without the state change, and vice-versa) | one local `@Transactional`; `repository.save()` drains `pendingEvents` → `OutboxPort.appendPending` in the same tx | aggregate `Jdbc*RepositoryIT`s (the outbox-row-delta assertions on save) |
| Published in `sequence_number` order; row marked `published` only after broker ack | `OutboxDrainer.drain()` `.join()`s each send before `OutboxPort.update(…published)` | `OutboxDrainerTest.drain_publishes_each_row_marks_published_and_saves`; `JdbcOutboxAdapterIT.findPending_returns_rows_in_sequence_number_order…` + `…update_marks_published…` |
| Broker failure → row left `failed` → retried next tick | per-row try/catch marks `failed`, continues the batch | `OutboxDrainerTest.drain_partial_failure_marks_failed_row_and_continues` |
| Concurrent drainers never double-claim a row | `findPending` `FOR UPDATE SKIP LOCKED` held inside the drain `@Transactional` | `JdbcOutboxAdapterIT.findPending_skips_rows_locked_by_another_transaction` |
| Crash after broker ack, before the `published` mark → row re-published next tick (duplicate on the topic) | at-least-once publish; duplicate carries the same `eventId` → collapsed by the consumer inbox | **doc-only** (process crash); the absorbing dedup is covered by `JdbcInboxAdapterIT` + the duplicate-delivery e2e (§2.27) |

### Consumer — idempotent consumption (the inbox; exactly-once effect)

| Guarantee | Mechanism | Covered by |
|---|---|---|
| Dedup keyed `(message_id, consumer_name)`, independent per consumer | inbox row + `alreadyProcessed` check | `JdbcInboxAdapterIT.recordProcessed_then_alreadyProcessed_is_true`, `…dedup_is_keyed_per_consumer` |
| Concurrent duplicate of the same message serialized (TOCTOU race closed) | advisory-lock gate (default), held across `handle()`'s tx | `JdbcInboxAdapterIT.advisory_lock_serializes_a_concurrent_duplicate` |
| `apply` + `recordProcessed` atomic; `apply` throws → both roll back → reprocessable | `handle()` `@Transactional` boundary | **planned** rollback-atomicity e2e (§2.27) |
| Offset committed only after the listener returns successfully | container-managed commit, default `BATCH` ack mode | `KafkaInboxDispatcherDeliveryIT.offset_commits_only_after_the_listener_returns_successfully` |
| Handler exception → offset not committed → record redelivered | error-handler re-seek (no commit) | same test (the failure half) |
| Duplicate delivery (e.g. producer re-publish) applied exactly once | redelivery hits `alreadyProcessed=true` → skip | **planned** duplicate-delivery e2e (§2.27) |
| Malformed envelope → skipped + offset committed (the one failure that does NOT redeliver — poison-pill avoidance) | dispatcher catches `JacksonException`, returns normally | `KafkaInboxDispatcherDeliveryIT.malformed_envelope_is_skipped_and_offset_still_commits` |
| Persistent handler failure → DLT after the retry budget, then offset commits (no infinite loop) | `DefaultErrorHandler(FixedBackOff(0,3))` + `DeadLetterPublishingRecoverer` | `KafkaInboxDispatcherDeliveryIT.persistent_failure_is_dead_lettered_then_offset_commits` |
| Crash between the DB commit and the offset commit → redelivery → dedup skips | inbox dedup absorbs the redelivery | **doc-only** (process crash); absorbed by the dedup covered above |

### Saga — cross-aggregate coordination

| Guarantee | Mechanism | Covered by |
|---|---|---|
| At most one worker advances a saga at a time | `claimDue` `FOR UPDATE SKIP LOCKED` + lease | `Jdbc{SalesOrderFulfilment,MakeToOrder,PurchaseToPay}SagaAdapterIT.claimDue_leases_active_due_rows_and_blocks_immediate_reclaim` |
| Concurrent transition → optimistic-lock failure → manager retries | `update … WHERE saga_id = ? AND version = ?` | the same ITs' `update_enforces_optimistic_lock_via_version` |
| Backed-off saga not re-claimed before `next_retry_at` | due-time filter in `claimDue` | the same ITs' `claimDue_skips_rows_with_future_next_retry_at` |
| Out-of-order prerequisite (cross-partition) → park + retry | handler parks when the saga row is absent | §2.6 sales cross-partition regression (un-exercisable until partitions > 1) |

> **Coverage status (2026-05-27):** the rows pointing at `KafkaInboxDispatcherDeliveryIT` are now **verified** (3/3 against Testcontainers Kafka). The two **planned** consumer rows (duplicate-delivery → applied-once; `apply`-throws → rollback-atomicity) are still being built — tracked in `dev-todo.md` §2.27. Everything else is verified today.

## Producer side

- **One outbox table per service** (`product.outbox_message`, `inventory.outbox_message`, etc.) — atomic with the aggregate write in the same DB transaction.
- **`OutboxDrainer.drain()`** (`shared.application.outbox.OutboxDrainer`; fired by `OutboxDrainScheduler`'s `@Scheduled(fixedDelayString = "${northwood.outbox.poll-interval:1000}")` in `shared.infrastructure.messaging`) — single-threaded per service, batch size 100, polls by service-local `sequence_number` (not `created_at` — see schema commentary for why). Synchronous publish per row (`.join()` on each send) — the outbox row only gets marked `published` after Kafka acknowledges. A broker-side failure marks the row `failed`; the next tick retries.
- **The drain transaction is load-bearing (and forces the two-bean split).** `OutboxDrainer.drain()` is `@Transactional` so `findPending`'s `FOR UPDATE SKIP LOCKED` locks are held for the whole batch — that's what makes multiple drainer workers safe. The transaction only fires through the Spring proxy, so `OutboxDrainer` must stay a **separate bean** from `OutboxDrainScheduler` (the scheduler's `tick()` → `drain()` is the external proxied call). Merging the two into one bean — or `new`-ing the drainer outside Spring — silently drops the transaction *and* the lock, after which concurrent drains can double-publish. Don't "simplify" the two `@Bean` methods in `OutboxDrainAutoConfiguration` into one.
- **One in-flight publish at a time per service.** Sequence-number cursor strictly increasing. No risk of out-of-order publish from a single service even at high event rates — the bottleneck is the broker, not the outbox.

### Consequence: cross-aggregate publish order matches outbox cursor order

Within one service, events are published in the order their outbox rows were written (which matches the order the aggregates committed). With multi-partition topics, that *publish* order is preserved per partition but interleaved across partitions on the consumer side. Single-broker doesn't change this — broker-side leader election is irrelevant when there's one broker, but partition consumers are still independent threads.

## Consumer side

- **One inbox table per service** (`<schema>.inbox_message`) — dedupe by `(consumer_name, event_id)`. Idempotent against redelivery / consumer-group rebalance / replay from `earliest` offset. The dedup *gate* (a config-selectable strategy — advisory lock by default), the `@Transactional` check → apply → record flow, and the rebalance-window race it closes are detailed in **[Consumer-side idempotency](#consumer-side-idempotency-exactly-once-effect)** below.
- **`KafkaInboxDispatcher`** — single `@KafkaListener` per consuming service, subscribed to the topics declared in `northwood.kafka.subscribe-topics` (per-service config in `application-kafka.yml`). Spring's `ConcurrentKafkaListenerContainerFactory` auto-applies the `DefaultErrorHandler` from `KafkaMessagingAutoConfiguration.java:84` — 3 immediate retries, then DLT.
- **`@KafkaListener` concurrency**: today defaults to 1. With multi-partition topics, bumping concurrency lets multiple partition-consumer threads run in parallel. The dispatcher's `apply()` is stateless beyond the inbox + projection writes; thread safety relies on per-row DB locking + idempotent SQL.

### Concurrency-safe projection patterns

Write patterns that are safe under multi-partition consumer concurrency:

- **Atomic SQL increment**: `UPDATE stock_balance SET on_hand_quantity = on_hand_quantity + ? WHERE product_id = ? AND warehouse_id = ?`. Safe regardless of how many consumer threads write concurrently — single-row UPDATE is atomic.
- **Idempotent state transition on the same key**: `UPDATE production_planning_board SET work_order_status = ? WHERE work_order_id = ? AND work_order_status NOT IN ('completed', 'cancelled')` — re-applying the same event on the same row converges. The `*_status NOT IN` guard is the idempotence enforcer.
- **Per-aggregate seed-then-fill**: `INSERT … ON CONFLICT DO NOTHING` (seed) + `UPDATE` (fill). Multi-partition can deliver the seed and the fill on different threads; the conflict + the WARN-and-insert fallback in `JdbcProductCardProjection.applyValuationClass` (lines 84-99) handle replay-out-of-order.

Write patterns that are **not** safe under multi-partition consumer concurrency:

- **Read-modify-write without optimistic version**: `SELECT … then UPDATE WHERE id=? AND version=?` without retrying on `OptimisticLockingFailureException`. Two threads both read v=5, both write v=6 — one wins, the other loses with no recovery. Saga rows have this guarded (`SagaPort.update` throws + manager retries); audit other projections in §2.14 to confirm none are doing this silently.

## Consumer-side idempotency (exactly-once effect)

The inbox is what converts Kafka's at-least-once *delivery* into exactly-once *effect*. This section is the consumer-side dedup in depth: what guarantees it, where the race is, and the config-selectable mechanism behind it.

### The goal: exactly-once effect, not exactly-once delivery

Kafka (and any broker, and Cassandra CDC — see `docs/infrastructure.md`) gives **at-least-once** delivery. The same event can arrive more than once: consumer-group rebalances, offset-commit gaps, replay from `earliest`, a redelivery after a processing error. A naive consumer that applies every delivery double-posts journal entries, double-reserves stock, double-increments balances.

The **inbox pattern** records that it has processed `(message_id, consumer_name)` and refuses to apply the same pair twice — exactly-once *effect*. The event may be *delivered* many times; its side effects land exactly once. The key is that the dedup record and the side effects commit **in one transaction**: if the side effects roll back, so does the "processed" record (the event becomes reprocessable); if they commit, the record commits with them (the event is durably done).

### The flow — `AbstractInboxHandler.handle()`

Every concrete inbox handler extends `shared.application.messaging.AbstractInboxHandler` and implements only `apply(payload, envelope)`. The base class runs the same four-step shape, all inside one `@Transactional` boundary:

```java
@Transactional
public void handle(EventEnvelope envelope) {
    if (!handles(envelope.eventType())) return;               // 1. not my event
    if (inbox.alreadyProcessed(eventId, consumerName)) return; // 2. dedup gate
    P payload = deserialize(envelope);                         // 3. decode
    apply(payload, envelope);                                  // 4a. side effects
    inbox.recordProcessed(InboxRow.processed(...));            // 4b. record
}
```

The application layer calls exactly two inbox methods — `alreadyProcessed` (the gate) and `recordProcessed` (the audit/dedup row). It expresses *intent*; it never sees the *mechanism* (advisory lock vs unique claim). That separation is what lets the mechanism be swapped by config (below).

#### Risk 1 — the transaction boundary is load-bearing

`handle()` is `@Transactional`. `apply()` and `recordProcessed()` commit or roll back **together** — the whole correctness argument for "exactly-once effect":

- If `apply()` throws, the transaction rolls back (including the inbox row), so the redelivery reprocesses cleanly. No half-applied event with a "processed" marker stranding it.
- If everything succeeds, the inbox row commits atomically with the side effects.

Two consequences that are easy to break:

1. **`@Transactional` only works through the Spring proxy.** `handle()` must be invoked on the proxied bean (it is — Spring Kafka's listener container calls the registered `InboxEnvelopeHandler` bean). A self-invocation, or `new`-ing a handler outside Spring, silently drops the transaction → `apply()` and `recordProcessed()` commit independently → a crash between them strands the event. See the CGLIB note in `AbstractInboxHandler`'s class Javadoc for the related reason its methods aren't `final`.
2. **`apply()` must keep all its writes inside that boundary.** A handler that opens its own nested transaction, or writes through a non-transactional path, escapes the atomicity guarantee. All `apply()` writes go through the same `JdbcTemplate`-backed ports, which enlist in the `handle()` transaction.

### Risk 2 — the race the gate has to close

The dedup *check* (`alreadyProcessed`) and the *record* (`recordProcessed`) are separated by `apply()` — a classic time-of-check-to-time-of-use window:

```
thread A: alreadyProcessed → false ─┐
thread B: alreadyProcessed → false ─┤  both see "not processed"
thread A: apply + record ───────────┤
thread B: apply + record ───────────┘  both apply → double effect
```

Two threads can only run this concurrently for the *same* `(message_id, consumer_name)` if the same message is being processed twice at once. On Kafka that requires **two consumers in the group simultaneously owning the same partition** — which happens only in the brief overlap window of a consumer-group rebalance (the old owner hasn't finished/committed when the new owner starts). Single-consumer-per-partition is the steady state, so the race is rare — but "rare" is not "never", and at higher `@KafkaListener` concurrency or under aggressive rebalancing it's a real bug.

#### Why the database's own constraint does NOT save you

`inbox_message` looks like it has a uniqueness guard — `UNIQUE (message_id, consumer_name, processed_at)` — but it does **not** enforce one-row-per-`(message_id, consumer_name)`. `processed_at` is in the key only because the table is `PARTITION BY RANGE (processed_at)` and PostgreSQL requires the partition key to appear in every unique constraint. Two duplicate inserts at different `processed_at` instants both satisfy this constraint and both succeed. So a concurrent double-insert is **not** rejected by the DB — the bare check-then-insert genuinely double-applies. **Dedup safety rests on the gate, not on the table constraint.**

#### The backstop: idempotent projection writes

Even with the gate, defence-in-depth matters. Most projection writes already converge under replay/reordering (see *Concurrency-safe projection patterns* above): atomic SQL increments, `INSERT … ON CONFLICT DO NOTHING` seeds, idempotent state transitions with `WHERE status NOT IN (...)` guards, and the planned highest-sequence-wins guard (`dev-todo.md` §3.8). The gate is the primary defence; idempotent writes are the backstop if a duplicate ever slips through.

### The dedup gate is a configurable strategy

The gate (`alreadyProcessed`) is the one piece whose *mechanism* varies. It's pulled behind an infrastructure SPI so the application never sees which mechanism is in force:

```
application/                         infrastructure/inbox/jdbc/
  AbstractInboxHandler ── InboxPort ── JdbcInboxAdapter
                          (intent)        │  recordProcessed()  (shared: writes the audit row)
                                          └─ InboxDedupStrategy  (the gate; config-selected)
                                               ├─ AdvisoryLockInboxDedupStrategy   (B, default)
                                               └─ (unique-claim — A, deferred)
```

`InboxPort` keeps its two-method shape (`alreadyProcessed` / `recordProcessed`). `JdbcInboxAdapter.alreadyProcessed` delegates to the configured `InboxDedupStrategy`; `recordProcessed` (the audit-row insert) is shared by every strategy. This is deliberate: **the mechanism is hidden, and the application layer plus every handler unit test stay untouched** when the mechanism changes. A `processOnce(envelope, Runnable)` template seam would have been "cleaner OO" but would have forced a rewrite of ~30 handler tests that mock `alreadyProcessed` / `recordProcessed`; keeping the intent-level API is both less churn and a better honouring of "mechanism stays in infrastructure".

Configuration:

```yaml
northwood:
  inbox:
    dedup-strategy: advisory-lock   # default; the only value wired today. Also: unique-claim (see below)
```

Selected in `JdbcInboxAutoConfiguration`. A service can also register its own `InboxDedupStrategy` / `InboxPort` bean (both are `@ConditionalOnMissingBean`) — e.g. the in-memory test double.

### Option B — advisory lock (default, PostgreSQL)

`AdvisoryLockInboxDedupStrategy`. Serialize processing of a given `(message_id, consumer_name)` with a **transaction-scoped** PostgreSQL advisory lock, then check existence:

```sql
-- statement 1: acquire (or wait for) the lock; released at transaction end
SELECT pg_advisory_xact_lock(hashtextextended(? , 0));   -- ? = messageId + ':' + consumerName
-- statement 2: fresh snapshot now that the lock is held
SELECT EXISTS (SELECT 1 FROM inbox_message WHERE message_id = ? AND consumer_name = ?);
```

`pg_advisory_xact_lock` is held until the consumer's transaction commits or rolls back — the *same* `@Transactional` boundary that wraps check → apply → record. So thread B, racing the same message, blocks at the lock until thread A commits, then re-checks against A's now-committed row, sees it, and skips. The race above is closed. No schema change.

#### Two statements, not one (the snapshot subtlety)

The lock is acquired in **its own statement before** the `EXISTS` check — never folded into a single CTE like `WITH _lock AS (SELECT pg_advisory_xact_lock(...)) SELECT EXISTS(...) FROM _lock`. Under `READ COMMITTED` (the default), a statement fixes its MVCC snapshot at statement start. A one-statement lock+check would take its snapshot *before* blocking on the lock, so after unblocking it would still read the **old** snapshot — missing the row the prior writer committed while we waited — and return `false` anyway. Running `EXISTS` as a *second* statement gives it a fresh snapshot taken *after* the lock is held, i.e. after the prior writer committed. This is subtle enough that the regression is invisible in single-threaded tests; the concurrency IT (below) is what pins it.

#### Cost and limits

Negligible cost. Advisory locks live in a PostgreSQL in-memory hash table — no disk, no row locks, no bloat. Contention happens **only between duplicates of the same `(message_id, consumer_name)`**; distinct messages hash to distinct keys and never wait on each other. So the only thing that ever blocks is an actual concurrent duplicate — exactly the case we *want* to serialize. The `hashtextextended` 64-bit hash can in principle collide (two unrelated keys serialize unnecessarily); the probability is negligible and a collision costs only a little extra serialization, never correctness.

PostgreSQL-specific, though. Other engines have advisory-lock equivalents with different APIs/semantics (SQL Server `sp_getapplock`, Oracle `DBMS_LOCK`, MySQL `GET_LOCK` is *session*- not transaction-scoped). If Northwood ever leaves PostgreSQL, switch to Option A.

### Option A — unique claim (portable, deferred)

`unique-claim`. Instead of locking, *claim* the work with a conditional write and let a unique constraint reject the duplicate:

```sql
INSERT INTO inbox_dedup (message_id, consumer_name) VALUES (?, ?) ON CONFLICT DO NOTHING;
-- rows affected == 0  → already claimed/processed → skip
-- rows affected == 1  → we won the claim → proceed to apply + recordProcessed
```

This is the **portable** mechanism: a conditional write on a uniqueness constraint exists in essentially every SQL engine *and* most NoSQL stores (DynamoDB conditional `PutItem`, MongoDB unique index, Cassandra `INSERT … IF NOT EXISTS`). It's the strategy to pick when the store isn't PostgreSQL.

**Why it's deferred, and why it needs its own table.** Option A requires a real `UNIQUE (message_id, consumer_name)`. The existing `inbox_message` *cannot* carry one — its `PARTITION BY RANGE (processed_at)` forces `processed_at` into every unique constraint (above). So Option A needs a small dedicated table that is *not* range-partitioned:

```sql
CREATE TABLE <service>.inbox_dedup (
    message_id     UUID         NOT NULL,
    consumer_name  VARCHAR(150) NOT NULL,
    claimed_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (message_id, consumer_name)   -- the real dedup key the partitioned table can't have
);
```

`alreadyProcessed` claims into `inbox_dedup`; `recordProcessed` still writes the audit row into `inbox_message` as today. Per the **option-2** decision (2026-05-26), the strategy + table are *not* built yet — `northwood.inbox.dedup-strategy=unique-claim` fails fast at startup with a pointer here. We wire it (`dev-todo.md` §3.10) when a non-PostgreSQL target actually appears.

**One semantic nuance when A lands:** A's `alreadyProcessed` has a *side effect* — it claims. Fine for the single-call-per-`handle()` usage (B's `alreadyProcessed` is a pure read; A's reads-and-claims), but it means `alreadyProcessed` is no longer idempotent if called twice without an intervening `recordProcessed`. The contract holds for the handler flow; just don't call it speculatively.

### A vs B at a glance

| | **B — advisory lock** (default) | **A — unique claim** (deferred) |
|---|---|---|
| Mechanism | `pg_advisory_xact_lock` + existence check | `INSERT … ON CONFLICT` on a unique key |
| Closes the TOCTOU race by | serializing same-key processing until commit | making the claim the gate (first writer wins) |
| Schema change | none (uses `inbox_message` as-is) | needs `inbox_dedup` table |
| Portability | PostgreSQL only | all SQL + most NoSQL (conditional write) |
| `alreadyProcessed` purity | pure read (+ transient lock) | reads **and claims** (side effect) |
| Cost | negligible; contends only on real duplicates | one extra insert per event |
| Status | **wired, default** | strategy seam ready; impl + table deferred (§3.10) |

### Verification

`JdbcInboxAdapterIT.advisory_lock_serializes_a_concurrent_duplicate` (in `shared`, real Postgres via Testcontainers) is the proof the in-process `SynchronousBus` harness can't give: two real transactions race the same `(message_id, consumer_name)`; the first holds the lock with an *uncommitted* inbox row; the second's `alreadyProcessed` must block on the lock and, once released, read a fresh snapshot showing the row → returns `true`. Without the lock its `EXISTS` would run against the uncommitted insert and return `false` (double-apply). Asserting the second sees `true` is the distinguishing observation. This partially closes the multi-partition race-verification gap flagged in the §2.14 audit items (2, 7) below.

The consumer-container contracts — offset-commit-only-after-success (with redelivery-on-failure), malformed-envelope skip, and DLT-after-retries — are pinned by `KafkaInboxDispatcherDeliveryIT` (in `shared`, real Kafka via Testcontainers). The complete guarantee → test map is the *Reliability & idempotency* matrix near the top of this doc.

## DLT (dead-letter topics)

`KafkaMessagingAutoConfiguration.kafkaErrorHandler` (line 84) wires a `DeadLetterPublishingRecoverer` that routes failed messages to `<sourceTopic>.dlt` *preserving the original partition* (`new TopicPartition(dlt, record.partition())`, line 93). This is the critical detail: **DLT topic partition count must match the source topic's partition count.** If `product.events` has 6 partitions but `product.events.dlt` is auto-created with 1, the recoverer publishes to partition 5 of a 1-partition topic → `UnknownTopicOrPartitionException` → DLT publish fails → poison-pill event isn't quarantined → consumer is stuck in the retry loop.

Today both are auto-created with 1 partition each — incidentally consistent. The §2.14 slice that flips `auto.create.topics.enable=false` must pre-declare both source + DLT with matching partition counts (or pick a recoverer strategy that doesn't pin partition).

## Hazards when scaling past 1 partition

This is the audit list for the §2.14 slice. Items 1-2 are concrete bugs in waiting; 3-5 are operational concerns; 6-7 are informational.

1. **DLT partition mismatch (HIGH).** Source + DLT must match. Pre-declare both via `NewTopic` beans with the same count.
2. **Saga concurrent-transition race verification (HIGH).** The design is correct (FOR UPDATE SKIP LOCKED + optimistic version) but the test harness is in-process / single-threaded and can't exercise the race. Need an integration-test target against a real multi-partition broker before relying on the safety. Cross-reference with the §2.6 cross-partition race regression that's currently un-exercisable.
3. **Read-modify-write audit (MEDIUM).** Audit every projection's write path. Atomic SQL increment is safe; load-process-write isn't. Candidates: `JdbcProductionPlanningProjection` (work-order status transitions + shortage updates), `JdbcProductCardProjection` (4 distinct handlers writing the same row), `JdbcStockBalanceWriter` (already does atomic `... = ... + ?`, confirm).
4. **Saga-prerequisite parking gets exercised more often (MEDIUM).** Today on 1 partition, saga-creation events arrive before their consequence events (publish-order preserved end-to-end). On N partitions, the consequence event can arrive on a different thread before the saga row exists. The handler must park and retry. Re-test the parking + replay paths under realistic delay (10-100ms broker latency, not 0ms in-process).
5. **Topic-creation ordering at deploy time (LOW once §2.14 lands).** With auto-create disabled, services must declare their topics before publishing. Spring's `KafkaAdmin` does this on context start, but new services added later need to declare their topics in their own `@Configuration` or first publish throws `UnknownTopicOrPartitionException`. Document in `docs/conventions.md` as part of the "adding a new service" checklist.
6. **Hot-partition risk on a low-cardinality aggregate (INFO).** With `aggregateId` as the key, all events for one aggregate hash to one partition. For the demo dataset (~12 SKUs, ~3 customers) this is fine. If a future load test pushes 10k events on one Product, that one partition becomes the bottleneck regardless of total partition count.
7. **Test-harness divergence (INFO).** `SynchronousBus` (test-harness) is in-process, single-threaded, no partitions. Multi-partition concurrency is invisible to harness tests. Need an integration-test mode against the real broker to verify the parking/retry behaviour and saga-row locking. §2.6 already flags this gap.

## Pointers

- `shared/.../kafka/KafkaEventPublisher.java` — partition key choice (line 49).
- `shared/.../kafka/KafkaMessagingAutoConfiguration.java` — error handler + DLT recoverer (line 84).
- `shared/.../application/outbox/OutboxDrainer.java` — drain loop (the `@Scheduled` trigger is `OutboxDrainScheduler` in `shared.infrastructure.messaging`), single-threaded, batch 100.
- `shared/.../saga/SagaManager.java` + `SagaPort.java` — claim-and-lease + optimistic concurrency.
- `shared/.../application/messaging/AbstractInboxHandler.java` — the `@Transactional` check → apply → record flow (idempotency risks 1 & 2 commented inline).
- `shared/.../application/inbox/InboxPort.java` — the intent-level dedup port (mechanism-free).
- `shared/.../infrastructure/inbox/jdbc/InboxDedupStrategy.java` + `AdvisoryLockInboxDedupStrategy.java` + `JdbcInboxAutoConfiguration.java` — the dedup gate SPI, Option B (default), and `northwood.inbox.dedup-strategy` selection.
- `shared/.../infrastructure/inbox/jdbc/JdbcInboxAdapterIT.java` — dedup semantics + the advisory-lock concurrency proof.
- `shared/.../infrastructure/messaging/kafka/KafkaInboxDispatcherDeliveryIT.java` — offset-commit / redelivery / malformed-skip / DLT contracts against a real broker.
- `docker-compose.yml:38-79` — Kafka KRaft single-broker setup, replication-factor-1 overrides.
- `dev-todo.md` §2.14 — pre-declare topics with configurable partition counts (with this doc's audit items as scope).
- `dev-todo.md` §2.13 — saga lease TTL + retry backoff → `@Value` config (related but independent).
- `dev-todo.md` §2.6 — cross-partition race regression test (un-exercisable today).
