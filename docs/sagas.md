# Sagas — state machines + manager class shape

Detail companion to `CLAUDE.md`. Read when authoring a new saga, a new inbox handler that advances one, or any worker that drains saga state.

## Saga / process manager — what we have today

Each long-running cross-context flow has its own saga state table in the schema that owns the flow:

- `sales.sales_order_fulfilment_saga` — wired through `completed`. Driven by inbox handlers for inventory's `StockReserved`, manufacturing's `WorkOrderCreated` / `WorkOrderManufacturingCompleted` / `ManufacturingDispatched`, inventory's `ShipmentPosted`, finance's `CustomerInvoiceCreated` / `CustomerPaymentReceived`. Multi-line orders track per-WO progress: `WorkOrderCreated` adds the WO id to `outstandingWorkOrderIds` in `saga.data`, `WorkOrderManufacturingCompleted` moves it to `completedWorkOrderIds`, the saga only advances when `outstanding` is empty AND `completed` non-empty. Sub-assembly child WOs are excluded (events carry `parentWorkOrderId`; sales handlers ignore non-null entries). **Cancel compensation flow:** `POST /api/sales-orders/{id}/cancel` is rejected with 409 once past `goods_shipped`; otherwise flips header to `cancelled` + saga to `compensating`. Inventory + manufacturing each apply idempotently and ack with `*.SalesOrderCancellationApplied`; both acks recorded as `Boolean` flags on `saga.data`. When both true, saga advances to `compensated` and emits `sales.SalesOrderCompensated`. Hard-cancel for in-progress WOs (WIP written off; soft-cancel parked in dev-todo).
- `manufacturing.make_to_order_saga` — wired through `completed`. Worker-driven `started → work_order_created → raw_material_reservation_requested`, with sub-assembly recursion (each child WO carries `parent_work_order_id` and gets its own saga at `work_order_created`). `inventory.RawMaterialsReserved` advances to `raw_materials_reserved` (or `raw_material_shortage`); the shortage path is un-parked by `inventory.GoodsReceived` flipping the saga back to `work_order_created` and re-emitting `RawMaterialReservationRequested` (inventory cancels the prior reservation row and re-attempts). Final hop driven inline by `WorkOrderOperationService`. Parent-on-children gate: a parent WO whose ops are all done holds at `in_progress` while any sub-assembly child is unfinished; on the last child, `WorkOrder.onChildCompleted(true)` cascades up the parent chain in the same transaction.
- `purchasing.purchase_to_pay_saga` — wired through `completed`. One row per PO (UNIQUE on `purchase_order_header_id`). Inserted at `started` in the same txn as PO creation. `started → purchase_order_approved` is driven inline (auto-approve for shortage-driven; manual via `POST /api/purchase-orders/{id}/approve`); `northwood.purchasing.shortagePoAutoApprove` (default `true`) gates auto-approve. Worker advances `purchase_order_approved → waiting_for_goods`. Inbox handlers advance `→ goods_received` (full receipt), `→ supplier_invoice_approved`, `→ completed (current_step=p2p_completed)` on full payment. Partial payments park at `supplier_payment_made`. The `three_way_match_*` states are reserved for future variance handling; no code path reaches them today.

Saga rows include `next_retry_at`, `lease_owner`, `lease_expires_at` for safe multi-worker polling via `SELECT ... FOR UPDATE SKIP LOCKED`.

## Reusable saga base — split across modules so domain doesn't import application

