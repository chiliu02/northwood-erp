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
2. Optimistic concurrency on `save()` (`SagaPort.java:32`) — `UPDATE ... WHERE saga_id = ? AND version = ?` with `version = version + 1`; throw on zero rows affected. A second concurrent transition triggers `OptimisticLockingFailureException`, the saga manager catches it and reschedules via `scheduleRetry` (`SagaManager.java:127-137`).

What an inbox handler does when it can't find the saga yet (because `SalesOrderPlaced` from one partition hasn't been processed before `StockReserved` from another partition arrives): **park and retry**. The §2.6 dev-todo entry references a 2026-05-05 cross-partition race fix that landed this pattern. Multi-partition makes the parking path the common case rather than the exception — re-test the parking + replay timing thoroughly before bumping partition counts. See §2.14 audit items.

## Producer side

- **One outbox table per service** (`product.outbox_message`, `inventory.outbox_message`, etc.) — atomic with the aggregate write in the same DB transaction.
- **`OutboxPublisher.drain()`** (`shared.infrastructure.outbox.OutboxPublisher`) — `@Scheduled(fixedDelayString = "${northwood.outbox.poll-interval:1000}")`, single-threaded per service, batch size 100, polls by service-local `sequence_number` (not `created_at` — see schema commentary for why). Synchronous publish per row (`.join()` on each send) — the outbox row only gets marked `published` after Kafka acknowledges. A broker-side failure marks the row `failed`; the next tick retries.
- **One in-flight publish at a time per service.** Sequence-number cursor strictly increasing. No risk of out-of-order publish from a single service even at high event rates — the bottleneck is the broker, not the outbox.

### Consequence: cross-aggregate publish order matches outbox cursor order

Within one service, events are published in the order their outbox rows were written (which matches the order the aggregates committed). With multi-partition topics, that *publish* order is preserved per partition but interleaved across partitions on the consumer side. Single-broker doesn't change this — broker-side leader election is irrelevant when there's one broker, but partition consumers are still independent threads.

## Consumer side

- **One inbox table per service** (`<schema>.inbox_message`) — dedupe by `(consumer_name, event_id)`. Idempotent against redelivery / consumer-group rebalance / replay from `earliest` offset.
- **`KafkaInboxDispatcher`** — single `@KafkaListener` per consuming service, subscribed to the topics declared in `northwood.kafka.subscribe-topics` (per-service config in `application-kafka.yml`). Spring's `ConcurrentKafkaListenerContainerFactory` auto-applies the `DefaultErrorHandler` from `KafkaMessagingAutoConfiguration.java:84` — 3 immediate retries, then DLT.
- **`@KafkaListener` concurrency**: today defaults to 1. With multi-partition topics, bumping concurrency lets multiple partition-consumer threads run in parallel. The dispatcher's `apply()` is stateless beyond the inbox + projection writes; thread safety relies on per-row DB locking + idempotent SQL.

### Concurrency-safe projection patterns

Write patterns that are safe under multi-partition consumer concurrency:

- **Atomic SQL increment**: `UPDATE stock_balance SET on_hand_quantity = on_hand_quantity + ? WHERE product_id = ? AND warehouse_id = ?`. Safe regardless of how many consumer threads write concurrently — single-row UPDATE is atomic.
- **Idempotent state transition on the same key**: `UPDATE production_planning_board SET work_order_status = ? WHERE work_order_id = ? AND work_order_status NOT IN ('completed', 'cancelled')` — re-applying the same event on the same row converges. The `*_status NOT IN` guard is the idempotence enforcer.
- **Per-aggregate seed-then-fill**: `INSERT … ON CONFLICT DO NOTHING` (seed) + `UPDATE` (fill). Multi-partition can deliver the seed and the fill on different threads; the conflict + the WARN-and-insert fallback in `JdbcProductCardProjection.applyValuationClass` (lines 84-99) handle replay-out-of-order.

Write patterns that are **not** safe under multi-partition consumer concurrency:

- **Read-modify-write without optimistic version**: `SELECT … then UPDATE WHERE id=? AND version=?` without retrying on `OptimisticLockingFailureException`. Two threads both read v=5, both write v=6 — one wins, the other loses with no recovery. Saga rows have this guarded (`SagaPort.save` throws + manager retries); audit other projections in §2.14 to confirm none are doing this silently.

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
- `shared/.../outbox/OutboxPublisher.java` — drain loop, single-threaded, batch 100.
- `shared/.../saga/SagaManager.java` + `SagaPort.java` — claim-and-lease + optimistic concurrency.
- `docker-compose.yml:38-79` — Kafka KRaft single-broker setup, replication-factor-1 overrides.
- `dev-todo.md` §2.14 — pre-declare topics with configurable partition counts (with this doc's audit items as scope).
- `dev-todo.md` §2.13 — saga lease TTL + retry backoff → `@Value` config (related but independent).
- `dev-todo.md` §2.6 — cross-partition race regression test (un-exercisable today).
