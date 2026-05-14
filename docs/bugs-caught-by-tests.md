# Bugs caught by unit tests

A running log of defects surfaced by the unit-test suite as it grows. Each entry: where the test ran, what broke, root cause, and the fix.

This file is the running ledger for the test-tier rollout (started 2026-05-05). Bugs caught during integration-test runs (e.g. the `ReorderPolicyChangedSeamIT` Testcontainers IT) and bugs caught during code review go here too — but only if they were *caught by* a test, not just *fixed before* writing one.

---

## 2026-05-12 — `invoiced_amount` never bumped on PO header; `SupplierPaymentMade` perpetually hits CHECK violation

**Caught by:** Live end-to-end runthrough of scenario 7.1 against the real Postgres stack. Unit tests + the in-memory P2P happy-path harness didn't catch it because the in-memory `InMemoryPurchaseOrderPaymentProjection` has no CHECK constraints; only a real Postgres exercise of the full P2P chain trips the schema-side guard.

**Root cause:** `SupplierInvoiceApprovedHandler` only advanced the saga state — it never bumped `purchase_order_header.invoiced_amount`. Then `SupplierPaymentMadeHandler` → `markFullyPaid()` tried `UPDATE … SET paid_amount = total_amount`, violating the schema CHECK `paid_amount <= invoiced_amount` (because `invoiced_amount` was still 0). The thrown exception rolled back the saga's `→ completed` transition. Kafka retried 4 times, each time the same CHECK fired, and the message was routed to `<topic>.dlt`. Saga stayed parked at `supplier_invoice_approved`.

**Symptom recognition cue:** in the purchasing-service log, the saga manager logs `→ completed (fully settled)` exactly N times (default 4 retries before DLT), each followed by "Record in retry and not yet recovered", and finally a `publishing to DLT finance.events.dlt` WARN. The "completed" log lines are *inside* the transaction that's about to roll back, so they're misleading — the DB never sees the state change.

**Fix:**
- New method `PurchaseOrderPaymentProjection.addInvoicedAmount(poId, amount)` — bumps `invoiced_amount` additively and flips status to `'partially_invoiced'` / `'invoiced'` based on whether the new running total covers the order.
- `SupplierInvoiceApprovedHandler` now calls `paymentProjection.addInvoicedAmount(...)` in the same transaction as the saga advance, with `payload.totalAmount()` as the invoice amount. Multi-invoice POs sum correctly because of the additive shape.
- `InMemoryPurchaseOrderPaymentProjection` (test-harness) and `SupplierInvoiceApprovedHandlerTest` updated to match the new contract.

**Re-run after the fix:** scenario 7.1 placed a fresh SO, drove through goods receipt → supplier invoice → supplier payment. P2P saga reached `completed`. Final PO row: `status='paid'`, `invoiced_amount=900`, `paid_amount=900`.

**Lesson — in-memory test harnesses miss schema-enforced invariants.** The §2.5.1 in-memory P2P happy-path test passes today even with this bug present, because the in-memory projection has no equivalent of the CHECK constraint. This is a known limitation of the harness shape (CLAUDE.md §2.5.1: "Phase C — `Jdbc*` infrastructure tests (Testcontainers + real Postgres) — deferred"). A future Phase-C slice covering `JdbcPurchaseOrderPaymentProjection` against a real Postgres would have caught this. Until that lands, *the live runthrough is the only test that exercises schema CHECKs*, and the dev-todo's "smoke-test against a fresh DB volume before claiming a slice ships" rule is the operational backstop.

---

## 2026-05-05 — `FulfilmentSagaData` 4-arg constructor change broke `StockReservedHandler`

**Caught by:** `mvn -pl sales-service test` failing the `default-compile` phase before any unit test ran.

