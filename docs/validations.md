# Validations — self-contained invariants

How Northwood validates **self-contained** invariants: conditions checkable from the
incoming request plus the target aggregate's own state, with **no cross-service
lookup**. Header-total-equals-line-sum, positive amounts, status preconditions,
quantity bounds, double-entry balance — all of these can and must be decided locally.

This doc is both the **convention** (how/where to validate) and a **register** of
what's enforced today plus the known gaps.

## Why up-front, at every layer

A self-contained invariant must be validated **before** a write transaction opens,
and re-checked as a backstop deeper in. Relying on a database `CHECK` constraint as
the *only* guard is the anti-pattern: a CHECK surfaces as an opaque
`DataIntegrityViolationException` (Postgres SQLSTATE `23514`) at write time, which —
on an event-driven path — rolls back the handler transaction, wedges the saga, and
loops the DLT redrive. The constraint is a last-line backstop for "should never
happen," not the place to reject a knowable-bad request.

The motivating case: a purchase order whose denormalised header `total_amount` had
drifted to `0` (supplier price defaulted to 0, then a line was priced by hand without
recomputing the header). Nothing validated `total == Σ lines` up front, so the first
real supplier invoice tripped `CHECK (invoiced_amount <= total_amount)` at projection
time — a wedge + 5× DLT redrive + phantom saga-milestone traces, instead of a clean
"this PO isn't approvable" at the edge.

### The three enforcement points

