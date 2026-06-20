# Saga compensation — making it real and reachable

> **Status:** **SHIPPED 2026-06-20** (branch `feature/multi-leg-saga-compensation`).
> Multi-leg compensation is implemented and reachable for both `to_order` product
> types — cancelling a make-to-buy line withdraws its committed purchase order, and a
> make-to-order line withdraws its released work order, before declaring the order
> `compensated`. The mechanism below is the as-built design (it was the spec; the
> handful of refinements found during implementation are noted inline). The only
> remaining future work is the liveness **timeout sweeper** (see *Shipped* → *Deferred*).
>
> This note still reads as the rationale + design for the capability; the
> *Current state* section describes the pre-Slice-1 baseline it was built on.

---

## Why this note exists

Across the three Northwood sagas, compensation is effectively dead/vestigial: the
sales-order `compensating` state was unreachable and is now removed, and the only
genuine undo on a cancel today is inventory releasing the reservation (plus a
finance-side deposit refund). For a system whose whole point is to *showcase*
event-driven orchestration, "compensation can't be ignored" — it needs to be a
real, reachable, demonstrable capability rather than a state label that flips
without orchestrating anything.

This note answers: **where would a real compensation actually happen, and how
would the saga wait for the multiple acks it implies?**

---

## Current state on `main`

The cancel path is **two-phase, inventory-arbitrated** (closes a concurrent
cancel-vs-ship race):

1. `SalesOrderService.cancel(...)` calls `SalesOrder.requestCancellation(...)`,
   which emits `sales.SalesOrderCancellationRequested` to the outbox. **The order
   status is not changed here.**
2. Inventory's `SalesOrderCancellationRequestedHandler` claims cancellation on its
   `sales_order_line_facts` (mark every not-yet-shipped line cancelled; cancellable
   iff no line has shipped). The ship-claim path row-locks the same rows with
   `AND NOT cancelled`, so whichever commits first wins.
3. If cancel wins, inventory emits `InventorySalesOrderCancellationApplied`.
   Sales' `InventoryCancellationAppliedHandler` calls
   `SalesOrderService.confirmCancellation(...)`, which flips the header to
   `cancelled` and advances the saga **directly to the `compensated` terminal**
   (no intermediate `compensating` hop).
4. If a shipment wins the race, no ack arrives, so sales never confirms and the
   order stays shipped — and this resolves **silently**: the cancel command already
   returned `200 OK` at submit time (the request was accepted *before* inventory
   arbitrated), and nothing notifies the user the cancellation later lost. The
   synchronous `OrderNotCancellable` (409) is raised *only* when sales already sees
   the order terminal or a line shipped at submit time
   (`isTerminal() || anyLineShipped()` in `SalesOrder.requestCancellation`, mapped via
   the application `OrderNotCancellableException extends ConflictException`) — i.e. a
   *late* cancel, not the in-flight race. So the race-loser path has **no** end-user
   feedback that the cancel failed; surfacing it would need an async notification or
   the SPA re-reading the order status (the `cancellation_requested_at` follow-up
   below makes the *pending* window visible but does not signal *failure* — the
   `cancelling` label simply resolves to `shipped`).

Two real undos happen, **both as event choreography, not saga orchestration**:

| Undo arm | Trigger | Where |
|---|---|---|
| Release the stock reservation | `sales.SalesOrderCancellationRequested` | `inventory.SalesOrderCancellationRequestedHandler` |
| Refund a paid deposit/prepayment (Dr 2110 Customer Deposits / Cr 1000 Bank) | the **confirmed terminal** — `sales.SalesOrderCompensated` / `SalesOrderCompensationFailed` / `SalesOrderRejected` | `CustomerCancellationRefundService` (via three thin handlers) |

> **Refund trigger** — the refund keys on the *confirmed* terminal, not the cancel
> *request*. Earlier it fired on `SalesOrderCancellationRequested`; once the cancel
> went two-phase, that request fires before inventory arbitrates cancel-vs-ship, so a
> cancel that *lost* the race would be refunded **and** shipped. Repointed to the
> confirmed terminals (see *Finance consequences* below) — a structural fix.