**Root cause:** earlier today's slice "cross-partition race fix in sales fulfilment saga" added a new `Integer expectedWorkOrderCount` field to `FulfilmentSagaData`, expanding the canonical constructor from 3 args to 4. Three call sites were updated (`ManufacturingDispatchedHandler`, `WorkOrderManufacturingCompletedHandler`, `none()`) but `StockReservedHandler.java:172` was missed — it still called the 3-arg constructor. The build went through earlier (the smoke test at the time only exercised the updated handlers), but as soon as `mvn test` was invoked on the module, the missed call site failed `javac`.

**Fix:** added `existing.expectedWorkOrderCount()` to the constructor call in `StockReservedHandler` so it preserves the field across the partial-shortage stash:

```java
SagaDataIO.write(saga, new FulfilmentSagaData(
    shortage,
    existing.expectedWorkOrderCount(),     // ← was missing
    existing.outstandingWorkOrderIds(),
    existing.completedWorkOrderIds()
), json);
```

**Lesson:** when expanding a record's canonical constructor signature, `mvn -am compile` from the module that owns the record tells you nothing about call sites elsewhere — `mvn -am test` (or even `install`) on the dependent module catches them. The earlier slice's "compile clean" claim was scoped to the module owning the record, not to consumers.

The `StockReservedHandler` test added in tier 2 (`PreservesExpectedWorkOrderCount` nested class) now guards directly against this regressing — `retry_after_dispatch_preserves_expected_count` fails if the constructor call drops the field again.

---

## 2026-05-05 — `CustomerPaymentReceivedHandler` writes `invoice_paid` saga state that violates schema CHECK

**Caught by:** code audit while verifying `user-stories.md` against the implementation (not a test, but logged here so the next reader sees it alongside the test-caught ones).

**Root cause:** `sales-service/.../inbox/CustomerPaymentReceivedHandler.java:103` transitions the saga to `invoice_paid` on partial customer settlements. That state is not in the schema CHECK on `sales.sales_order_fulfilment_saga.saga_state` (v3 baseline lists: `started, stock_reservation_requested, stock_reserved, stock_reservation_failed, manufacturing_requested, manufacturing_in_progress, manufacturing_completed, ready_to_ship, goods_shipped, invoice_requested, invoice_created, completed, compensating, compensated, failed`). A real partial customer payment would fail with `check constraint "sales_order_fulfilment_saga_saga_state_check"`.

**Why no test caught it:** the tier 2 test for `CustomerPaymentReceivedHandler` mocks the repository — `sagas.save(saga)` is stubbed and never reaches the DB, so the CHECK never runs. The schema-level invariant is invisible to a unit test that mocks persistence.

**Fix:** added Liquibase changeset `2026-05-05-extend-fulfilment-saga-states.sql` that drops and re-adds the CHECK with `invoice_paid` included. Also patched `db/northwood_erp_v3.sql` so fresh DBs get the right list from the start.

**Lesson:** state-machine handlers that introduce a new state need a paired schema migration. A unit test against mocked persistence won't catch it; either a Testcontainers-backed integration test (the level above tier 2) or a schema-level compile check (e.g. enumerated state in code cross-referenced with a schema query at startup) would. Captured in dev-todo as a follow-up.

---

## Tier 2 (saga state-machine tests) — no new defects surfaced

Seven handler test files added for the highest-leverage cross-context handlers; all passed on first run after compile fixes. The earlier-today bug above remains the only one caught by the tier 1 + tier 2 rollout.

Coverage:
- `ManufacturingDispatchedHandlerTest` (sales) — race-fix slice.
- `WorkOrderManufacturingCompletedHandlerTest` (sales) — multi-WO + sub-assembly filter.
- `RawMaterialsReservedHandlerTest` (manufacturing) — full / partial / failed paths.
- `GoodsReceivedHandlerTest` (manufacturing) — sharper-trigger un-park.
- `SupplierPaymentMadeHandlerTest` (purchasing) — full vs partial settlement.
- `CustomerPaymentReceivedHandlerTest` (sales) — O2C close-out.
- `StockReservedHandlerTest` (sales) — partial-shortage stash preserving expectedWorkOrderCount.

Total reactor: **308 tests, 0 failures.**

---
