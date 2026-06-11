# Messaging design

How events flow from producer outbox to consumer inbox over Kafka — partition key choice, ordering guarantees, hazards, and the audit items to clear before scaling partition counts past 1.

Event topics are explicitly declared at **3 partitions** by default (configurable — see *Status today*); the DLT/`.parked` topics are still broker-auto-created. This doc captures the design choices that hold whether partitions = 1 or partitions = N, and the audit items that only matter at N > 1.

## Status today

- **Topic shape**: one event topic per producer service — `product.events`, `inventory.events`, `sales.events`, `manufacturing.events`, `purchasing.events`, `finance.events`. Decision recorded in `KafkaEventPublisher.java:24` ("never per-event-type"). Per-event-type topics were rejected because Find-Usages-on-the-event-class becomes the cross-service traceability anchor (see `MEMORY.md` — event classes are the load-bearing artifact), and per-service topics keep that 1:1 with the source aggregate's outbox.
- **Partition count**: each `<service>.events` topic is declared at **3 partitions** by default, overridable per service via `northwood.kafka.topic.partitions`. The producer service owns the declaration — `KafkaMessagingAutoConfiguration.eventsTopic` publishes a `NewTopic` bean (gated on `northwood.outbox.drain.enabled=true`, so only producers declare; consumer-only services declare none), which Spring's `KafkaAdmin` materialises on context start. `KafkaAdmin` only ever *adds* partitions, so raising the count is a safe rolling change; lowering it is ignored. DLT/`.parked` topics are still broker-auto-created (their partition count is decoupled from the source — see *Source topic vs DLT*).
- **Wire format**: JSON via Jackson 3. No schema registry by design.
- **Replication factor**: 1 across the board (single-broker showcase — see the `KAFKA_*_REPLICATION_FACTOR=1` overrides in `docker-compose.yml:62-67`). Event-topic RF is `northwood.kafka.topic.replication-factor` (default 1) — raise for a real multi-broker cluster.
- **Auto-create**: still enabled at the broker (`KAFKA_AUTO_CREATE_TOPICS_ENABLE=true`) so DLT/`.parked` topics appear on first publish; event topics no longer rely on it (explicitly declared above).

## Critical: partition key choice

**The partition key is `envelope.aggregateId().toString()`** — set in `KafkaEventPublisher.java:49`. This is the single load-bearing design choice that determines what ordering guarantees consumers can rely on.

### What this choice guarantees

Kafka guarantees: messages with the same key always land on the same partition, and partition logs are strictly ordered. So with `key = aggregateId`:

- **Per-aggregate event order is preserved.** All events for `Product(id=X)` — `ProductCreated`, `StandardCostChanged`, `ValuationClassChanged`, `ProductDiscontinued` — land on the same partition and are consumed in the order the producer wrote them. The four `finance.product_card` projection handlers can rely on `ProductCreated` arriving before `StandardCostChanged` (so the seed-row exists when the cost-update UPDATE runs).
- **Per-aggregate partition stickiness through schema partition changes.** Adding partitions to an existing topic re-hashes new keys; existing keys keep their partition unless the partitioner uses range-style hashing (Kafka's default `DefaultPartitioner` uses murmur2 hash mod num-partitions, which DOES re-shard). So increasing partition count on a live topic shuffles future events for an aggregate to a possibly-different partition than its past events. **Caveat**: events already in flight before the partition bump stay where they were; the consumer must drain those before new partitions get traffic. Not an issue at deploy time if done before any events flow into new partitions.

### What this choice does NOT guarantee

- **Cross-aggregate ordering is not preserved**, even within the same topic. `PurchaseRequisitionCreated` (key=PR) and `PurchaseOrderCreated` (key=PO, references the PR) can land on different partitions and be consumed concurrently by different threads. A consumer reading the PO-partition may process `PurchaseOrderCreated` before its own group has even fetched `PurchaseRequisitionCreated` from the PR-partition.
- **Cross-topic ordering is never preserved**, regardless of key. Events in `sales.events` and `inventory.events` are independent partition logs; the relative order they reach a multi-topic consumer (sagas, BFF aggregators) is whatever scheduling gives.

### Open question for cross-saga keying

A saga isn't an aggregate — it's a cross-aggregate coordinator. Events the saga consumes carry different aggregate IDs (a sales-fulfilment saga consumes `SalesOrderPlaced` keyed by SO-id, `StockReserved` keyed by Reservation-id, `WorkOrderManufacturingCompleted` keyed by WO-id, `ShipmentPosted` keyed by Shipment-id). With multi-partition topics, those events arrive on different partition-consumer threads and try to advance the same saga concurrently.

**Today's mitigation is correct** but worth recording:

1. `SagaPort.claimDue` uses `SELECT ... FOR UPDATE SKIP LOCKED` (`SagaPort.java:14`) — only one worker claims a given saga row at a time, and SKIP_LOCKED means sibling workers move on rather than blocking.
2. Optimistic concurrency on `update()` (`SagaPort.java`) — `UPDATE ... WHERE saga_id = ? AND version = ?` with `version = version + 1`; throw on zero rows affected. A second concurrent transition triggers `OptimisticLockingFailureException`, the saga manager catches it and reschedules via `scheduleRetry` (`SagaManager.java:127-137`).

What an inbox handler does when it can't find the saga yet (because `SalesOrderPlaced` from one partition hasn't been processed before `StockReserved` from another partition arrives): **park and retry**. A 2026-05-05 cross-partition race fix landed this pattern. Multi-partition makes the parking path the common case rather than the exception — re-test the parking + replay timing thoroughly before bumping partition counts. See the audit items below.