The internal **reject** path (`SalesOrder.reject(...)`, fired when a short line's
replenishment is cancelled — unsourceable SKU / no active BOM / no approved vendor)
emits `SalesOrderCancellationRequested` (→ inventory release) **and** the confirmed
`SalesOrderRejected` (→ refund), so a prepaid-then-rejected order both refunds and
releases. A reject is always safe to refund — a rejected order was never reserved, so
it can never ship.

**What was removed (already on `main`):** `JdbcSalesOrderFulfilmentSagaManager#requestCompensation`,
the `SalesOrderFulfilmentSagaManager.requestCompensation` interface method, and the
`compensating` saga state. The saga now jumps from its active state straight to
`compensated` on the single inventory ack (`FulfilmentSagaData.inventoryCancellationAcked`,
a single boolean latch).

**Diagram note:** `docs/system-map.html`'s fulfilment-saga `stateDiagram-v2` is a
*deliberately pruned forward-flow view* — it has never depicted a `compensating`
state. So its compensation-free shape is a documentation choice, not evidence about
reachability; don't read it as the spec for this work.

---

## Why "shipped then cancel" is **not** the case

In the two-phase model, shipment is the arbiter and the **point of no return**. If a
shipment wins, the cancel is *refused* (no ack, order stays shipped). To make
shipped-then-cancel a compensation you'd have to reverse physical + financial facts
already committed: return-to-stock, reverse the invoice / deferred revenue, refund
the payment. That is the **returns / credit-note (RMA) process** — explicitly out of
scope, and conceptually a *new forward business process* (a return has its own
approval, inspection, restock-or-scrap decision, credit note), not a saga rewind.
Saga compensation is for rewinding *in-flight, not-yet-physically-committed* steps.
So shipped-then-cancel is correctly a separate process, not compensation.

---

## The one case where a **real** compensation belongs

> **Cancel (or internal reject) of a `to_order` line whose order-pegged
> replenishment has already become a committed purchase order (sent to a supplier)
> or a released work order (materials issued to WIP) — but has not yet shipped.**

At the moment a cancel/reject lands at `stock_reservation_incomplete` /
`supply_secured`, the committed side-effects are:

1. deposit taken → cash parked in finance → **refunded** ✅
2. coverable lines reserved → **released** ✅
3. **order-pegged replenishment raised a `PurchaseRequisition`/PO or released a
   `WorkOrder`** — *was* orphaned; **now withdrawn** ✅ (Slices 2–3). This was the
   gap this work closed: purchasing/manufacturing now consume the cancel and roll
   back the committed PO / released WO.

That third arm is what makes compensation real and multi-step:

- it must **fan out undo to purchasing/manufacturing and wait for several acks**
  before declaring `compensated` (today's single-ack jump is fine for one undo,
  wrong for three);
- it has an **un-compensatable leaf**: a PO the supplier already dispatched, or a
  work order that already consumed material → the undo is itself a business
  transaction (goods-receipt-and-return, or scrap WIP with a loss posted to GL) and
  may *fail*, needing escalation rather than a silent `compensated`;
- it must **discriminate pegged vs. unpegged**: only `to_order` (order-pegged)
  replenishment may be cancelled. A `to_stock` top-up PO must **stay** (its stock
  belongs to the general pool, not this order) — compensating it would be a bug.

This is the scenario that earns a multi-ack `compensating` state, and it is both
where compensation *should* happen and where it *currently doesn't*.

---

## Mechanism — how `compensating` waits for multiple acks

The fulfilment saga already implements the "wait for N acks" join **twice** in
`sales-service/.../domain/saga/FulfilmentSagaData.java`. Multi-ack compensation is a
*third instance of an existing pattern*, not new machinery.

**Existing join 1 — set-drain** (`outstandingReplenishmentLineIds`):
`withReplenishmentLineFulfilled(lineId, pegged)` removes one entry per arriving
`inventory.ReplenishmentFulfilled`; `allReplenishmentLinesFulfilled()` fires the
transition when the set empties.

**Existing join 2 — flag-meet** (the completion gate): `orderShipped` + `orderSettled`
independent boolean latches set by different events; `isReadyToComplete()` fires when
both are true.

**Today's compensation is the degenerate one-ack case:** a single latch
`inventoryCancellationAcked` (`withInventoryCancellationAcked()` →
`cancellationAcked()`). Multi-ack compensation generalises that latch back into a
**set-drain join** (legs vary per order — one PO/WO leg per pegged line, plus
reservation and refund):

