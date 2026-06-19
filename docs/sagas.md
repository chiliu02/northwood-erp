# Sagas — state machines + manager class shape

Detail companion to `CLAUDE.md`. Read when authoring a new saga, a new inbox handler that advances one, or any worker that drains saga state.

## Aggregate vs Saga — a thing vs a process

Both an aggregate and a Saga can carry a state machine, so they look alike on a diagram. They answer different questions:

- An **aggregate with a state machine** (e.g. `inventory.ReplenishmentRequest`: `requested → dispatched → fulfilled`/`cancelled`) is a **thing** — one business fact with identity, inside **one** consistency boundary, and the source of truth for that fact. Each transition is one local ACID transaction (mutate the row + drain `pendingEvents` to the outbox in the same `save()`), and the aggregate enforces its own invariants (`markFulfilled()` throws unless `status == dispatched`).
- A **Saga** (e.g. `sales.sales_order_fulfilment_saga`) is a **process** — progress markers for a workflow that spans **many** boundaries (aggregates / services / external systems) that cannot be committed atomically together. It owns no business fact; it coordinates the things that do.

The notation is shared; the semantics are not. `docs/system-map.html` deliberately titles the inventory diagram *"ReplenishmentRequest (aggregate, not a Saga)"* for exactly this reason. This is the same instinct as `docs/conventions.md` → *deltas get aggregates, totals get projections*, applied to processes.

| | Aggregate (state machine) | Saga (process manager) |
|---|---|---|
| Is a… | business fact / thing | workflow / process |
| States mean | lifecycle of one fact | progress through a multi-step flow |
| Boundary | one aggregate, one txn per transition | many boundaries, eventual consistency |
| Owns | a business invariant | only orchestration progress |
| Undo | a local terminal state (`cancelled`) | distributed compensation (terminal `compensated`) |
| Emits | its own deltas (drained at `save()`) | commands, if anything — some Sagas emit nothing |
| Driver | passive — handlers mutate it | active — leased polling worker + retries |
| Persisted row is | the source of truth | a coordination bookmark (state, lease, retry, `data`) |
| Carries | `AGGREGATE_TYPE`, `pendingEvents`, invariant | lease/retry fields, `data`; no invariant |

Litmus test (the `docs/conventions.md` rule, applied to processes): **does it emit its own delta — a fact with identity and downstream consumers? → aggregate. Does it only watch other people's deltas and decide what to do next, owning no fact of its own? → a process (and *maybe* a Saga — see the next section).**

## When does a flow actually need a Saga?

Crossing into a second aggregate does **not** automatically require a Saga. Coordinating across boundaries has two forms, and only one is a Saga:

- **Choreography** — aggregate A emits an event; a handler updates aggregate B in its own transaction. No central coordinator, no remembered state. Northwood does this constantly (e.g. a product price change fanning out to inventory/sales/manufacturing snapshot projections). *Multiple aggregates, multiple services, zero Sagas.*
- **Orchestration (the Saga)** — a stateful coordinator that remembers where the process is and drives it step by step.

A Saga is warranted only when **both** hold:

1. the process spans **more than one transaction** (multiple aggregates, services, or external systems — it can't be committed atomically), **and**
2. it needs **stateful coordination** — at least one of:
   - **remember progress** across steps (a later step depends on earlier ones),
   - **gate / join** on multiple outcomes ("don't ship until stock reserved *and* prepayment paid"),
   - **compensate** on failure (a later step fails → undo earlier ones, which means having recorded that they happened),
   - **wait with timeout / retry** (park until an event arrives, then retry).

If you have (1) but not (2) — each reaction is independent, fire-and-forget, needs no memory or undo — choreography suffices. The Saga is specifically for the *stateful, joinable, compensatable, retryable* case. Note the real axis is **consistency boundaries**, not aggregate count — an external system (a payment gateway, say) is another boundary and can pull in a Saga even with a single aggregate.

**The decision ladder:**

1. Fits in one aggregate, one txn per transition? → **aggregate state machine**. No Saga.
2. Crosses boundaries, but each effect is independent fire-and-forget (A happened ⇒ do B, never look back)? → **choreography** (event + handler). Still no Saga.
3. Crosses boundaries **and** needs to remember progress / join multiple outcomes / compensate / retry-with-timeout? → **Saga** (process manager / orchestration).

## The three Sagas, analysed against the criteria

Each clears the bar (multiple boundaries + stateful coordination), but they lean on *different* criteria — "Saga" is not one shape.

**`sales.sales_order_fulfilment_saga` — gate + compensate + drive.** Spans sales + inventory + finance. Remembers progress; **gates** shipment on prepayment and **joins** reservation/replenishment acks; **compensates** on cancel (release reservations, reverse prepayment postings); **waits/retries** by parking at `stock_reservation_incomplete`. It also **emits** commands (`StockReservationRequested`, `PrepaymentInvoiceRequested`). The only Saga here that compensates, and the only one whose state set grows/shrinks with business gates — the full-strength "why Sagas exist" example. (Why it does *not* drive manufacturing: see *Why the Sales Order Fulfilment Saga does not drive manufacturing* below.)

**`manufacturing.work_order_saga` — wait/retry + fan-in join.** Spans manufacturing ↔ inventory, plus a parent/child tree of `WorkOrder` instances.
- *Remember progress* ✓: `work_order_created → raw_material_reservation_requested → raw_materials_reserved`/`raw_material_shortage → in_progress → completed`.
- *Gate / join* ✓: the **parent-on-children** join — a parent WO holds at `in_progress` until every sub-assembly child WO completes.
- *Wait / retry* ✓ — its load-bearing justification: the `raw_material_shortage` path parks and is un-parked by `inventory.GoodsReceived`, which re-emits `RawMaterialReservationRequested` to retry. Cross-service wait-then-retry.
- *Compensate* ✗ today: WO cancellation was retired once no WO was bound to a sales order; the Saga is now forward-only.
- *Emits* one command (`RawMaterialReservationRequested`).

Sharp nuance: not all of its coordination is Saga work. The parent-child **cascade** runs in-aggregate, in one transaction (`WorkOrder.onChildCompleted(true)` cascades up the parent chain). The Saga holds the *join state* (which children are still outstanding); the aggregate holds the *cascade mechanics*. A clean case of a flow splitting work between Saga and aggregate, each for the part it suits.

**`purchasing.purchase_to_pay_saga` — passive join / progress tracker.** Spans the widest set — purchasing ↔ inventory ↔ finance.
- *Remember progress* ✓: `started → purchase_order_approved → waiting_for_goods → goods_received → supplier_invoice_approved → completed`.
- *Gate / join* ✓: completion is a **three-way join** — receipt *and* invoice approval *and* full payment, three independent events from three services; partial payment parks at `supplier_partially_paid`. (The `three_way_match_*` states are reserved for future variance handling; nothing reaches them yet.)
- *Wait / retry* ✓: long parks against inbound events with the standard lease/retry polling.
- *Compensate* ✗: forward-only; no PO-unwind path.
- *Emits* no worker commands at all — every transition is inline or inbox-handler-driven against `PurchaseOrder` / `SupplierInvoice` / `Payment`.

The most interesting interrogation: with no emission and no compensation, *is it a Saga or a status projection of the PO?* It stays a Saga, by a hair, for two reasons a projection can't satisfy: (1) it makes an **authoritative completion decision** over a multi-service join (and partial payment ≠ complete), not a field-mirror; and (2) its worker **self-advances** `purchase_order_approved → waiting_for_goods` — a transition driven by no external event. Strip those two and it would collapse into a read-model projection. Tracing exactly *why the answer is "Saga"* is the sharpest way to feel where the Saga/projection line runs.

**Summary — three shapes of Saga:**

| | SO Fulfilment | WorkOrder | PurchaseToPay |
|---|---|---|---|
| Boundaries | sales + inventory + finance | manufacturing + inventory (+ child WOs) | purchasing + inventory + finance |
| Remember progress | ✓ | ✓ | ✓ |
| Gate / join | ✓ prepayment gate + reservation join | ✓ parent-on-children fan-in | ✓ receipt + invoice + payment join |
| Wait / retry | ✓ park on shortage | ✓ park on shortage, retry on receipt | ✓ wait for goods / invoice / payment |
| Compensate | ✓ cancel → release + reverse | ✗ (retired) | ✗ |
| Emits commands? | ✓ reservation, prepayment | ✓ reservation | ✗ (passive) |
| Archetype | **gate + compensate + drive** | **wait/retry + fan-in join** | **passive join / tracker** |

The payoff of analysing all three: **"Saga" isn't one thing.** All qualify, but SO Fulfilment is the full-strength compensating orchestrator; WorkOrder is justified mainly by cross-boundary wait/retry plus a fan-in (handing the cascade back to the aggregate); PurchaseToPay is the minimal *remember + join + decide-completion* tracker that most tempts the "could this be a projection?" question — and answering it is the cleanest way to locate the Saga/projection boundary.

## Saga / process manager — what we have today

Each long-running cross-context flow has its own saga state table in the schema that owns the flow:

- `sales.sales_order_fulfilment_saga` — the **pruned orchestration** (process progress only — the order *status* is the `classify(lines)` fold on the aggregate, not a saga state). Forward work ends at `supply_secured` (renamed from `ready_to_ship` — all lines reserved); the post-supply ship → invoice → pay leg is event-reactive. The saga's only post-supply act is the **completion gate**: it latches two `saga.data` flags — `orderShipped` (inventory's `ShipmentPosted` with `orderFullyShipped`) + `orderSettled` (finance's `CustomerPaymentReceived` with `orderFullySettled`) — and transitions `supply_secured → completed` once both land (order-independent; the two events carry different partition keys, so each is set then the gate is re-checked). `completed` is kept because the saga's own drain / compensation machinery reads the state; the old per-milestone post-supply states (`goods_shipped` / `partially_shipped` / `invoice_created` / `invoice_partially_paid`) were non-branching pass-throughs whose facts live on the line / 360 axes and were dropped — and sales no longer consumes `finance.CustomerInvoiceCreated`. **Prepayment / deposit gate:** these terms park at `awaiting_prepayment` until the up-front invoice is paid, then advance to the unified `prepaid` checkpoint (the worker requests reservation; the old `awaiting_prepayment_invoice` / `awaiting_deposit_invoice` / `deposit_invoiced` / `deposit_paid` intermediates collapsed). For a deposit, only the prepayment leg latches `orderSettled` at the gate — the deposit's balance invoice + payment land after shipment and latch `orderSettled` at `supply_secured`. Driven by inbox handlers for inventory's `StockReserved` / `ReplenishmentFulfilled` / `ReplenishmentCancelled` / `ShipmentPosted` and finance's `CustomerPaymentReceived`. **Inventory-as-MRP-orchestrator (the flip):** a partial/failed reservation parks the saga at `stock_reservation_incomplete` while inventory replenishes (inventory raises the `ReplenishmentRequest` in the same transaction as the reservation, routing make-vs-buy itself); `saga.data.outstandingReplenishmentLineIds` tracks the short lines; each `ReplenishmentFulfilled` removes one, and when the set empties the saga re-enters `stock_reservation_requested` to retry. A `ReplenishmentCancelled` for any short line → `rejected`. Sales no longer drives or tracks manufacturing (the `manufacturing_requested` / `manufacturing_in_progress` / `manufacturing_completed` / `purchasing_requested` states + the WO-tracking handlers + events `sales.ManufacturingRequested` / `manufacturing.ManufacturingDispatched` / `sales.SalesOrderPurchasingRequested` were retired). **Cancel compensation flow (two-phase, inventory-arbitrated):** `POST /api/sales-orders/{id}/cancel` is rejected with 409 once sales already sees a shipped line (the domain `anyLineShipped` predicate — `supply_secured` is non-terminal, so a reserved-but-unshipped order still cancels). Otherwise it only **requests** cancellation: `SalesOrder.requestCancellation` emits `sales.SalesOrderCancellationRequested` **without** flipping the header or touching the saga. Inventory then arbitrates the request against any concurrent shipment on the shared `sales_order_line_facts` rows — `tryClaimCancellation` marks every not-yet-shipped line `cancelled`, and the ship-claim (`tryClaimShipment`) refuses a `cancelled` line. If no line had shipped, inventory releases the reservation idempotently and acks `inventory.SalesOrderCancellationApplied` (recorded as a `Boolean` flag on `saga.data`); on that ack sales `confirmCancellation` flips the header to `cancelled` and the saga advances from its active state **straight to `compensated`** (no prior `compensating` hop — the request no longer pre-compensates, since a shipment could win the race), emitting `sales.SalesOrderCompensated`. If a shipment won the race, inventory emits **no** ack — the cancel is silently dropped and the order rides the normal ship → invoice → pay path. This is what closes the cancel-vs-ship race: whichever of the two claims commits first wins, and the order is never both shipped and cancelled, with no synchronous cross-service call (the schema-per-service + outbox-only invariants forbid one). (The manufacturing leg of the gate was retired — no work order is bound to a sales order, so inventory is the sole compensation contract; the manufacturing sales-cancel handler, `WorkOrderCancellationService`, `WorkOrder.cancel`, and `manufacturing.WorkOrderCancelled` were all deleted.)
- `manufacturing.work_order_saga` — wired through `completed`; now purely make-to-stock. Every WO is entered directly at `work_order_created` by `WorkOrderReleaseService.releaseForReplenishment` (the sales-driven `started` entry was retired), with sub-assembly recursion (each child WO carries `parent_work_order_id` and gets its own saga at `work_order_created`). Worker-driven `work_order_created → raw_material_reservation_requested`. `inventory.RawMaterialsReserved` advances to `raw_materials_reserved` (or `raw_material_shortage`); the shortage path is un-parked by `inventory.GoodsReceived` flipping the saga directly to `raw_material_reservation_requested`, with `GoodsReceivedHandler` re-emitting `RawMaterialReservationRequested` synchronously via the shared `RawMaterialReservationRequestEmitter` (no longer bounces through `work_order_created`; inventory cancels the prior reservation row and re-attempts). Final hop driven inline by `WorkOrderOperationService`. Parent-on-children gate: a parent WO whose ops are all done holds at `in_progress` while any sub-assembly child is unfinished; on the last child, `WorkOrder.onChildCompleted(true)` cascades up the parent chain in the same transaction.
- `purchasing.purchase_to_pay_saga` — wired through `completed`. One row per PO (UNIQUE on `purchase_order_header_id`). Inserted at `started` in the same txn as PO creation. `started → purchase_order_approved` is driven inline (auto-approve for shortage-driven; manual via `POST /api/purchase-orders/{id}/approve`); `northwood.purchasing.shortagePoAutoApprove` (default `true`) gates auto-approve. Worker advances `purchase_order_approved → waiting_for_goods`. Inbox handlers advance `→ goods_received` (full receipt), `→ supplier_invoice_approved`, `→ completed (current_step=p2p_completed)` on full payment. Partial payments park at `supplier_partially_paid`. The `three_way_match_*` states are reserved for future variance handling; no code path reaches them today.

Saga rows include `next_retry_at`, `lease_owner`, `lease_expires_at` for safe multi-worker polling via `SELECT ... FOR UPDATE SKIP LOCKED`.

## Why the Sales Order Fulfilment Saga does not drive manufacturing

Northwood is **make-to-stock** (the business model is captured in `docs/business-requirements.md`, REQ-INV-090): production is triggered by inventory stocking policy — the reorder point — not pulled into existence by each sales order. So the Sales Order Fulfilment Saga does **not** orchestrate manufacturing or purchasing. On a partial or failed reservation it records the short line ids on `saga.data.outstandingReplenishmentLineIds`, parks at `stock_reservation_incomplete`, and waits. Inventory raises a `ReplenishmentRequest` (an aggregate, not a Saga) in the *same transaction* as the reservation and owns the make-vs-buy routing — the Saga never sees whether the gap is closed by a work order or a purchase order.

This is the reason the manufacturing leg was collapsed out of the Saga: once sourcing is owned by inventory policy rather than by the order, the `manufacturing_requested` / `manufacturing_in_progress` / `manufacturing_completed` / `purchasing_requested` states plus their WO-tracking handlers and fields had nothing left to coordinate (the retired states and events are enumerated in the inventory-as-MRP-orchestrator note on the `sales.sales_order_fulfilment_saga` bullet above).

The resulting shape is intentionally thin: reserve from stock → (if short) park and retry on `ReplenishmentFulfilled`, or reject on `ReplenishmentCancelled`. The Saga waits on a **delta**; it does not coordinate sourcing.

## Reusable saga base — split across modules so domain doesn't import application

- `SagaInstance` (in `shared-kernel/.../domain/saga/`) — framework-free abstract row with state/lease/version fields. Service `*Saga` aggregates live in their service's `domain/saga/` and extend this. Kernel placement is what lets service-domain saga aggregates exist without crossing into application.
- `SagaPort<S extends SagaInstance>` (in `shared/.../application/saga/`) — generic port: `claimDue`, `save`, `insert`, `findBySagaId`. Claim is a single `UPDATE … WHERE saga_id IN (SELECT … FOR UPDATE SKIP LOCKED) RETURNING …` so lease-stamping is atomic. Service-specific `*SagaPort` (e.g. `SalesOrderFulfilmentSagaPort`) live in the service's `application/saga/` and extend this with a domain-key lookup method.
- `SagaManager<S extends SagaInstance, P extends SagaPort<S>>` — polling driver base. Holds one `protected final P sagaPort` (typed as the concrete saga port so subclasses reach saga-specific port methods like `findBySalesOrderId` directly) and one `protected final Logger log = LoggerFactory.getLogger(getClass())` (subclasses don't redeclare; log lines tag with the concrete class). Constructor takes a `PlatformTransactionManager` and builds an internal `TransactionTemplate(REQUIRES_NEW)`. `drain(int batchSize, String workerId, Consumer<S> advanceFn)` is `final`; subclasses pass an advance function rather than overriding an `advance(S)` method, so the base stays saga-state-only.

Two non-obvious rules baked into the base — preserve when adding a saga:

- **Each saga in a batch gets its own transaction.** `drain()` uses `TransactionTemplate.executeWithoutResult` per saga (`REQUIRES_NEW`), not one `@Transactional` over the whole loop. When the advance callback writes to the outbox and then throws, those writes must roll back so the retry doesn't double-emit.
- **The retry path reloads the saga before saving.** The advance callback may have mutated `saga.state()` etc. before throwing. After per-saga rollback the in-memory object still carries those mutations; saving them on retry persists a half-applied transition. So the catch block does `sagaPort.findBySagaId(saga.sagaId())` and applies `scheduleRetry` to the freshly loaded row.

Inbox handlers advance sagas too — the worker handles transitions where the saga has work to emit; the inbox handles transitions driven by inbound events. Both go through the same `sagaPort.save(saga)` so optimistic-concurrency keeps them coherent.

A startup-time `SagaInvariantsAutoConfiguration` validates that every concrete saga's CHECK constraint matches the states its worker / handlers can produce; failures abort startup with a clear delta.

**Only model states the code actually writes.** When a feature adds compensation states for one saga (e.g. cancel-order added `compensating`/`compensated` to sales + manufacturing), don't reflexively add them to other sagas' `ALL_STATES` "in case we need them later." The invariant checker reads `ALL_STATES` as code's claim of what it can produce — anything not in the DB CHECK fails boot. Purchasing's `PurchaseToPaySaga` had `compensating`/`compensated` listed defensively despite no transition code producing them; cleanest fix was removing the dead entries, not extending the schema CHECK to allow what nothing emits. (The two-phase cancel later made sales' own `compensating` unreachable as well — the cancel ack now advances straight to `compensated` — so `compensating` + `requestCompensation` were **removed** for exactly this reason; sales' `ALL_STATES` + the `saga_state` CHECK no longer list `compensating`.)

## Saga manager class shape — single source of truth per saga

Each saga has one source-of-truth class for its state machine; worker shells, inbox handlers, and shared emitters orbit around it but hold no transition logic of their own.

**File layout per saga:**

| Role | Location | Notes |
|---|---|---|
| Manager interface | `<service>.application.saga.<Flow>SagaManager` | Orchestration verbs only — lifecycle (`insertStarted`, `requestCompensation`), `drain`, and one `applyXxx` per inbox event |
| Manager impl | `<service>.infrastructure.saga.Jdbc<Flow>SagaManager` | Extends `SagaManager<S, P>` from `shared.application.saga` |
| Worker shell | `<service>.infrastructure.saga.<Flow>SagaWorker` | `@Component` with `@Scheduled poll()` + worker-driven `advance(saga)` |
| Inbox handler shells | `<service>.application.inbox.*Handler` | One per inbox event type, each its own `handler_name` |
| Cross-handler emitter | `<service>.application.<Flow>*Emitter` (or similar) | Shared cross-handler event emission. `application/`, NOT `application/inbox/` (inbox package is handler-only) |

**Manager dependencies are minimal.** Holds:
- `sagaPort` — typed as the concrete saga port via the abstract base's second type parameter, so saga-specific methods (e.g. `findBySalesOrderId`) are reachable without redeclaring the field on the subclass.
- `ObjectMapper json` for `saga.data` round-trip.
- `PlatformTransactionManager` (via super).

The manager **never** holds: `OutboxPort`, `JdbcTemplate`, projection ports for read-side echoes, repository ports for other aggregates in the same service, application services from this or any other context. Those collaborators belong with the side-effect owner (worker shell or inbox handler).

**Apply methods take saga-relevant primitives, not inbox payload types.** Acceptable arg types: `UUID`, `int`, `String`, `boolean`, `Map<X, Y>` — anything directly used by the saga state machine or in the saga's log lines. Inbox handlers parse the payload and pass primitives. This keeps the manager unaware of wire formats.

**Reference domain-event names via `<Event>.EVENT_TYPE`, never as a string literal.** Anywhere an inbox-event name surfaces inside a saga manager — the `eventName` arg to `requireSaga(...)`, `IllegalStateException` messages, log lines, anything user-facing or audit-visible — use the constant (`StockReserved.EVENT_TYPE`, `WorkOrderCreated.EVENT_TYPE`, `InventorySalesOrderCancellationApplied.EVENT_TYPE`, …). This makes the saga manager's domain-event dependency explicit at the import level: rename an event and the compiler points at every dependent manager. A hardcoded `"StockReserved"` string silently rots. The same rule applies elsewhere (inbox handlers, projection handlers, anywhere code names an event) — saga managers are just the most visible enforcement site.

**Reference aggregate-type strings via constants, never as a string literal.** The `aggregate_type` arg to `OutboxRow.pending(...)` (and the equivalent column in raw INSERT outbox writers) must come from a constant — never an inline `"SalesOrder"` literal. Two layers of constant exist:

1. **`<Service>AggregateTypes` in `<service>-events`** — single source of truth for every aggregate-type string the service produces. Example: `sales-events` hosts `SalesAggregateTypes.SALES_ORDER`, `SalesAggregateTypes.CUSTOMER`, `SalesAggregateTypes.SALES_ORDER_FULFILMENT_SAGA`. Cross-service consumers (consumer-side inbox-handler tests, cross-service event-class stamping) import directly from this class — the events jar is the only cross-service contract surface.
2. **`<AggregateRoot>.AGGREGATE_TYPE`** on the aggregate class itself, re-exporting the events-jar constant. Example: `SalesOrder.AGGREGATE_TYPE = SalesAggregateTypes.SALES_ORDER;`. Same-service outbox writers reference the aggregate-class field for call-site stability; that field is one indirection away from the wire constant.

Saga state-machine classes carry the constant the same way: a `public static final String AGGREGATE_TYPE` field re-exporting from `<Service>AggregateTypes.<SAGA_NAME>` (e.g. `SalesOrderFulfilmentSaga.AGGREGATE_TYPE = SalesAggregateTypes.SALES_ORDER_FULFILMENT_SAGA`). All three sagas declare the field for symmetry. **Whether anything stamps under it depends on whether the saga owns emissions**: `SalesOrderFulfilmentSaga` does — its worker stamps `StockReservationRequested` (+ `PrepaymentInvoiceRequested`), and its inbox handlers re-emit `StockReservationRequested` (retry) / `SalesOrderCancellationRequested` under this aggregate-type. `WorkOrderSaga` stamps its sole emission (`RawMaterialReservationRequested`) under `WorkOrder.AGGREGATE_TYPE` because the request belongs to the just-created WorkOrder's stream, and `PurchaseToPaySaga` has no worker emissions at all (all transitions are state-only or inbox-handler-driven against `PurchaseOrder` / `SupplierInvoice` / `Payment`). The constants on the latter two reserve a stable call site for any future self-originated commands.

**Cross-service stamping:** when an event in service A stamps an aggregate owned by service B, the event class sources its `AGGREGATE_TYPE` from B's `<Service>AggregateTypes` (and A's events POM depends on B's events jar). Live example: `ProductMaterialsCostComputed.AGGREGATE_TYPE = ProductAggregateTypes.PRODUCT` (manufacturing-events → product-events dep). (The former `ManufacturingDispatched.AGGREGATE_TYPE = SalesAggregateTypes.SALES_ORDER` example was retired with that event; the `*.ReplenishmentUndispatchable` events instead stamp inventory's `InventoryAggregateTypes.REPLENISHMENT_REQUEST` from their emit sites.) Same motivation as the EVENT_TYPE rule: the wire-format string lives in one place, the compiler points at dependent writers if it ever moves.

**Apply methods that can't find their saga must throw, not warn-and-return-null.** Use the `requireSaga(id, EVENT_TYPE)` helper for consistency — an orphan ack with no saga row is a genuine invariant violation that inbox redelivery / dead-letter handling should surface, not silently swallow. The only places `findByXxx(...).orElse(null)` is acceptable are programmatic / lifecycle entry points where a missing saga is a legitimate no-op (e.g. `cancelForWorkOrder` when the work order had no work-order saga to begin with).

**Apply methods return the saga's new state (`String`)** so callers gate side effects on it:
- `applyStockReserved` returns `"stock_reservation_incomplete"` → handler projects `markStatus(SO, "in_fulfilment")`
- `applyReplenishmentCancelled` returns `"rejected"` → handler projects `markStatus(SO, "rejected")` + emits `SalesOrderCancellationRequested`
- `applyShipmentPosted(SO, orderFullyShipped)` — the handler records the shipment on the `SalesOrder` aggregate FIRST (every shipment: accumulates per-line `shipped_quantity`, moves the header to `shipped`/`partially_shipped`, emits `SalesOrderShipped` → one finance invoice per shipment), then drives the saga on the aggregate's `orderFullyShipped` decision. **on_shipment partial shipments:** a partial shipment moves `ready_to_ship → partially_shipped` (and stays there across further partial shipments + their interim per-shipment invoices/payments, which are no-ops while parked); the shipment that completes the order moves `partially_shipped → goods_shipped`. A full single shipment goes straight to `goods_shipped`. For prepayment / cash-on-delivery (single-shipment terms — partial shipments out of scope) it ignores the flag and returns `"completed"` (the saga walks straight to terminal at shipment — invoice + payment already settled / auto-settled), and the handler additionally projects `markStatus(SO, "completed")`
- `applyCustomerPaymentReceived(SO, invoiceFullySettled, orderFullySettled)` — on_shipment completion is gated on **`orderFullySettled`** (every invoice for the order paid), NOT the per-invoice flag: a partially-shipped order has several per-shipment invoices, so paying one in full must not complete the order. Prepayment (`→ prepaid`) and deposit (`deposit_invoiced → deposit_paid`) are single-invoice and still use `invoiceFullySettled`. Finance computes `orderFullySettled` as `SUM(outstanding_amount)` over the order's invoices `== 0` (arithmetic, in `PaymentService`)
- `applyInventoryCancellationApplied` returns `"compensated"` (the sole compensation ack — manufacturing's was retired) → handler first calls `SalesOrderService.confirmCancellation` (flips the header to `cancelled`), then advances any non-terminal saga straight to `compensated` (a terminal saga — the `rejected`/unsourceable path, which also releases via this ack — is left untouched) and calls the compensation emitter
- Returns `null` for genuinely-no-op cases (sub-assembly skipped, no-saga warns)

**Worker shell holds worker-driven side effects** — `JdbcTemplate` for sales-order-line / similar reads, `OutboxPort` for command emission (e.g. StockReservationRequested), `ObjectMapper` for serde, plus its own `workerId` field. `@Scheduled poll()` is a one-liner: `manager.drain(BATCH_SIZE, workerId, this::advance)`. The `advance(saga)` method switches on `saga.state()`, reads the DB, builds + emits the command, transitions the saga in-place; the abstract base saves the saga after the callback returns.

**Worker identity is the worker shell's concern, not the manager's.** Worker holds its own `workerId` field — typically `"<saga-name>-worker@" + ManagementFactory.getRuntimeMXBean().getName()` — and passes to `manager.drain(...)`. Manager doesn't construct or store the id; the lease-claim SQL stamps it into `lease_owner` only via the drain parameter.

**Inbox handler holds inbox-driven side effects.** One per event type. Shape: `handle(envelope)` → dedupe (`inbox.alreadyProcessed`) → deserialise payload → extract primitives → `manager.applyXxx(primitives)` (returns `String`) → side effects gated on return value → `inbox.recordProcessed`. Side-effect collaborators (status projection, shipping service, compensation emitter, etc.) are constructor-injected on the handler.

**Shared cross-handler emission factored into a small service.** When two handlers emit the same event under different conditions, extract a one-method service in `<service>.application/` — NOT `application/inbox/` (the inbox package is handler-only by convention). Example: `SalesOrderCompensationEmitter.emitCompensated(salesOrderHeaderId)` called by both `InventoryCancellationAppliedHandler` and `ManufacturingCancellationAppliedHandler` when their respective `apply*CancellationApplied` returns `"compensated"`.

**Test rebalance.** Manager test asserts only saga state changes (state, current_step, saga.data). Side effects (projections, shipping calls, outbox emissions) are tested in handler tests where they live. Each handler test mocks the manager and verifies the dedupe → manager call → side-effect gating → recordProcessed shape; one or two transition assertions per side-effect branch is enough.

## Timed releases — park-and-wake, decide once

Some saga legs defer work to a wall-clock instant rather than to an inbound event: the prepayment/deposit legs park a day out as a liveness backstop, and the Sales-Order planning time fence parks until `need-by − fence` so a far-future order schedules its own fulfilment. All of them reuse one mechanism — `SagaInstance.parkUntil(Instant)` writes `next_retry_at`; the claim query (`Jdbc*SagaAdapter.claimDue`) re-selects the row once `next_retry_at <= now()`. No new scheduler; the existing `@Scheduled poll()` is the wake.

Two clocks are in play and they must not disagree:

- **Java clock** — the worker's `advance(...)` decides *whether* to park (`now < releaseAt`).
- **Postgres clock** — `claimDue`'s `next_retry_at <= now()` decides *when* the parked row wakes.

**Rule — decide once; the wake does not re-evaluate the deadline.** The advance handler for a timed-wait state (`awaiting_release`, etc.) must emit its command **unconditionally** — being claimed out of that state already means the deadline passed (Postgres said so). Re-checking `now < releaseAt` on wake creates a two-clock trap: if the entry decision used the Java clock but the row was woken by the Postgres clock, a slightly-behind Java clock re-parks the saga forever — a poll-loop in prod and a hang in tests. The park *is* the decision; the deadline lives in `next_retry_at`, evaluated by exactly one clock thereafter.

Concretely, the entry state (`started` / `prepaid`) evaluates the gate **once** — `releaseAt = needBy − maxFence`; `now ≥ releaseAt` → emit immediately, else `parkUntil(releaseAt)` + transition to the wait state. The wait state's advance branch just emits. One `now` read, at entry.

**Clock seam for testability.** The worker reads `now` through an injected `java.time.InstantSource` (Spring auto-provides one; tests pass a fixed source), never a bare `Instant.now()` at the gate. This makes the park-vs-emit decision a pure unit test:

- `needBy = now+30d, fence=7` → `releaseAt = now+23d` → asserts transition to the wait state + `parkUntil(now+23d)`. Microseconds — no real waiting.
- `needBy = now+3d, fence=7` → `releaseAt = now−4d` → asserts the command is emitted immediately.

**Testing the wake — fast-forward the timestamp, never the wall clock.** Because the deadline is a stored `next_retry_at`, the persistence IT proves the wake by making the row due, not by sleeping: park the saga, assert `drainOnce()` is a no-op (`next_retry_at > now()`), then `UPDATE … SET next_retry_at = now() - interval '1 second'`, `drainOnce()` again, and assert the command was emitted and the saga advanced. A multi-day fence is exercised in milliseconds. The decide-once rule is what makes this work with a single `UPDATE` — if the wake re-checked the Java clock, the IT would also have to advance the injected `InstantSource` in lockstep, reintroducing the two-clock coupling the rule exists to remove.

## Saga observability — milestone overview

A saga's lifecycle is spread across many traces — each event-driven hop is its own bounded trace (`docs/observability.md` → *Saga-trace linkage*) and the worker advances run on their own poll traces. To see a saga **end to end** you query a *milestone overview* rather than reconstruct one giant trace.

**Mechanism — small-footprint, centralized in the adapter.** `SagaInstance.transitionTo(...)` sets a transient `stateAdvanced` flag; each `Jdbc*SagaAdapter` consumes it on `update()` (and records unconditionally on `insert()` — creation is the first milestone). When the state actually advanced it records a **milestone span** via `shared.application.saga.SagaMilestone`: a standalone span (`setNoParent`, its own one-span trace) named after the state entered (`saga.<state>`), tagged `northwood.saga_id` + `northwood.saga_type` (+ `northwood.sales_order_id` cross-saga key when the saga carries an originating order), and `addLink`-ed to the trace current at the transition — the triggering action/event detail. Data-only updates and retry reschedules (no `transitionTo`) record nothing; NOOP-safe when no tracer is wired. **No manager or worker code changed** — the adapter is the single choke point every transition already flows through, so the footprint is one boolean field + three adapter call-sites.

**Why no saga-root span.** Anchoring on the durable `saga_id` (a column on the saga row) rather than a long-lived root traceId keeps "find everything for this saga" independent of Tempo's block retention — a root trace spanning the whole saga (minutes-to-hours, across restarts) would age out and isn't OTel-idiomatic.

**Why a dedicated attribute, not `correlation_id`.** The cross-saga key is the **originating sales-order id** — constant across the SO saga and the WO/PO sub-sagas it triggers — stamped as the dedicated span attribute `northwood.sales_order_id`, sourced from the saga rows (`SalesOrderFulfilmentSaga.salesOrderId()`, `WorkOrderSaga.salesOrderHeaderId()`). It is deliberately **not** the messaging `correlation_id` (left free for its conventional conversation-root use) and **not** the per-saga `saga_id` (the saga instance PK, which differs at every saga boundary and so can't correlate across sagas).

**Viewing it (Tempo TraceQL):**
- `{ .northwood.saga_id = "<saga uuid>" }` — one saga's milestone timeline; each milestone links to its detail trace.
- `{ .northwood.sales_order_id = "<sales-order uuid>" }` — the order plus the WO sub-saga it triggered.

The cross-saga key flows on the **SO→WO** path because the WO saga row carries `sales_order_header_id` for SO-shortage-driven work orders (null for pool/reorder WOs — correctly uncorrelated to any order, per *Why the Sales Order Fulfilment Saga does not drive manufacturing* above). **SO→PO is a deferred follow-up:** purchasing's `purchase_to_pay_saga` is keyed by PO and doesn't carry the originating order, so PO-saga milestones currently omit `sales_order_id`.