- `SagaInstance` (in `shared-kernel/.../domain/saga/`) — framework-free abstract row with state/lease/version fields. Service `*Saga` aggregates live in their service's `domain/saga/` and extend this. Kernel placement is what lets service-domain saga aggregates exist without crossing into application.
- `SagaPort<S extends SagaInstance>` (in `shared/.../application/saga/`) — generic port: `claimDue`, `save`, `insert`, `findBySagaId`. Claim is a single `UPDATE … WHERE saga_id IN (SELECT … FOR UPDATE SKIP LOCKED) RETURNING …` so lease-stamping is atomic. Service-specific `*SagaPort` (e.g. `SalesOrderFulfilmentSagaPort`) live in the service's `application/saga/` and extend this with a domain-key lookup method.
- `SagaManager<S extends SagaInstance, P extends SagaPort<S>>` — polling driver base. Holds one `protected final P sagaPort` (typed as the concrete saga port so subclasses reach saga-specific port methods like `findBySalesOrderId` directly) and one `protected final Logger log = LoggerFactory.getLogger(getClass())` (subclasses don't redeclare; log lines tag with the concrete class). Constructor takes a `PlatformTransactionManager` and builds an internal `TransactionTemplate(REQUIRES_NEW)`. `drain(int batchSize, String workerId, Consumer<S> advanceFn)` is `final`; subclasses pass an advance function rather than overriding an `advance(S)` method, so the base stays saga-state-only.

Two non-obvious rules baked into the base — preserve when adding a saga:

- **Each saga in a batch gets its own transaction.** `drain()` uses `TransactionTemplate.executeWithoutResult` per saga (`REQUIRES_NEW`), not one `@Transactional` over the whole loop. When the advance callback writes to the outbox and then throws, those writes must roll back so the retry doesn't double-emit.
- **The retry path reloads the saga before saving.** The advance callback may have mutated `saga.state()` etc. before throwing. After per-saga rollback the in-memory object still carries those mutations; saving them on retry persists a half-applied transition. So the catch block does `sagaPort.findBySagaId(saga.sagaId())` and applies `scheduleRetry` to the freshly loaded row.

Inbox handlers advance sagas too — the worker handles transitions where the saga has work to emit; the inbox handles transitions driven by inbound events. Both go through the same `sagaPort.save(saga)` so optimistic-concurrency keeps them coherent.

A startup-time `SagaInvariantsAutoConfiguration` validates that every concrete saga's CHECK constraint matches the states its worker / handlers can produce; failures abort startup with a clear delta.

**Only model states the code actually writes.** When a feature adds compensation states for one saga (e.g. cancel-order added `compensating`/`compensated` to sales + manufacturing), don't reflexively add them to other sagas' `ALL_STATES` "in case we need them later." The invariant checker reads `ALL_STATES` as code's claim of what it can produce — anything not in the DB CHECK fails boot. Purchasing's `PurchaseToPaySaga` had `compensating`/`compensated` listed defensively despite no transition code producing them; cleanest fix was removing the dead entries, not extending the schema CHECK to allow what nothing emits.

## Saga manager class shape — single source of truth per saga

Established §2.9 Slice A (sales fulfilment, 2026-05-09 → 2026-05-10). Each saga has one source-of-truth class for its state machine; worker shells, inbox handlers, and shared emitters orbit around it but hold no transition logic of their own.

**File layout per saga:**

| Role | Location | Notes |
|---|---|---|
| Manager interface | `<service>.application.saga.<Flow>SagaManager` | Orchestration verbs only — lifecycle (`insertStarted`, `requestCompensation`), `drain`, and one `applyXxx` per inbox event |
| Manager impl | `<service>.infrastructure.saga.Jdbc<Flow>SagaManager` | Extends `SagaManager<S, P>` from `shared.application.saga` |
| Worker shell | `<service>.infrastructure.saga.<Flow>SagaWorker` | `@Component` with `@Scheduled poll()` + worker-driven `advance(saga)` |
| Inbox handler shells | `<service>.application.inbox.*Handler` | One per inbox event type, each its own `consumer_name` |
| Cross-handler emitter | `<service>.application.<Flow>*Emitter` (or similar) | Shared cross-handler event emission. `application/`, NOT `application/inbox/` (inbox package is handler-only) |

**Manager dependencies are minimal.** Holds:
- `sagaPort` — typed as the concrete saga port via the abstract base's second type parameter, so saga-specific methods (e.g. `findBySalesOrderId`) are reachable without redeclaring the field on the subclass.
- `ObjectMapper json` for `saga.data` round-trip.
- `PlatformTransactionManager` (via super).

The manager **never** holds: `OutboxPort`, `JdbcTemplate`, projection ports for read-side echoes, repository ports for other aggregates in the same service, application services from this or any other context. Those collaborators belong with the side-effect owner (worker shell or inbox handler).

**Apply methods take saga-relevant primitives, not inbox payload types.** Acceptable arg types: `UUID`, `int`, `String`, `boolean`, `Map<X, Y>` — anything directly used by the saga state machine or in the saga's log lines. Inbox handlers parse the payload and pass primitives. This keeps the manager unaware of wire formats.

**Reference domain-event names via `<Event>.EVENT_TYPE`, never as a string literal.** Anywhere an inbox-event name surfaces inside a saga manager — the `eventName` arg to `requireSaga(...)`, `IllegalStateException` messages, log lines, anything user-facing or audit-visible — use the constant (`StockReserved.EVENT_TYPE`, `WorkOrderCreated.EVENT_TYPE`, `InventorySalesOrderCancellationApplied.EVENT_TYPE`, …). This makes the saga manager's domain-event dependency explicit at the import level: rename an event and the compiler points at every dependent manager. A hardcoded `"StockReserved"` string silently rots. The same rule applies elsewhere (inbox handlers, projection handlers, anywhere code names an event) — saga managers are just the most visible enforcement site.

**Reference aggregate-type strings via `<AggregateRoot>.AGGREGATE_TYPE`, never as a string literal.** The `aggregate_type` arg to `OutboxRow.pending(...)` (and the equivalent column in raw INSERT outbox writers) must come from a `public static final String AGGREGATE_TYPE` constant on the aggregate root — `SalesOrder.AGGREGATE_TYPE`, `WorkOrder.AGGREGATE_TYPE`, `Product.AGGREGATE_TYPE`, `StockReservation.AGGREGATE_TYPE`. Sagas that own their own emissions independently of any domain aggregate carry the constant on the saga state-machine class (`SalesOrderFulfilmentSaga.AGGREGATE_TYPE`). **Cross-service emission exception:** when the outbox writer lives in a different service than the aggregate root (so it can't import the root's Java class), or there's no aggregate root Java class, put the constant on the event class itself instead — e.g. `ManufacturingDispatched.AGGREGATE_TYPE = "SalesOrder"` (event in manufacturing-events, aggregate root in sales-service), `SupplierProductPriceChanged.AGGREGATE_TYPE = "SupplierProductPrice"` (no Java aggregate). Same motivation as the EVENT_TYPE rule: the wire-format string lives in one place, the compiler points at dependent writers if it ever moves.

**Apply methods that can't find their saga must throw, not warn-and-return-null.** Use the `requireSaga(id, EVENT_TYPE)` helper for consistency — an orphan ack with no saga row is a genuine invariant violation that inbox redelivery / dead-letter handling should surface, not silently swallow. The only places `findByXxx(...).orElse(null)` is acceptable are programmatic / lifecycle entry points where a missing saga is a legitimate no-op (e.g. `cancelForWorkOrder` when the work order had no make-to-order saga to begin with).

**Apply methods return the saga's new state (`String`)** so callers gate side effects on it:
- `applyStockReserved` returns `"stock_reserved"` → handler projects `markStatus(SO, "in_fulfilment")`
- `applyManufacturingDispatched` returns `"stock_reservation_failed"` → handler projects `markStatus(SO, "rejected")`
- `applyShipmentPosted` returns `"goods_shipped"` → handler calls `shipping.recordShipped(...)`
- `applyInventoryCancellationApplied` / `applyManufacturingCancellationApplied` returns `"compensated"` (only when both acks received) → handler calls the compensation emitter
- Returns `null` for genuinely-no-op cases (sub-assembly skipped, no-saga warns)

**Worker shell holds worker-driven side effects** — `JdbcTemplate` for sales-order-line / similar reads, `OutboxPort` for command emission (e.g. StockReservationRequested), `ObjectMapper` for serde, plus its own `workerId` field. `@Scheduled poll()` is a one-liner: `manager.drain(BATCH_SIZE, workerId, this::advance)`. The `advance(saga)` method switches on `saga.state()`, reads the DB, builds + emits the command, transitions the saga in-place; the abstract base saves the saga after the callback returns.

**Worker identity is the worker shell's concern, not the manager's.** Worker holds its own `workerId` field — typically `"<saga-name>-worker@" + ManagementFactory.getRuntimeMXBean().getName()` — and passes to `manager.drain(...)`. Manager doesn't construct or store the id; the lease-claim SQL stamps it into `lease_owner` only via the drain parameter.

**Inbox handler holds inbox-driven side effects.** One per event type. Shape: `handle(envelope)` → dedupe (`inbox.alreadyProcessed`) → deserialise payload → extract primitives → `manager.applyXxx(primitives)` (returns `String`) → side effects gated on return value → `inbox.recordProcessed`. Side-effect collaborators (status projection, shipping service, compensation emitter, etc.) are constructor-injected on the handler.

**Shared cross-handler emission factored into a small service.** When two handlers emit the same event under different conditions, extract a one-method service in `<service>.application/` — NOT `application/inbox/` (the inbox package is handler-only by convention). Example: `SalesOrderCompensationEmitter.emitCompensated(salesOrderHeaderId)` called by both `InventoryCancellationAppliedHandler` and `ManufacturingCancellationAppliedHandler` when their respective `apply*CancellationApplied` returns `"compensated"`.

**Test rebalance.** Manager test asserts only saga state changes (state, current_step, saga.data). Side effects (projections, shipping calls, outbox emissions) are tested in handler tests where they live. Each handler test mocks the manager and verifies the dedupe → manager call → side-effect gating → recordProcessed shape; one or two transition assertions per side-effect branch is enough.