```java
// in FulfilmentSagaData — mirrors outstandingReplenishmentLineIds exactly
Set<String> outstandingCompensationLegs   // e.g. {"RESERVATION", "REFUND", "PO:<lineId>", "WO:<lineId>"}

FulfilmentSagaData withCompensationLegAcked(String legId) {
    if (!outstandingCompensationLegs.contains(legId)) return this;   // idempotent — same guard as withReplenishmentLineFulfilled
    var next = new LinkedHashSet<>(outstandingCompensationLegs);
    next.remove(legId);
    return new FulfilmentSagaData(/* … */ next /* … */);
}
boolean allCompensationLegsAcked() { return outstandingCompensationLegs.isEmpty(); }
```

**Entry into `compensating`** (replacing the straight-to-`compensated` jump): on
cancel/reject, compute which committed side-effects exist for this order, stamp them
as the outstanding-leg set, emit one compensation-request event per leg to the
outbox, and transition to `compensating`.

**Each ack** is an ordinary inbox event — `InventorySalesOrderCancellationApplied`, a
new `PurchaseOrderCancellationApplied`, `WorkOrderCancellationApplied`,
`RefundPosted` — whose handler calls:

```java
String applyCompensationAck(UUID orderId, String legId) {
    var saga = requireSaga(orderId, ...);
    var data = readData(saga).withCompensationLegAcked(legId);
    if (data.allCompensationLegsAcked()) saga.transitionTo(COMPENSATED, "all-legs-acked");
    saga.withData(write(data));
    sagaPort.update(saga);
    return saga.state();
}
```

The saga **parks** in `compensating` (a non-terminal state, so the drain keeps
servicing it) until the last leg's ack empties the set.

### Why it's correct — all four properties are already demonstrated in the file

- **At-least-once / redelivery:** the `if (!set.contains(legId)) return this;` guard
  makes a duplicate ack a no-op — exactly how `withReplenishmentLineFulfilled`
  handles `ReplenishmentFulfilled` redelivery.
- **Out-of-order arrival:** set-removal is commutative; no leg sequence is assumed —
  the same race-tolerance the completion gate relies on (its two events carry
  different partition keys and can arrive in either order).
- **Concurrent acks for one order:** each `applyCompensationAck` is `@Transactional`,
  and the saga row + inbox dedup (advisory-xact-lock) serialise concurrent acks on
  the same `sales_order_header_id`.
- **No new infrastructure:** it's the JSON `data` blob updated and
  `sagaPort.update(saga)` in the same tx that records the inbox event — like the two
  existing joins. No extra table.

### The two bits that make it a showcase (not just a counter)

1. **Un-compensatable leaf → distinct terminal.** A leg can reply with a *failure*
   ack (`PurchaseOrderCancellationRefused` because the supplier already dispatched).
   Don't let that silently count as done. Carry a parallel `failedCompensationLegs`
   set; when `outstandingCompensationLegs` empties, branch: empty failures →
   `compensated`; non-empty → a separate terminal (e.g. `compensation_failed` /
   `needs_manual_intervention`) that raises an alert and/or opens a return (RMA).
   That is compensation that can *partially fail and escalate* — the part today's
   design cannot express.