## Reliability & idempotency: guarantees and where they're tested

Reliable delivery + idempotent consumption are the cornerstone of this architecture, so this matrix is the index for the detailed sections that follow: every guarantee, the mechanism that provides it, and the test that covers it. The **doc-only** rows are inherently not unit-testable (you can't deterministically crash a process mid-commit) — each is absorbed by a mechanism that *is* tested, noted in the row.

### Producer — reliable emission (the outbox)

| Guarantee | Mechanism | Covered by |
|---|---|---|
| Event row written atomically with the aggregate change (no event without the state change, and vice-versa) | one local `@Transactional`; `repository.save()` drains `pendingEvents` → `OutboxPort.appendPending` in the same tx | aggregate `Jdbc*RepositoryIT`s (the outbox-row-delta assertions on save) |
| Published in `sequence_number` order; row marked `published` only after broker ack | `OutboxDrainer.drain()` `.join()`s each send before `OutboxPort.update(…published)` | `OutboxDrainerTest.drain_publishes_each_row_marks_published_and_saves`; `JdbcOutboxAdapterIT.findPending_returns_rows_in_sequence_number_order…` + `…update_marks_published…` |
| Broker failure → row left `failed` → retried next tick | per-row try/catch marks `failed`, continues the batch | `OutboxDrainerTest.drain_partial_failure_marks_failed_row_and_continues` |
| Concurrent drainers never double-claim a row | `findPending` `FOR UPDATE SKIP LOCKED` held inside the drain `@Transactional` | `JdbcOutboxAdapterIT.findPending_skips_rows_locked_by_another_transaction` |
| Crash after broker ack, before the `published` mark → row re-published next tick (duplicate on the topic) | at-least-once publish; duplicate carries the same `eventId` → collapsed by the consumer inbox | **doc-only** (process crash); the absorbing dedup is covered by `JdbcInboxAdapterIT` + `DuplicateDeliveryAppliedOnceIT` |

### Consumer — idempotent consumption (the inbox; exactly-once effect)

| Guarantee | Mechanism | Covered by |
|---|---|---|
| Dedup keyed `(message_id, consumer_name)`, independent per consumer | inbox row + `alreadyProcessed` check | `JdbcInboxAdapterIT.recordProcessed_then_alreadyProcessed_is_true`, `…dedup_is_keyed_per_consumer` |
| Concurrent duplicate of the same message serialized (TOCTOU race closed) | advisory-lock gate (default), held across `handle()`'s tx | `JdbcInboxAdapterIT.advisory_lock_serializes_a_concurrent_duplicate` |
| `apply` + `recordProcessed` atomic; `apply` throws → both roll back → reprocessable | `handle()` `@Transactional` boundary | `InboxApplyRollbackAtomicityIT.applyThrows_rollsBackAtomically_thenAppliesOnceOnRedelivery` (inventory, Testcontainers Kafka + Postgres) |
| Offset committed only after the listener returns successfully | container-managed commit, default `BATCH` ack mode | `KafkaInboxDispatcherDeliveryIT.offset_commits_only_after_the_listener_returns_successfully` |
| Handler exception → offset not committed → record redelivered | error-handler re-seek (no commit) | same test (the failure half) |
| Duplicate delivery (e.g. producer re-publish) applied exactly once | redelivery hits `alreadyProcessed=true` → skip | `DuplicateDeliveryAppliedOnceIT.duplicateDelivery_isAppliedExactlyOnce` (inventory, Testcontainers Kafka + Postgres) |
| Malformed envelope → skipped + offset committed (the one failure that does NOT redeliver — poison-pill avoidance) | dispatcher catches `JacksonException`, returns normally | `KafkaInboxDispatcherDeliveryIT.malformed_envelope_is_skipped_and_offset_still_commits` |
| Transient handler failure → retried under backoff, then DLT once the budget is exhausted, then offset commits (no infinite loop) | `DefaultErrorHandler(ExponentialBackOff)` + `DeadLetterPublishingRecoverer` | `KafkaInboxDispatcherDeliveryIT.retryable_failure_is_dead_lettered_after_backoff_then_offset_commits` |
| Deterministic/poison failure → DLT on the first failure (no wasted retries), then offset commits | `DefaultErrorHandler.addNotRetryableExceptions(NOT_RETRYABLE)` | `KafkaInboxDispatcherDeliveryIT.non_retryable_failure_is_dead_lettered_without_retry` |
| Crash between the DB commit and the offset commit → redelivery → dedup skips | inbox dedup absorbs the redelivery | **doc-only** (process crash); absorbed by the dedup covered above |

### Saga — cross-aggregate coordination

| Guarantee | Mechanism | Covered by |
|---|---|---|
| At most one worker advances a saga at a time | `claimDue` `FOR UPDATE SKIP LOCKED` + lease | `Jdbc{SalesOrderFulfilment,WorkOrder,PurchaseToPay}SagaAdapterIT.claimDue_leases_active_due_rows_and_blocks_immediate_reclaim` |
| Concurrent transition → optimistic-lock failure → manager retries | `update … WHERE saga_id = ? AND version = ?` | the same ITs' `update_enforces_optimistic_lock_via_version` |
| Backed-off saga not re-claimed before `next_retry_at` | due-time filter in `claimDue` | the same ITs' `claimDue_skips_rows_with_future_next_retry_at` |
| Out-of-order prerequisite (cross-partition) → park + retry | handler parks when the saga row is absent | sales cross-partition regression (handler-parking path) |
| Concurrent duplicate on two partitions → applied once | advisory-lock inbox dedup gate under real listener concurrency | `SalesFulfilmentSagaCrossPartitionRaceIT.duplicateStockReserved_onTwoPartitions_advancesSagaExactlyOnce` |

> **Coverage status (2026-05-27):** every row above is now **verified** — the three formerly-deferred rows landed as `KafkaInboxDispatcherDeliveryIT`, `DuplicateDeliveryAppliedOnceIT`, and `InboxApplyRollbackAtomicityIT` against Testcontainers Kafka + Postgres. The only un-asserted entries are the two **doc-only** process-crash rows, each absorbed by a mechanism that is itself tested.

## Disaster recovery — total auto-recovery vs. manual vs. unrecoverable

Runbook companion to the guarantees matrix — audited 2026-05-27 against the code. Every failure sorts into exactly one of three classes:

- ✅ **Auto-recovered fully** — no operator action, no data loss.
- 🛠 **Manual step required** — the durable record survives, so a human can recover it.
- ❌ **Full recovery impossible** — the durable record itself is gone; data is permanently lost.

**The single rule that decides the class:** *is the PostgreSQL-resident source of truth (the `outbox_message` / `inbox_message` / `*_saga` tables + aggregates) intact?* The outbox is the system of record, not Kafka — so **losing Kafka is recoverable** (re-drive from the outbox) while **losing PostgreSQL without a backup is the one unrecoverable disaster**. Everything else self-heals.

### At a glance

| Failure / disaster | Class | Why |
|---|---|---|
| Service / container / host **restart** (crash, OOM, deploy, reschedule) | ✅ auto | state is in Postgres; in-flight consumer messages redeliver (offset uncommitted), saga steps re-claim after lease expiry. Loops are restart- **and** multi-instance-safe (`outbox` `FOR UPDATE SKIP LOCKED`; saga lease + per-instance `workerId`). |
| **Kafka broker** down → back (volume kept) | ✅ auto | producers' rows go `failed` → retried next tick (`findPending` selects `pending`+`failed`); consumers pause then resume; fixed `CLUSTER_ID` reattaches the volume with data + offsets. |
| **PostgreSQL** down → back (volume kept) | ✅ auto | request path errors; the `@Scheduled` outbox/saga loops throw but Spring's scheduler logs-and-suppresses → next tick retries (HikariCP reconnects). Consumer messages in flight *during* the outage are now ridden out by the error handler's `ExponentialBackOff` (default 5 min budget) rather than burned through in ms — only an outage **longer** than that budget dead-letters (finding 1 was the old `FixedBackOff(0,3)` behaviour). |
| **Network partition** between two services | ✅ auto | no synchronous service-to-service calls exist (only the outbox/Kafka), so events queue and flow on reconnect. No corruption. (A partition to a BFF just fails the *read*.) |
| Consumer handler **transient** failure (clears within the retry budget) | ✅ auto | `DefaultErrorHandler` re-seeks (no offset commit) → redelivered under an `ExponentialBackOff` (default 5 min budget) → inbox dedup makes the re-apply safe. |
| Duplicate delivery / rebalance / replay-from-`earliest` | ✅ auto | inbox dedup gate skips it (exactly-once effect). |
| Saga worker crash mid-step | ✅ auto | `claimDue` reclaims any row where `lease_owner IS NULL OR lease_expires_at < now()`. |
| Saga step **transient** failure | ✅ auto | `scheduleRetry(now + backoff)` releases the lease; re-claimed once `next_retry_at <= now()`. |
| Cross-partition out-of-order prerequisite | ✅ auto | handler **parks and retries** until the prerequisite row exists. |
| Process crash between DB commit and offset commit | ✅ auto | redelivery on restart → inbox dedup skips the already-applied effect. |
| **Dead-lettered** record whose transient cause has since cleared | ✅ auto | the per-service `DltRedriver` re-applies it through the live handler fan-out (filtered by the `kafka_dlt-original-consumer-group` header); inbox dedup makes the re-apply safe. |
| Consumer messages dead-lettered during a **dependency outage** within the retry budget | ✅ auto | the `ExponentialBackOff` (default 5 min) rides out the blip and the record succeeds on a later retry. |
| Consumer messages dead-lettered during an outage **longer than the retry budget** | ✅ auto* | the `DltRedriver` re-applies the dead-lettered record after the fact; it succeeds once the dependency is back. *Parked (→ 🛠) only if still failing after the redrive cap. |
| Consumer **deterministic / poison** failure (bad payload, constraint, NPE) | 🛠 manual | classified non-retryable → dead-lettered immediately, no wasted retries; the `DltRedriver` then retries to the cap and **parks** it in `<topic>.dlt.parked`. |
| **Parked** record (`<topic>.dlt.parked`) | 🛠 manual | redrive cap exhausted (genuinely unrecoverable) — terminal store carrying the origin-group + last-error headers; fix forward + replay (no in-repo tooling). |
| **Poison outbox row** (publish always fails) | 🛠 manual | retried every tick forever (no `retry_count` cap, no terminal status); fix the cause or correct/remove the row. |
| **Stuck saga** (advance always fails) | 🛠 manual | retried forever (no terminal `failed` state); fix forward or force-resolve via SQL — there is no cancel/force-complete control. |
| **Malformed envelope** on a topic | 🛠 manual | skipped + offset committed (dropped at the consumer); re-emit from the producer's outbox. |
| **Kafka** volume **lost** (RF=1, no replica) | 🛠 manual | published-but-unconsumed events + offsets gone — **but re-drivable from the outbox** (the durable record): reset `outbox_message.status` `published`→`pending`. |
| **PostgreSQL** volume **lost**, no backup | ❌ impossible | the source of truth (outbox/inbox/saga/aggregates/projections/audit) is gone — nothing to recover from. |

### ✅ Auto-recovered fully

These need no operator action and lose nothing — the outbox is the durable producer-side record and the inbox makes every consumer redelivery idempotent, so transient broker/DB/network/process failures and clean restarts all converge on their own. This now includes consumer messages arriving **during** a dependency outage: the error handler's `ExponentialBackOff` rides out a blip up to its retry budget (default 5 min), and even a record dead-lettered after a *longer* outage is re-applied by the per-service `DltRedriver` once the dependency is back. Only a record still failing after the redrive cap is **parked** for ops (the residual 🛠 case); the old `FixedBackOff(0,3)` dead-lettered in ms (finding 1) is fixed.

### 🛠 Manual step required (recoverable — the durable record survives)

1. **Parked records (`<topic>.dlt.parked`).** A record reaches `<source-topic>.dlt` (via the `DeadLetterPublishingRecoverer` in `KafkaMessagingAutoConfiguration.inboxErrorHandler`) either after exhausting the `ExponentialBackOff` retry budget (a transient failure that never cleared — default 5 min) or immediately if classified non-retryable (a poison/deterministic exception). The source offset commits so the consumer is never blocked. The DLT is then **auto-drained** by each service's `DltRedriver`: it re-applies the records *it* dead-lettered (filtered on the `kafka_dlt-original-consumer-group` header) through the live handler fan-out, so a transient cause that has since cleared recovers with no operator action (inbox dedup makes the re-apply safe). **Only the genuinely-unrecoverable records survive to the manual tier:** after `northwood.kafka.dlt.redrive.max-attempts` re-applies still fail, the record is published to a terminal `<topic>.dlt.parked` store (carrying the origin-group + `northwood-dlt-redrive-attempts` + last-error headers) that nothing consumes. Recover by: *detect* (parked-topic depth; the redriver logs `ERROR "redrive … exhausted … parked in …"`), *diagnose* from the payload + headers and ship a fix, *replay* by re-publishing the parked records onto the source topic (console producer or a small script — inbox dedup makes a racing replay safe). **No in-repo tooling for the parked-store replay yet.**

2. **Poison outbox row (publish always fails).** A row whose publish never succeeds (payload the broker rejects, serialization defect) is retried on **every** drain tick — **no `retry_count` cap, no terminal `dead` status** (`outbox_message.status` CHECK is only `pending | published | failed`). It doesn't head-of-line-block siblings (the loop continues past it), but never clears itself, and a *later* row for the **same aggregate** can publish ahead of it (order break). Recover: `… WHERE status = 'failed'` (read `retry_count` + `last_error`), fix the cause and let it drain, or correct/remove the row.

3. **Stuck saga (advance always fails).** `SagaInstance.scheduleRetry` only bumps `retry_count` and pushes `next_retry_at` — **no max-retry → terminal `failed` state** (the only terminal states are the *success* states in `terminalStates()`). So it retries forever with backoff, trail in `last_error`/`retry_count`. Recover: diagnose from `last_error`, fix forward (it resumes on the next due tick). **No operator control to cancel/abandon/force-complete** — the BFF `/api/sagas` feed + `SagaAggregatorController.pump()` are **read-only** (the Saga Console SSE projection); a force-resolve means direct SQL on the `*_saga` row.

4. **Malformed envelope on a topic.** The dispatcher catches `JacksonException`, logs `ERROR "Skipping malformed envelope …"`, and **commits the offset** (poison-pill avoidance — the one failure that neither redelivers nor dead-letters; `KafkaInboxDispatcher.onMessage`). The event is dropped at the consumer; since the producer's outbox row is already `published`, nothing re-emits it. Recover: spot the ERROR line, re-emit/replay from the outbox. Only fires on a producer serialization defect or a hand-mangled message.

5. **Kafka volume lost (RF=1, no replica).** Single broker, `KAFKA_DEFAULT_REPLICATION_FACTOR=1` — no replica, so a lost volume loses every message *published-but-not-yet-consumed* plus all consumer offsets (topics auto-recreate on next publish). **Recoverable because the outbox in Postgres is the source of truth, not Kafka:** re-drive by resetting the relevant `outbox_message.status` from `published` back to `pending` (the inbox dedup makes an over-broad re-drive safe — already-applied events are skipped). This is the concrete payoff of "the outbox, not the broker, is the system of record."

### ❌ Full recovery impossible (permanent data loss)

**PostgreSQL volume lost with no backup** is the *only* unrecoverable disaster — and it is unrecoverable **only because no backup exists**. There is no `pg_dump`/PITR/replica anywhere in the repo; durability rests entirely on the `northwood-pgdata` docker volume. Lose it and the outbox, inbox, saga state, aggregates, projections and audit all go with it — there is no surviving record to re-drive from (unlike Kafka loss, which the outbox covers). The only "recovery" today is `docker compose down -v` + reseed from `config/postgresql/northwood_erp.sql` (+ `northwood_erp_seed.sql`) — i.e. **start clean, accept total loss of business data**. Acceptable for a local showcase; not for production.

> A backup/replica would demote this straight into 🛠. The fix is the deferred HA work: RDS Multi-AZ / Aurora + a real backup/PITR policy. Until then, a single PostgreSQL is a single point of failure for **all seven services** (schema-per-service in one DB).

### Two sharp findings the audit surfaced

1. **~~A dependency outage longer than *milliseconds* dead-letters live traffic.~~ FIXED (2026-05-27).** The consumer error handler *was* `FixedBackOff(0,3)` — 3 *immediate* (zero-delay) retries, no backoff — so a brief PostgreSQL/dependency outage (while Kafka kept *delivering*) burned each record's retries in a few ms and dumped a burst of live traffic into the DLT. It is now a classify-and-backoff `DefaultErrorHandler` (`KafkaMessagingAutoConfiguration.inboxErrorHandler`): transient/infra exceptions are retried under an `ExponentialBackOff` (default budget 5 min, tunable via `northwood.kafka.consumer.retry.*`) so a blip is ridden out, while deterministic/poison exceptions (`NOT_RETRYABLE`) dead-letter on the first failure instead of wasting the budget. The producer side was already the opposite — the outbox retries a down broker *forever*. Remaining gap: a >5-min outage still dead-letters (then needs DLT-replay, or auto-redrive via `DltRedriver`); a `ContainerPausingBackOff` / circuit-breaker that pauses the container on infra exceptions is the heavier alternative, not wired.

2. **No backup/restore exists for PostgreSQL or Kafka** (only the docker volumes) — which is exactly why the two storage-loss rows split across 🛠 (Kafka, covered by the outbox) and ❌ (Postgres, the source of truth itself). Add a Postgres backup and the ❌ row disappears.

### Caveats that shape the runbook

- **Detection is manual today.** Automated alerting on stuck outbox / stuck saga / error-rate is deferred. Until it lands, discovery relies on logs, Grafana, and direct table queries — the data is all there (`outbox_message.status = 'failed'` + `last_error`; `*_saga` rows with climbing `retry_count` + `last_error`; DLT topic depth).
- **BFF restart (non-messaging).** `erp-web-ui-bff` holds OIDC session/token state in-JVM, so a restart logs every user out (re-login; Redis-backed sessions are deferred). The synchronous *command* path is also not durable — a POST in flight when a service dies is lost and the client must resubmit; only state that already committed (and so its outbox row) survives.
- **Replaying/rebuilding a projection.** Kafka retains the log, so offset-rewind replay is possible; to *re-apply* (not merely re-skip) the target consumer's `inbox_message` rows for those messages must be cleared first. Under a future SQS swap this would not be replayable — replay would mean re-draining the outbox.

## Producer side

- **One outbox table per service** (`product.outbox_message`, `inventory.outbox_message`, etc.) — atomic with the aggregate write in the same DB transaction.
- **`OutboxDrainer.drain()`** (`shared.application.outbox.OutboxDrainer`; fired by `OutboxDrainScheduler`'s `@Scheduled(fixedDelayString = "${northwood.outbox.poll-interval:1000}")` in `shared.infrastructure.messaging`) — single-threaded per service, batch size 100, polls by service-local `sequence_number` (not `created_at` — see schema commentary for why). Synchronous publish per row (`.join()` on each send) — the outbox row only gets marked `published` after Kafka acknowledges. A broker-side failure marks the row `failed`; the next tick retries.
- **The drain transaction is load-bearing (and forces the two-bean split).** `OutboxDrainer.drain()` is `@Transactional` so `findPending`'s `FOR UPDATE SKIP LOCKED` locks are held for the whole batch — that's what makes multiple drainer workers safe. The transaction only fires through the Spring proxy, so `OutboxDrainer` must stay a **separate bean** from `OutboxDrainScheduler` (the scheduler's `tick()` → `drain()` is the external proxied call). Merging the two into one bean — or `new`-ing the drainer outside Spring — silently drops the transaction *and* the lock, after which concurrent drains can double-publish. Don't "simplify" the two `@Bean` methods in `OutboxDrainAutoConfiguration` into one.
- **One in-flight publish at a time per service.** Sequence-number cursor strictly increasing. No risk of out-of-order publish from a single service even at high event rates — the bottleneck is the broker, not the outbox.

### Consequence: cross-aggregate publish order matches outbox cursor order

Within one service, events are published in the order their outbox rows were written (which matches the order the aggregates committed). With multi-partition topics, that *publish* order is preserved per partition but interleaved across partitions on the consumer side. Single-broker doesn't change this — broker-side leader election is irrelevant when there's one broker, but partition consumers are still independent threads.

### Saga-trace linkage

A business transaction is two trace-time halves: the synchronous **request** (BFF → sales, done when the 201 returns) and the **async saga continuation** that flows over the bus long after. Each cross-service hop is its **own bounded trace**, **linked** back to the one that triggered it (fixed `span-link` behaviour — an earlier configurable `northwood.tracing.saga-linkage` knob with `parent-child`/`both`/`off` modes was removed once span-link was settled on; reparenting produced unbounded long-lived traces, and the whole-saga overview is the separate saga milestone view instead):

- **Capture at append.** Every emitter stamps the *current* trace context into `outbox_message.headers` as a `traceparent` when the row is written — inside the same request/consumer thread whose span we want to link from. Aggregate writes (the 16 `Jdbc*Repository.writeOutbox`) and the saga/non-aggregate path (`OutboxAppender`) both call `OutboxTraceHeaders.currentJson()`. It reads the OTel current span statically (`io.opentelemetry.api.trace.Span.current()`) so no `Tracer` has to be threaded through 16 repository constructors; the format matches `Traceparent.format`. Write-once event-less aggregates (`finance.JournalEntry`) emit nothing, so they capture nothing.
- **Link at drain.** `OutboxDrainer` parses that stored `traceparent`, rebuilds a `TraceContext` (`Traceparent.parse`), and adds it as a `Link` on the `outbox.publish` span. The publish span stays in its own (drain-tick) trace; Spring Kafka's observation-enabled producer (`spring.kafka.template.observation-enabled`) stamps *its* context onto the record, and the listener-side observation continues that trace on the consumer — so each hop is a bounded trace linked to its predecessor, navigable one click at a time.
- **Degrades safely.** No captured context (write outside any traced request), or no `Tracer`/`ObjectMapper`/unparseable header → the publish span is plain (drain-tick-rooted), no link. The envelope's `traceparent` header (for the BFF events aggregator's ↗-trace affordance) carries the captured originating context when present.

## Consumer side

- **One inbox table per service** (`<schema>.inbox_message`) — dedupe by `(consumer_name, event_id)`. Idempotent against redelivery / consumer-group rebalance / replay from `earliest` offset. The dedup *gate* (a config-selectable strategy — advisory lock by default), the `@Transactional` check → apply → record flow, and the rebalance-window race it closes are detailed in **[Consumer-side idempotency](#consumer-side-idempotency-exactly-once-effect)** below.
- **`KafkaInboxDispatcher`** — single `@KafkaListener` per consuming service, subscribed to the topics declared in `northwood.kafka.subscribe-topics` (per-service config in `application-kafka.yml`). Spring's `ConcurrentKafkaListenerContainerFactory` auto-applies the `DefaultErrorHandler` built by `KafkaMessagingAutoConfiguration.inboxErrorHandler` — transient exceptions retried under an `ExponentialBackOff` (default 5 min budget), poison/deterministic exceptions (`NOT_RETRYABLE`) dead-lettered on the first failure.
- **`@KafkaListener` concurrency**: `northwood.kafka.listener.concurrency` sets the listener thread count and **defaults to the topic partition count** (`northwood.kafka.topic.partitions`, default 3) — one consumer thread per partition out of the box. A `BeanPostProcessor` in `KafkaMessagingAutoConfiguration` clamps the effective value to `max(1, min(requested, partitions))` on the auto-configured `ConcurrentKafkaListenerContainerFactory` (Spring Boot 4 dropped the `ConcurrentKafkaListenerContainerFactoryCustomizer` hook), so an over-set concurrency can't spawn idle threads — within a group Kafka assigns at most one consumer per partition. The dispatcher's `apply()` is stateless beyond the inbox + projection writes; thread safety relies on per-row DB locking + idempotent SQL.
- **`DltRedriver`** — a *second* per-service `@KafkaListener` (group `<service>-dlt-redriver`, pattern `.+\.dlt`), enabled by `northwood.kafka.dlt.redrive.enabled`. Auto-drains the DLT: for each record it reads the `kafka_dlt-original-consumer-group` header and **re-applies only its own** (others belong to other services' redrivers), retrying through the live `KafkaInboxDispatcher` fan-out up to `max-attempts` (with `delay` between), then parking the rest in `<topic>.dlt.parked`. **Header for routing, partition for concurrency**: the DLT key stays `aggregateId` (so partitions keep ordering + parallelism), and the listener uses ordinary group subscription with configurable `concurrency`, so a slow (blocking) redrive on one partition never stalls the others. It catches every re-apply failure and returns normally, so the `inboxErrorHandler` never fires on it (no `.dlt.dlt`). Full rationale: `DltRedriver` Javadoc.

### Concurrency-safe projection patterns

Write patterns that are safe under multi-partition consumer concurrency:

- **Atomic SQL increment**: `UPDATE stock_balance SET on_hand_quantity = on_hand_quantity + ? WHERE product_id = ? AND warehouse_id = ?`. Safe regardless of how many consumer threads write concurrently — single-row UPDATE is atomic.
- **Idempotent state transition on the same key**: `UPDATE production_planning_board SET work_order_status = ? WHERE work_order_id = ? AND work_order_status NOT IN ('completed', 'cancelled')` — re-applying the same event on the same row converges. The `*_status NOT IN` guard is the idempotence enforcer.
- **Per-aggregate seed-then-fill**: `INSERT … ON CONFLICT DO NOTHING` (seed) + `UPDATE` (fill). Multi-partition can deliver the seed and the fill on different threads; the conflict + the WARN-and-insert fallback in `JdbcProductCardProjection.applyValuationClass` (lines 84-99) handle replay-out-of-order.

Write patterns that are **not** safe under multi-partition consumer concurrency:

- **Read-modify-write without optimistic version**: `SELECT … then UPDATE WHERE id=? AND version=?` without retrying on `OptimisticLockingFailureException`. Two threads both read v=5, both write v=6 — one wins, the other loses with no recovery. Saga rows have this guarded (`SagaPort.update` throws + manager retries); audit other projections before increasing partition counts to confirm none are doing this silently.

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

Even with the gate, defence-in-depth matters. Most projection writes already converge under replay/reordering (see *Concurrency-safe projection patterns* above): atomic SQL increments, `INSERT … ON CONFLICT DO NOTHING` seeds, idempotent state transitions with `WHERE status NOT IN (...)` guards, and the planned highest-sequence-wins guard. The gate is the primary defence; idempotent writes are the backstop if a duplicate ever slips through.

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

`alreadyProcessed` claims into `inbox_dedup`; `recordProcessed` still writes the audit row into `inbox_message` as today. Per the **option-2** decision (2026-05-26), the strategy + table are *not* built yet — `northwood.inbox.dedup-strategy=unique-claim` fails fast at startup with a pointer here. We wire it when a non-PostgreSQL target actually appears.

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
| Status | **wired, default** | strategy seam ready; impl + table deferred |

### Verification

`JdbcInboxAdapterIT.advisory_lock_serializes_a_concurrent_duplicate` (in `shared`, real Postgres via Testcontainers) is the proof the in-process `SynchronousBus` harness can't give: two real transactions race the same `(message_id, consumer_name)`; the first holds the lock with an *uncommitted* inbox row; the second's `alreadyProcessed` must block on the lock and, once released, read a fresh snapshot showing the row → returns `true`. Without the lock its `EXISTS` would run against the uncommitted insert and return `false` (double-apply). Asserting the second sees `true` is the distinguishing observation. This partially closes the multi-partition race-verification gap flagged in the audit items (2, 7) below.

The consumer-container contracts — offset-commit-only-after-success (with redelivery-on-failure), malformed-envelope skip, and DLT-after-retries — are pinned by `KafkaInboxDispatcherDeliveryIT` (in `shared`, real Kafka via Testcontainers). The complete guarantee → test map is the *Reliability & idempotency* matrix near the top of this doc.

## DLT (dead-letter topics)

`KafkaMessagingAutoConfiguration.inboxErrorHandler` wires a `DeadLetterPublishingRecoverer` that routes failed messages to `<sourceTopic>.dlt` and **routes by key-hash** — `new TopicPartition(dlt, -1)` — letting the producer's default partitioner pick within the DLT's *own* partition count. The record key stays the unchanged `aggregateId`, so all of one aggregate's dead-lettered records co-locate on a single DLT partition (per-aggregate ordering preserved) and the chosen partition always exists.

This **decouples the DLT's partition count from the source topic's**: a DLT may have any number of partitions (1, the auto-created default, or N) without risk of a publish to a non-existent partition. An earlier version pinned `record.partition()`, which tied the two counts together and would have dropped/wedged records once a scaled-up source out-partitioned its DLT — see *Hazards*, item 1, for the full before/after. Covered by `KafkaInboxDispatcherDeliveryIT.dead_letter_routes_by_key_hash_when_dlt_has_fewer_partitions_than_source` (source declared with more partitions than its DLT; the dead-letter copy still lands).

## Hazards when scaling past 1 partition

This is the audit list for scaling past 1 partition. Items 3-5 are operational concerns; 6-7 are informational. Item 1 was a latent bug, now resolved; item 2 (the saga concurrent-transition race) is now exercised end-to-end and resolved.

1. **DLT partition mismatch (RESOLVED).** Previously the dead-letter recoverer pinned the *source* partition number — `new TopicPartition(dlt, record.partition())` — tying the DLT's partition count to the source topic's: a record from source partition 4 had to land on DLT partition 4, so a scaled-up source that out-partitioned its (auto-created, 1-partition) DLT would publish to a non-existent partition, and the failed recovery either wedged the source partition retrying the poison record or dropped it. The recoverer now routes with `new TopicPartition(dlt, -1)` — partition `-1` lets the producer's key-hash partitioner pick within the DLT's *own* partition count. Because the record key is the unchanged `aggregateId`, all of one aggregate's dead-lettered records still co-locate on a single DLT partition (per-aggregate ordering preserved) **and** the chosen partition always exists. The DLT's partition count is now fully decoupled from the source's — a DLT may have any number of partitions (even 1, the current default) safely. (`KafkaMessagingAutoConfiguration.inboxErrorHandler`.) The redrive tier parallelises up to the DLT's partition count, so a DLT scaled to N gives N-way redrive concurrency; a 1-partition DLT serialises redrive but stays correct.
2. **Saga concurrent-transition race verification (RESOLVED).** The safety holds at two layers — the saga claim (`FOR UPDATE SKIP LOCKED` + optimistic version) and the inbox advisory-lock dedup gate — and both are now exercised under genuine concurrency, which the in-process `SynchronousBus` harness still cannot see:
   - **DB-level**, on Testcontainers Postgres alone: `JdbcSalesOrderFulfilmentSagaAdapterIT.claimDue_under_concurrency_grants_a_due_saga_to_exactly_one_worker` — two transactions race the same due saga row; `SKIP LOCKED` grants it to exactly one.
   - **Full delivery-path**, on a real multi-partition broker: `SalesFulfilmentSagaCrossPartitionRaceIT` (`sales-service`) drives the sales-fulfilment saga across a 2-partition `inventory.events` with listener `concurrency=2`, publishing one `StockReserved` verbatim to **both** partitions so two listener threads consume it at once. The advisory-lock dedup gate skips the loser; the test asserts the saga advances to `ready_to_ship` and emits `SalesOrderReadyToShip` **exactly once** (one `inbox_message` row, one outbox row). This is the multi-partition-broker target this item called for.
3. **Read-modify-write audit (MEDIUM).** Audit every projection's write path. Atomic SQL increment is safe; load-process-write isn't. Candidates: `JdbcProductionPlanningProjection` (work-order status transitions + shortage updates), `JdbcProductCardProjection` (4 distinct handlers writing the same row), `JdbcStockBalanceWriter` (already does atomic `... = ... + ?`, confirm).
4. **Saga-prerequisite parking gets exercised more often (MEDIUM).** Today on 1 partition, saga-creation events arrive before their consequence events (publish-order preserved end-to-end). On N partitions, the consequence event can arrive on a different thread before the saga row exists. The handler must park and retry. Re-test the parking + replay paths under realistic delay (10-100ms broker latency, not 0ms in-process).
5. **Topic-creation ordering at deploy time (LOW).** Mostly handled for event topics: every producer service now declares its own `<service>.events` via the `eventsTopic` `NewTopic` bean (gated on `northwood.outbox.drain.enabled`), so Spring's `KafkaAdmin` creates it on context start — a new producer service inherits this automatically. Residual: the DLT/`.parked` topics still rely on broker auto-create; if `auto.create.topics.enable=false` is ever flipped, those need pre-declaring too. Document in `docs/conventions.md` as part of the "adding a new service" checklist.
6. **Hot-partition risk on a low-cardinality aggregate (INFO).** With `aggregateId` as the key, all events for one aggregate hash to one partition. For the demo dataset (~12 SKUs, ~3 customers) this is fine. If a future load test pushes 10k events on one Product, that one partition becomes the bottleneck regardless of total partition count.
7. **Test-harness divergence (INFO).** `SynchronousBus` (test-harness) is in-process, single-threaded, no partitions. Multi-partition concurrency is invisible to harness tests. Need an integration-test mode against the real broker to verify the parking/retry behaviour and saga-row locking.

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
- Event topics are pre-declared with configurable partition counts (`KafkaMessagingAutoConfiguration.eventsTopic`, default 3 via `northwood.kafka.topic.partitions`). Remaining: pre-declare DLT/`.parked` topics if broker auto-create is ever disabled.
- Listener concurrency is configurable (`northwood.kafka.listener.concurrency`, default = partition count) and clamped to the partition count by a `BeanPostProcessor` in `KafkaMessagingAutoConfiguration` (line 174).
- Saga lease TTL + retry backoff → `@Value` config (related but independent).
- Cross-partition saga race — DB-level `JdbcSalesOrderFulfilmentSagaAdapterIT.claimDue_under_concurrency_*` + full delivery-path `SalesFulfilmentSagaCrossPartitionRaceIT` (2-partition broker, `concurrency=2`, duplicate-across-partitions → applied once).
