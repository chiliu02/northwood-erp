# Saga compensation — making it real and reachable

> **Status:** design note / starting point for a future work session. Captures why
> sales-order compensation is currently vestigial, the one scenario where a *real*
> multi-step compensation belongs, and a concrete mechanism for it grounded in the
> machinery the fulfilment saga already has.
>
> **Base:** `main` (the dead `requestCompensation` + `compensating` state have
> already been removed — see *Current state* below). Nothing here is implemented
> yet.

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
| Refund a paid deposit/prepayment (Dr 2110 Customer Deposits / Cr 1000 Bank) | `sales.SalesOrderCancellationRequested` | `finance.SalesOrderCancellationRefundHandler` |

The internal **reject** path (`SalesOrder.reject(...)`, fired when a short line's
replenishment is cancelled — unsourceable SKU / no active BOM / no approved vendor)
emits the *same* `SalesOrderCancellationRequested`, so a prepaid-then-rejected
order both refunds and releases. No gap there.

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

1. deposit taken → cash parked in finance → **refunded today** ✅
2. coverable lines reserved → **released today** ✅
3. **order-pegged replenishment raised a `PurchaseRequisition`/PO or released a
   `WorkOrder` → nothing undoes it** ❌. No purchasing/manufacturing handler
   consumes the cancel today, so that PO/WO is **orphaned**.

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

## Wiring cost / what to build

- **Re-introduce the `compensating` state**: add it to
  `SalesOrderFulfilmentSaga.ALL_STATES` **and** the DB `CHECK` set on
  `sales.sales_order_fulfilment_saga.saga_state` **together** — the boot-time
  `SagaStateInvariantChecker` fails fast if the two diverge. `compensating` is
  non-terminal (drain keeps processing it); `compensated` and any
  `compensation_failed` are terminal.
  - Note: Liquibase is disabled in this repo — schema/CHECK changes edit the
    baseline `config/postgresql/northwood_erp.sql`, and the live AWS DB (demo data,
    no `down -v`) needs an `ALTER` to widen the CHECK set.
- **Generalise the ack latch**: replace the single `inventoryCancellationAcked`
  boolean in `FulfilmentSagaData` with the `outstandingCompensationLegs` /
  `failedCompensationLegs` set-drain helpers above; add `applyCompensationAck` to
  the saga manager.
- **New compensation-request/ack event pairs** on the purchasing and manufacturing
  `*-events` jars, with consumers on those services — the legs that **do not exist
  today** (currently nothing rolls back a pegged PO or released work order). Follow
  the event conventions: `EVENT_TYPE` constants, wire-format status constants on the
  producer's `*-events` jar, partition-key choice per `docs/messaging.md`.
- **Pegged-vs-unpegged discrimination**: only order-pegged (`to_order`)
  replenishment legs are added to the outstanding set; `to_stock` top-ups are never
  compensated. The peg information is known at the point the saga records the short
  line (`applyStockReserved` / the replenishment tracking).
- **Validation / idempotency**: per `docs/validations.md`, validate up-front at every
  layer; the compensation-cancel on a PO/WO must be predicate-guarded /
  effect-gated, not eventId-dedup alone.

---

## Follow-up — surface the cancel window with `cancellation_requested_at`

Independent of the multi-leg compensation rebuild, the two-phase cancel has a gap
worth closing in the same area: **there is no in-system signal that a cancellation
is pending.**

**The problem.** `SalesOrder.requestCancellation(...)` emits
`SalesOrderCancellationRequested` but does **not** change the header status (it stays
on its pre-ship fold rung — `open`/`partially_reserved`/`reserved`), set
`cancelledAt`, or record that a request is outstanding. Two consequences:

1. **No user feedback.** A user who clicks *Cancel* sees the order unchanged
   (still `reserved`), so they can't tell the click registered.
2. **No idempotency → event amplification.** Because the only guard is
   `isTerminal() || anyLineShipped()` (neither true during the window) and nothing
   records "a request is already pending", **repeat clicks each append another
   `SalesOrderCancellationRequested`** (fresh `eventId`). N clicks → N request events
   → N inventory release transactions → N `InventorySalesOrderCancellationApplied`
   acks → N finance-refund handler runs. The end state stays correct *only* because
   every consumer is idempotent today (stock release keys on the active reservation —
   no over-release; finance has the `refunded_at` gate; sales `confirmCancellation`
   is a terminal no-op), but it leans entirely on that universal idempotency holding —
   the fragile posture `docs/validations.md` warns against.