| Layer | Role | Example |
|---|---|---|
| **api/** | Fail fast — reject before a write transaction is opened (a read-only pre-check is fine). | `PurchaseOrderController.approve` → 404 then `assertApprovable` (409) before `approve` |
| **application/** | Orchestrate; expose a read-only validation; gate side effects on real state. | `PurchaseOrderService.assertApprovable` (read-only); advance saga only when PO reached `sent` |
| **domain/** | The authoritative invariant — pure, no I/O, reused by the layers above. | `PurchaseOrder.assertApprovable()` |

A single pure-read domain method (no mutation, no events) is the source of truth; the
API calls it pre-transaction, the domain mutator calls it as the in-transaction
backstop.

## Categories of self-contained invariant

1. **Aggregate consistency** — denormalised header equals the lines: `total == subtotal + tax`, `subtotal == Σ line totals`, `tax == Σ line tax`; double-entry `Σ debits == Σ credits`.
2. **Value guards** — positivity (`total > 0` where a zero is almost always a pricing miss), non-empty line collections, non-negative quantities.
3. **State preconditions** — the aggregate is in a status that permits the action (`draft` to approve, `three_way_match_failed` to manually approve).
4. **Bounded additive projection writes** — an inbox projection that does `col = col + ?` against a `CHECK (col <= bound)` is fragile twice over: it is **non-idempotent** (a duplicate / DLT-redriven event double-counts) and it can **breach the bound** from inconsistent input. Make the write a true no-op on replay, not just dedup-gated.

## Reference models — copy these

- **`PurchaseOrder.assertApprovable()`** (`purchasing-service/.../domain/PurchaseOrder.java`) — the template: status gate + `total > 0` + `total == subtotal + tax` + `subtotal == Σ lineTotal` + `tax == Σ lineTax`. Pure read, so it's reused as a pre-transaction API check (`PurchaseOrderService.assertApprovable`) and the in-transaction backstop (`approve`).
- **`JournalEntry.post()`** (`finance-service/.../domain/JournalEntry.java`) — `Σ debits == Σ credits` + ≥2 lines, with the `enforce_journal_balance` deferred DB trigger as the backstop. Write-once immutable aggregate, so no drift-then-transition path exists. The gold standard.
- **Predicate-guarded projection writes** (`inventory-service/.../JdbcStockBalanceWriter` `decrementOnHand` / `tryReserveOnHand`) — the UPDATE carries its own bound in the `WHERE` (`... AND on_hand_quantity - reserved_quantity >= ?`). A would-be breach matches **0 rows** and returns `false` cleanly — it never reaches the CHECK.
- **Effect-gated additive write** (`finance-service/.../JdbcWorkOrderWipProjection.chargeRawMaterials`) — `... ON CONFLICT ... WHERE materials_charged_at IS NULL`: the increment fires exactly once regardless of redelivery / DLT re-apply. Idempotent by *effect*, not merely by `eventId` dedup.

## Idempotency note (additive writes)

Every inbox projection is already idempotent against ordinary duplicate redelivery of
the **same `eventId`** (`AbstractInboxHandler`: `alreadyProcessed → apply → recordProcessed`
in one transaction). That dedup does **not** cover: (a) the rebalance-window TOCTOU
(two concurrent deliveries of the same id — see `docs/messaging.md`), or (b) a
*different* event carrying overlapping/stale quantities (a re-receipt, a DLT message
republished with a new id). So a CHECK-bounded additive write still needs to be
recompute-from-source, predicate-guarded, or effect-gated — `eventId` dedup alone is
not enough.

## Register

Enforced today (✅) and the verified gaps (⚠️ / ❌), from the 2026-06-02 audit. Gaps
are ordered by risk: a CHECK-bounded write that can wedge a saga ranks above a
read-model that only drifts silently.

### Aggregate consistency / value guards

| Aggregate · operation | Invariant | Status |
|---|---|---|
| `PurchaseOrder.approve` / `assertApprovable` | status `draft` + `total>0` + `total==subtotal+tax` + header==Σlines | ✅ enforced (domain + app + api) |
| `JournalEntry.post` / `reverseOf` | `Σ debits == Σ credits`, ≥2 lines | ✅ enforced (domain + DB trigger) |
| `WorkOrder.release` | `plannedQuantity > 0` | ✅ enforced (domain) |
| `Product.changePlanningTimeFence` | `planningTimeFenceDays >= 0` | ✅ enforced (domain `Assert.argument` + api `@PositiveOrZero` fails fast read-only → 400 + DB `CHECK (planning_time_fence_days >= 0)` backstop). The api guard fires before the write tx, so the CHECK is never the surfacing layer. |
| `SupplierInvoice.assertApprovable` (manual + auto path) | status (`three_way_match_failed`) + `total>0` + header==Σlines before emitting `SupplierInvoiceApproved` / posting GL (Dr GRNI / Cr AP) | ✅ enforced (domain + app + api). `manualApprove` calls `assertApprovable()` (status + `assertConsistent()`); the API fails fast read-only before the write tx; and `record(MATCHED, …)` no longer auto-approves a zero-total invoice — it parks at `three_way_match_failed` for review. The supplier-side twin of `PurchaseOrder.assertApprovable`. |
| `SalesOrder.place` / `CustomerInvoice.build` | zero-value handling (header==Σlines holds by construction via `recomputeTotals`) | ✅ resolved as mainstream-ERP behaviour — zero-value sales orders / invoices are **allowed** (free samples, promotions, warranty replacements, 100%-discount lines), so **no** `total>0` block at order/invoice entry. The GL side is the guard: `JournalEntryService.post` **skips a zero/negative amount** (no journal entry — a `0` Dr/Cr pair has no meaning and would violate the `journal_entry_line` `debit>0 OR credit>0` CHECK), logged at INFO and documented as silent fallback #10 in `docs/design-notes.md`. |

Confirmed **not** at risk (header==Σlines by construction at creation; no later drift path; or no header money total): `CustomerInvoice`/`SupplierInvoice`/`SalesOrder`/`GoodsReceipt`/`JournalEntry` *creation factories* (recompute from lines in the same call); `PurchaseRequisition`, `WorkOrder`, `GoodsReceipt`, `Shipment`, `StockAdjustment` (no header money total summed from lines).

### Bounded additive projection writes

| Write site | Column · table | Bound (CHECK) | Status |
|---|---|---|---|
| `JdbcPurchaseOrderPaymentProjection.addInvoicedAmount` | `invoiced_amount` · `purchase_order_header` | `invoiced_amount <= total_amount` | ✅ **intentional backstop — do NOT cap.** Over-invoicing is a real 3-way-match anomaly that must fail loud. The stale-total root cause (the original wedge) is prevented up front by `PurchaseOrder.assertApprovable`, and a deterministic `23514` now **parks** (DltRedriver) instead of looping. Pinned by `JdbcPurchaseOrderPaymentProjectionIT` (`..._violates_schema_CHECK`). |
| `JdbcPurchaseOrderPaymentProjection.markFullyPaid` | `paid_amount = total_amount` · `purchase_order_header` | `paid_amount <= invoiced_amount` | ✅ intentional backstop. Reached only from a payment-receivable saga state (after `invoiced_amount` was bumped), so `invoiced_amount == total_amount` in order; a pay-before-invoice breaches by design (parked, not looped). Pinned by IT. |
| `JdbcPurchaseOrderPaymentProjection.addPartialPayment` | `paid_amount` · `purchase_order_header` | `paid_amount <= invoiced_amount` | ✅ intentional backstop — an over-payment must fail loud (parked, not looped). Pinned by IT (`..._exceeding_invoiced_violates_schema_CHECK`). |
| `JdbcPurchaseOrderReceiptProjection.recordReceipt` (per line) | `received_quantity` · `purchase_order_line` | `received_quantity <= ordered_quantity` | ✅ intentional backstop — an over-receipt must fail loud (parked, not looped). Header `received_amount` is recomputed via `SUM` (safe). |
| `JdbcStockBalanceWriter.releaseReserved` | `reserved_quantity` · `stock_balance` | `reserved_quantity >= 0` | ✅ fixed — now predicate-guarded (`... AND reserved_quantity >= ?`); a duplicate/over release matches 0 rows + WARN, never drives it negative. Matches the sibling `decrementOnHand` pattern. |
| `JdbcWipBalanceWriter.decrement` | `on_hand_quantity` · `wip_balance` | `on_hand_quantity >= 0` | ✅ fixed — now predicate-guarded (`... AND on_hand_quantity >= ?`); breach → 0 rows + WARN, no negative. |
| `JdbcStockBalanceWriter.decrementOnHand` / `tryReserveOnHand` | `on_hand` / `reserved` · `stock_balance` | `>= 0`, `on_hand >= reserved` | ✅ safe — predicate-guarded WHERE; breach → 0 rows → clean `false`. |
| `JdbcSalesOrderLineFactsProjection.tryClaimShipment` | `shipped_quantity` · `sales_order_line_facts` | `shipped_quantity <= ordered_quantity` | ✅ safe — predicate-guarded + **row-locked claim** (`... AND shipped_quantity + ? <= ordered_quantity AND NOT cancelled`), run in the shipment tx *before* the stock decrement. Two concurrent shipments of one line serialize on the row lock; the second past the cap matches 0 rows → 409, so `on_hand` decrements exactly once. The synchronous over-ship guard (the conservation invariants alone did **not** catch the over-ship — a collision probe did). |
| `JdbcSalesOrderLineFactsProjection.tryClaimCancellation` | `cancelled` · `sales_order_line_facts` | (arbiter flag, not a CHECK) | ✅ the cross-service cancel-vs-ship arbiter — flips `cancelled` on every not-yet-shipped line; row-locks the same rows `tryClaimShipment` does, so a concurrent cancel + ship resolves to exactly one winner. See the arbiter pattern below. |
| `JdbcWorkOrderWipProjection.chargeRawMaterials` | `wip_value` · `work_order_wip` | none | ✅ safe — effect-gated `WHERE materials_charged_at IS NULL`. |
| reporting `*_view` / `finance.purchase_order_line_facts` additive bumps | `invoiced_amount` / `paid_amount` / `received_quantity` / … | **none** | ⚠️ read-model only — no CHECK, so no saga wedge, but a semantic double-count silently drifts the dashboards / 3-way-match facts. Lower priority; recompute-from-source if accuracy becomes load-bearing. |

## Adding a new validation — checklist

1. Is the invariant **self-contained** (request + aggregate state only)? If it needs another service's data, it's a saga/projection concern, not this.
2. Put the authoritative check in a **pure-read domain method** (`assertXxx()`), no mutation/events.
3. Call it from the **domain mutator** (backstop) and expose it through the **application** layer.
4. Call it from the **api** layer before the write transaction so an invalid request fails fast (read-only pre-check → 4xx).
5. For an **additive projection write** bounded by a CHECK: make it recompute-from-source, predicate-guarded, or effect-gated — don't lean on `eventId` dedup alone.
6. Keep the DB `CHECK` as the backstop, never as the only guard.

## Concurrency under contention — the synchronous claim + the cross-service arbiter

A self-contained guard + idempotency is **not enough once two _commands_ race over the same
outcome.** Two patterns close that gap; both came out of the load-test collision probes
(`docs/concurrent-load-test.md` §4.6), which found races the conservation invariants are blind
to.

**Why end-state invariants miss it.** A conservation/end-state check (no oversell, ledger
balances, sagas converge) runs *after* the dust settles, so it cannot see a race that produces a
self-consistent-but-wrong state, a transient breach that heals, or a duplicated effect that nets
out. Command-layer races need a **synchronous guard at the command**, plus a **targeted
two-worker collision probe** to prove it — an end-state assertion passes on the broken system,
so it is no evidence of safety.

### Pattern 1 — single-service: claim synchronously before an irreversible effect

Before an irreversible side effect (ship → goods leave; pay → cash moves), make an **atomic,
row-locked claim** that the action is still allowed, in the *same transaction*, and reject on
**0 rows** — never lean on the downstream CHECK to surface it. This is the predicate-guarded
write applied to a *command*: `ShipmentService.post` claims
`shipped_quantity += qty WHERE shipped+qty <= ordered` on `sales_order_line_facts` **before**
decrementing `on_hand`, so two concurrent shipments of one line can't both decrement (the
over-ship bug the probe found).

### Pattern 2 — cross-service: the shared-row arbiter (+ two-phase originator)

When the two racing commands live in **different services** and one is irreversible, the
schema-per-service + outbox-only invariants forbid a shared lock or a synchronous cross-service
call. Resolve it without one:

- **Make the service that owns the irreversible effect the arbiter** (here: inventory, which
  ships).
- **Both commands claim the same row** in that service's schema (`sales_order_line_facts`): the
  irreversible command claims via its eligibility predicate (`tryClaimShipment … AND NOT
  cancelled`); the competing command claims the conflicting flag on the same rows
  (`tryClaimCancellation` sets `cancelled WHERE shipped_quantity = 0`). The **row lock serializes
  them — whichever commits first wins**, and the loser matches 0 rows.
- **The foreign originator goes two-phase.** The service that *initiates* the competing command
  (here: sales, which cancels) must not finalise synchronously — it only **requests**
  (`SalesOrder.requestCancellation` emits the event, no status change), the arbiter **acks** only
  on a win, and the originator **confirms** on the ack (`confirmCancellation` → `cancelled`).
  Critically, **defer every side effect to the confirmation** — the saga compensation fires on
  the applied-ack, not at request time, so a *lost* race triggers nothing (the order just ships).

Worked examples: the concurrent **double-ship** over-ship (pattern 1) and the **cancel-vs-ship**
half-state (pattern 2) — both surfaced by the probes, both fixed here; full flow in
`docs/sagas.md` → *Cancel compensation flow (two-phase, inventory-arbitrated)*. The same shape is
the principled answer to the still-open cross-partition PR→PO link race (a sibling cross-service
TOCTOU): a row both the dispatch and the PO-created handler claim, in the service that owns the
link.