2. **Liveness / timeout.** An ack that never arrives (downstream service down) would
   park the saga in `compensating` forever. The outbox gives at-least-once
   *emission*; for at-least-once *completion* add a sweeper that re-emits the
   still-outstanding compensation-request events after a timeout (idempotent on the
   receiver, same as the forward path's loss-tolerance).

---

## Shipped — what was built

Three increments on `feature/multi-leg-saga-compensation`, all `mvn install`-green with
DSL acceptance tests for both legs:

- **Sales-side machinery + states.** Re-introduced `compensating`
  (non-terminal) and added `compensation_failed` (terminal) to
  `SalesOrderFulfilmentSaga.ALL_STATES` **and** the `sales.sales_order_fulfilment_saga`
  `saga_state` CHECK in lockstep (boot-time `SagaStateInvariantChecker`; Liquibase is
  disabled so the baseline `config/postgresql/northwood_erp.sql` is edited directly,
  and the live DB needs an `ALTER`). Generalised the single `inventoryCancellationAcked`
  boolean into the `outstandingCompensationLegs` / `failedCompensationLegs` set-drain on
  `FulfilmentSagaData` (helpers exactly as sketched above), plus
  `applyInventoryCancellationApplied(orderId, legIds)` (empty → `compensated`, the
  common path; non-empty → park in `compensating`) and `applyCompensationAck(orderId,
  legId, failed)`.
- **The purchasing PO leg + the manufacturing WO leg.** New
  compensation-request + ack event pairs: `inventory.OrderPeggedSupplyCancellationRequested`
  (one fan-out event, consumed by both services on `targetService`),
  `purchasing.PurchaseOrderCancellationApplied`, `manufacturing.WorkOrderCancellationApplied`,
  and the escalation `sales.SalesOrderCompensationFailed`. `PurchaseOrder.compensateCancel`
  (DRAFT/SENT → cancel; received+ → refuse) and `WorkOrder.cancel` (rebuilt; RELEASED →
  cancel; in-progress+ → refuse) are the predicate-guarded, idempotent leaf cancels;
  each owning service terminates its own sub-saga (P2P / work-order, both gaining a
  `cancelled` terminal where needed) and the WO cancel releases its reserved raw
  materials via `manufacturing.WorkOrderCancelled` → inventory.

**Leg-id scheme (refinement).** Leg ids are `"<targetService>:<salesOrderLineId>"`
(`purchasing:…` / `manufacturing:…`) — reusing the existing `target_service`
vocabulary rather than a separate `PO:` / `WO:` prefix. Inventory enumerates the legs
on the cancellation-applied ack; each service's ack handler forms the same id.

**Topology decision.** The sales saga is the visible orchestrator (it holds the legs,
drains the acks, branches to the terminal), but **inventory fans out** the per-leg
cancel-requests because it owns the peg→PO/WO map (`replenishment_request`); sales
can't read another service's schema. Only `DISPATCHED` order-pegged (`to_order`)
replenishments become legs — `to_stock` top-ups are never compensated; a `REQUESTED`
(not-yet-dispatched) or `FULFILLED` (already received) replenishment produces no leg.

**The cancel-vs-reject collision (found while building the purchasing leg).** Dropping a `DISPATCHED`
replenishment emits `inventory.ReplenishmentCancelled`, which the sales
`ReplenishmentCancelledHandler` would read as *unsourceable → reject*, racing the saga
to `rejected` ahead of the compensation acks. Guarded: that handler skips the reject
when the order is already being cancelled (`SalesOrder.cancellationRequestedAt != null`,
stamped + persisted before any event dispatches). Reject-parity then falls out for
free — a reject is *triggered by* an unsourceable/cancelled replenishment, so a
rejected line never carries a `DISPATCHED` leg and takes the zero-leg path.

### Finance consequences

A compensated / rejected order is always **pre-shipment**, so its *only* general-ledger
footprint is a paid up-front **deposit or prepayment** parked in 2110 Customer Deposits.
Revenue and COGS are recognised at shipment, which a cancellable/rejected order never
reached — so there is **nothing on revenue/COGS/inventory to reverse**.

- **The refund.** If the order's up-front invoice was paid, `CustomerCancellationRefundService`
  posts the inverse of the original receipt — **`Dr 2110 Customer Deposits / Cr 1000 Bank`**
  for the paid amount (`SourceDocumentType = CUSTOMER_REFUND`), stamping
  `customer_invoice_header.refunded_at`. Across the original deposit receipt
  (`Dr 1000 / Cr 2110`) and this refund, **2110 nets to zero** for the order.
- **One posting, three triggers.** The journal is *identical* whether the order reached
  `compensated`, `compensation_failed`, or `rejected` — they answer the same question
  ("return the deposit because the order terminated without shipping"). Exactly one of
  the three confirmed terminals fires per order; `refunded_at` is the idempotency
  backstop. It fires on the **confirmed** terminal, never the cancel request (the fix
  that closes the refund-then-ship race above).
- **No posting when there's nothing parked.** On-shipment / COD orders (no pre-shipment
  invoice), an unpaid up-front invoice, and an already-refunded order all no-op.