**The fix — one small field, triple duty.** Add a `cancellation_requested_at`
timestamp on `sales_order_header` (mirror of `cancelled_at`), stamped by
`requestCancellation()`:

```java
public void requestCancellation(String reason) {
    if (isTerminal() || anyLineShipped()) throw new OrderNotCancellableException(id, status);
    if (cancellationRequestedAt != null) return;          // already pending → idempotent no-op, no 2nd event
    this.cancellationRequestedAt = Instant.now();
    this.pendingEvents.add(new SalesOrderCancellationRequested(...));
}
```

That single field:
- **(a) Visibility.** Drives a derived `cancelling` label in reporting's
  `sales_order_360_view.order_status` (the deliberately-separate third vocabulary):
  `cancellation_requested_at IS NOT NULL AND order_status NOT IN (cancelled, shipped, …)
  → 'cancelling'`. When either outcome resolves (cancel confirmed → `cancelled`;
  ship wins → `shipped`) the predicate stops matching and the label disappears — no
  race-loser cleanup logic.
- **(b) Idempotency at the source.** A 2nd click emits **no** event and returns
  success (HTTP 200 "cancellation pending"), not an error — killing the amplification
  rather than relying on N-fold downstream idempotency.
- **(c) Monotonic / race-survivable.** Set-once; if a shipment wins the race, the
  stale timestamp is harmless (the `anyLineShipped()` guard fires first; the
  `cancelling` label predicate excludes shipped).

**Why this is NOT a new status.** Deliberately a *timestamp + derived 360 label*, not
a rung in `SalesOrder.Status` (or `LineStatus`). The header status is, by design,
either a faithful fold over the lines (`recomputeStatus()`) or an **absorbing**
order-level terminal. A `cancelling` rung is neither: it's not derivable from the
lines, and it's **non-absorbing** — it must be able to revert if a shipment wins, and
there is **no event telling sales "your cancel lost"** (sales only infers it from a
later `ShipmentPosted`). So a status rung would need bespoke non-monotonic
"stay-or-fall-back" logic and could strand the aggregate in `cancelling` forever on
the race-loser path. The line precedent agrees: line cancellation is an overlay the
fold *skips*, not a ship-band. Keep the aggregate's "header = faithful fold +
absorbing terminals" invariant intact; surface the pending window as a derived label.

**Companion UI fix.** The primary user-facing remedy is still client-side: disable the
*Cancel* button on click and show an optimistic "Cancelling…" state. The
`cancellation_requested_at` field is the server-side backstop (idempotency + the label
the UI can render).

**Scope note.** This is a small, self-contained change that does **not** require the
multi-leg `compensating` work — it can ship on its own. It's filed here because it
lives in the same two-phase-cancel surface.

---

## Open questions for the work session

1. **PO/WO cancellation semantics.** What states of a `PurchaseRequisition`/PO and a
   `WorkOrder` are still cancellable, and what is the firm-commitment cutoff that
   makes a leg un-compensatable? (Supplier-confirmed PO; WIP material already issued
   or operations started.)
2. **Where the failed-leaf remediation lives.** Does `compensation_failed` open a
   return/RMA, a purchasing GRN-and-return, or a manufacturing scrap with a GL loss
   posting — and is that orchestrated by the same saga or handed to a new one?
3. **Scope.** Is the purchasing leg alone enough for a convincing showcase
   (make-to-buy `to_order`), or must the manufacturing (work-order) leg ship too?
4. **Reject vs. cancel parity.** The internal reject path should share the same
   multi-leg compensation as a user cancel (both already emit
   `SalesOrderCancellationRequested`); confirm the pegged-PO/WO undo fires on reject.
5. **Reachability test.** An acceptance test (`o2c` DSL) that places a `to_order`
   line, lets it raise a real PO/WO, cancels before shipment, and asserts the PO/WO
   is withdrawn and the saga reaches `compensated` — the demonstrable proof that
   compensation is reachable.

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
  `inventory.SalesOrderCancellationRequestedHandler`,
  `finance.SalesOrderCancellationRefundHandler` — the current cancel/undo arms.
- `docs/sagas.md` — saga base, manager/worker/handler/emitter split.
- `docs/messaging.md` — outbox→Kafka→inbox, partition keys, idempotency.
- `docs/validations.md` — self-contained-validation register (read before adding the
  PO/WO cancellation guards).
- `docs/composed-state-machines.html` — the line-fold vs fulfilment-chain rollup
  (why ship/invoice/pay status is owned by the fold + 360, not the saga).
