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
| **`SupplierInvoice.manualApprove`** | **header consistency + `total>0` before emitting `SupplierInvoiceApproved` and posting GL (Dr GRNI / Cr AP)** | ❌ **gap — only a status check.** Stored `totalAmount` is trusted verbatim; a drifted/zero total posts a bad ledger entry and feeds the P2P `paid_amount <= total_amount` chain. The true twin of the PO bug, and worse (hits the GL). Add an `assertConsistent()` mirroring `PurchaseOrder.assertApprovable`'s consistency block. |
| `SalesOrder.place` | `totalAmount > 0` (header==Σlines holds by construction via `recomputeTotals`) | ⚠️ gap — no `total>0` guard; a fully zero-priced order places and later auto-creates a zero-total customer invoice + zero GL posting. Decide: guard, or consciously document zero-priced orders. |
| `CustomerInvoice.build` | `total > 0` (auto-posts Dr AR / Cr Revenue) | ⚠️ gap — inherits a zero from `SalesOrder`; same decision as above. |

Confirmed **not** at risk (header==Σlines by construction at creation; no later drift path; or no header money total): `CustomerInvoice`/`SupplierInvoice`/`SalesOrder`/`GoodsReceipt`/`JournalEntry` *creation factories* (recompute from lines in the same call); `PurchaseRequisition`, `WorkOrder`, `GoodsReceipt`, `Shipment`, `StockAdjustment` (no header money total summed from lines).

### Bounded additive projection writes

| Write site | Column · table | Bound (CHECK) | Status |
|---|---|---|---|
| `JdbcPurchaseOrderPaymentProjection.addInvoicedAmount` | `invoiced_amount` · `purchase_order_header` | `invoiced_amount <= total_amount` | ⚠️ gap — additive, dedup-only. Over-invoice or duplicate `SupplierInvoiceApproved` → `23514` wedge (the original bug's residual). Recompute from approved invoices, or `LEAST(total_amount, …)`. |
| `JdbcPurchaseOrderPaymentProjection.markFullyPaid` | `paid_amount = total_amount` · `purchase_order_header` | `paid_amount <= invoiced_amount` | ⚠️ gap — sets `paid_amount = total_amount`, not `invoiced_amount`; if `invoiced_amount < total_amount` at that instant (event reordering) → `23514`. Set `paid_amount = invoiced_amount`, or assert `invoiced_amount == total_amount` first. |
| `JdbcPurchaseOrderPaymentProjection.addPartialPayment` | `paid_amount` · `purchase_order_header` | `paid_amount <= invoiced_amount` | ⚠️ gap — additive; duplicate/over-payment → `23514`. Cap with `LEAST(invoiced_amount, …)` or recompute. |
| `JdbcPurchaseOrderReceiptProjection.recordReceipt` (per line) | `received_quantity` · `purchase_order_line` | `received_quantity <= ordered_quantity` | ⚠️ gap — additive; over-/duplicate receipt → `23514`. Guard `... AND received_quantity + ? <= ordered_quantity` (no-op on breach). The header `received_amount` in the same method is recomputed via `SUM` and is ✅ safe. |
| `JdbcStockBalanceWriter.releaseReserved` | `reserved_quantity` · `stock_balance` | `reserved_quantity >= 0` | ⚠️ gap — bare `reserved_quantity - ?`, no floor; a duplicate/over release drives it negative → `23514`. Floor with `GREATEST(reserved_quantity - ?, 0)` (the sibling combined method already uses `LEAST`). |
| `JdbcWipBalanceWriter.decrement` | `on_hand_quantity` · `wip_balance` | `on_hand_quantity >= 0` | ⚠️ gap — subtractive, no floor/guard; duplicate/over `SubAssembliesConsumed` → negative → `23514`. Guard `... AND on_hand_quantity - ? >= 0` or clamp. |
| `JdbcStockBalanceWriter.decrementOnHand` / `tryReserveOnHand` | `on_hand` / `reserved` · `stock_balance` | `>= 0`, `on_hand >= reserved` | ✅ safe — predicate-guarded WHERE; breach → 0 rows → clean `false`. |
| `JdbcWorkOrderWipProjection.chargeRawMaterials` | `wip_value` · `work_order_wip` | none | ✅ safe — effect-gated `WHERE materials_charged_at IS NULL`. |
| reporting `*_view` / `finance.purchase_order_line_facts` additive bumps | `invoiced_amount` / `paid_amount` / `received_quantity` / … | **none** | ⚠️ read-model only — no CHECK, so no saga wedge, but a semantic double-count silently drifts the dashboards / 3-way-match facts. Lower priority; recompute-from-source if accuracy becomes load-bearing. |

## Adding a new validation — checklist

1. Is the invariant **self-contained** (request + aggregate state only)? If it needs another service's data, it's a saga/projection concern, not this.
2. Put the authoritative check in a **pure-read domain method** (`assertXxx()`), no mutation/events.
3. Call it from the **domain mutator** (backstop) and expose it through the **application** layer.
4. Call it from the **api** layer before the write transaction so an invalid request fails fast (read-only pre-check → 4xx).
5. For an **additive projection write** bounded by a CHECK: make it recompute-from-source, predicate-guarded, or effect-gated — don't lean on `eventId` dedup alone.
6. Keep the DB `CHECK` as the backstop, never as the only guard.