- **`compensation_failed` posts nothing extra for the un-withdrawn leaf.** A PO already
  received or a WO in progress that couldn't be withdrawn is an **ops escalation** — a
  goods-receipt-and-return (RMA) or a scrap-WIP write-off (`Dr 5600 Inventory Write-off /
  Cr 1220`, a separate forward process) — explicitly out of scope here. From finance's
  view a `compensation_failed` order refunds its deposit exactly like a clean
  `compensated` one; the residue is someone else's transaction.

---

## Shipped — cancel pending-window, observable outcome + optimistic UI

The cancel-outcome UX scoped in the follow-ups that used to live here is **implemented**
(and removed from this note): `cancellation_requested_at` + an idempotent
`requestCancellation` (exactly one event per cancel, however many times the user clicks);
the derived `none`/`cancelling`/`cancelled`/`cancellation_rejected` outcome on
`SalesOrder` + the sales `SalesOrderView`; the cancel endpoint returning `202 Accepted`;
and the optimistic cancel UI (a durable "Cancellation requested…" pill that polls to
reconcile to a terminal). The **derive-only** decision held — no extra event or column,
because a cancel can only lose to a shipment that sales sees, so the outcome is derivable
from `(cancellation_requested_at, status)`. Only the **multi-leg `compensating` rebuild**
above remains future work.

---

## Resolved design questions

1. **PO/WO cancellation cutoff.** PO: `draft`/`sent` cancellable, `partially_received`
   and beyond refuse. WO: `released` cancellable, `in_progress` and beyond refuse.
   Each refusal is a typed domain exception (`PoNotCompensatableException` /
   `WoNotCompensatableException`) the handler maps to a failure ack — never a wedge.
2. **Failed-leaf remediation.** `compensation_failed` emits
   `sales.SalesOrderCompensationFailed` as an **escalation signal** (open an RMA /
   post a scrap write-off) — the order is still cancelled cleanly. The remediation
   process itself (returns/RMA, scrap-WIP-with-GL-loss) stays out of scope, exactly as
   `failed` vs `compensation_failed` is documented: a business outcome needing a human,
   not a broken saga.
3. **Scope.** Both legs shipped (purchasing + manufacturing).
4. **Reject vs. cancel parity.** Resolved structurally — a reject is *triggered by* an
   unsourceable/cancelled replenishment, so a rejected line never carries a dispatched
   PO/WO leg; it takes the zero-leg path to `compensated`. No separate reject fan-out
   needed (see *Shipped* → the cancel-vs-reject collision).
5. **Reachability tests.** `OrderToCashCompensatePurchasedLegDslTest` and
   `OrderToCashCompensateManufacturedLegDslTest` (test-harness `o2c`) — each places a
   `to_order` line, lets it raise a real PO/WO, cancels before shipment, and asserts the
   artifact is withdrawn and the saga reaches `compensated`. The `compensation_failed`
   branch is covered at the unit tier (the domain refusal + the saga-manager
   `applyCompensationAck` test), since the natural E2E rarely hits a received-PO /
   in-progress-WO while the replenishment is still `DISPATCHED`.

---

## Pointers

- `sales-service/.../domain/saga/FulfilmentSagaData.java` — the two existing join
  patterns + the single-ack latch to generalise.
- `sales-service/.../domain/saga/SalesOrderFulfilmentSaga.java` — state constants,
  `ALL_STATES`, `TERMINAL_STATES`.
- `sales-service/.../infrastructure/saga/JdbcSalesOrderFulfilmentSagaManager.java` —
  the `apply*` transition methods (the existing `applyReplenishment*` are the join
  template).
- `sales-service/.../application/inbox/InventoryCancellationAppliedHandler.java`,
  `inventory.SalesOrderCancellationRequestedHandler` — the cancel/undo arms;
  `finance.CustomerCancellationRefundService` (+ the `SalesOrderCompensated` /
  `SalesOrderCompensationFailed` / `SalesOrderRejected` refund handlers) — the
  deposit-refund arm (see *Finance consequences*).
- `docs/sagas.md` — saga base, manager/worker/handler/emitter split.
- `docs/messaging.md` — outbox→Kafka→inbox, partition keys, idempotency.
- `docs/validations.md` — self-contained-validation register (read before adding the
  PO/WO cancellation guards).
- `docs/composed-state-machines.html` — the line-fold vs fulfilment-chain rollup
  (why ship/invoice/pay status is owned by the fold + 360, not the saga).
