# Northwood — Requirement coverage matrix

Maps every requirement in `docs/business-requirements.md` to the test(s) that exercise it, so
coverage drift is visible at a glance. Keep it in sync when adding requirements or tests.

## Test tiers

Coverage is layered (see `docs/test-harness-dsl.md` §8 for why each tier exists and what it must
**not** absorb):

| Tier | Where | Covers |
|---|---|---|
| **Domain unit** | `*-service/src/test/.../domain/*Test.java` | Single-aggregate invariants (fold ladders, guards, BOM cycles, journal balance). |
| **Acceptance DSL** | `test-harness/.../**/*DslTest.java` | Cross-service business **outcomes** end-to-end over the real saga + handlers + Jackson serde (in-memory bus). The matrix below names these per flow. |
| **Persistence / seam IT** | `*-service/src/test/.../*IT.java` (Testcontainers + Postgres) | `Jdbc*RepositoryIT` (round-trip + triggers), `*SeamIT` (one cross-service projection edge), saga-adapter ITs (lease/claim). |
| **Delivery IT** | `shared/.../*DeliveryIT`, `DltRedriverIT` | Kafka ordering, retry/backoff, DLT redrive. |
| **Security / demo** | `@PreAuthorize` + Demo 8 walkthrough (`docs/demo-script.md`) | Role gates, audit trail. |

Legend: ✅ covered · ⚠️ partial / verify · ❌ gap (not yet covered or not shipped).

---

## REQ-PROD — Product master

| REQ | Covered by | Status |
|---|---|---|
| REQ-PROD-001/002/003 (register, immutable SKU, type defaults) | domain unit (`product` aggregate) + `JdbcProductRepositoryIT`; downstream fan-out via `MakeVsBuyChangedSeamIT` / `ReorderPolicyChangedSeamIT` | ✅ |
| REQ-PROD-010/011 (make-vs-buy) | `MakeVsBuyChangedSeamIT` (inventory projection edge); routing exercised by replenishment DSL tests | ✅ |
| REQ-PROD-020/021 (reorder policy) | `ReorderPolicyChangedSeamIT`; consumed by all `StockReplenishment*DslTest` | ✅ |
| REQ-PROD-022 (to-stock vs to-order) | `OrderToCash{MakeToOrder,BuyToOrder}PathDslTest`, `PurchaseRequisitionToOrderGuardDslTest` | ✅ |
| REQ-PROD-030/031/040 (pricing, historical, std cost) | domain unit + `JdbcProductRepositoryIT`; price-at-capture exercised by o2c DSL tests | ✅ |
| REQ-PROD-041 (manufactured cost rollup = material + conversion, recursive) | `MaterialsCostRollupServiceTest` (component sum, scrap uplift, leaf-cost change walks parent, missing-cost + cross-currency guards, own-routing conversion + multi-level sub-assembly conversion folded into standardCost); `SeedStandardCostConsistencyIT` (every seeded manufactured BOM's std cost == material+conversion rollup) | ✅ |
| REQ-PROD-050 (valuation class) | `StockReplenishmentWipPostingsDslTest` (routes WIP/COGS by class) | ✅ |
| REQ-PROD-060 (active BOM) | `JdbcBomRepositoryIT`; cycle detection domain unit | ✅ |
| REQ-PROD-070 (approved vendor) | `JdbcSupplierProductPriceRepositoryIT` / `*QueryPortIT` | ✅ |
| REQ-PROD-080 (discontinue) | `ProductTest` (aggregate) + a per-consumer `ProductDiscontinuedHandlerTest` in inventory / sales / purchasing / manufacturing / reporting + `SalesOrderServicePlaceOrderTest` (rejects a discontinued SKU) | ✅ |

## REQ-SAL — Sales

| REQ | Covered by | Status |
|---|---|---|
| REQ-SAL-001/002/003 (customer master) | `JdbcCustomerRepositoryIT` + domain unit | ✅ |
| REQ-SAL-010/011/012 (place order, positive, numbering) | domain unit (`SalesOrder`) + `OrderToCashHappyPathDslTest`; `OrderToCashLineAmendmentDslTest` | ✅ |
| REQ-SAL-013/037 (planning time fence) | `OrderToCashPlanningFenceDslTest` + `OrderToCashPlanningFenceTest` (kept — decide-once/`nextRetryAt`) | ✅ |
| REQ-SAL-020/021/022 (payment terms, prepayment, gate) | `OrderToCashDepositPathDslTest`, `OrderToCashCodPathDslTest` | ✅ |
| REQ-SAL-030…035 (fulfilment lifecycle states) | `SalesOrder` fold domain unit + the o2c DSL suite; saga adapter `JdbcSalesOrderFulfilmentSagaAdapterIT` | ✅ |
| REQ-SAL-036/040 (cancel / compensation) | `OrderToCashCancellationPathDslTest`, `OrderToCashCancelRefundPathDslTest` (release reservations + refund; no WO cancel) | ✅ |
| REQ-SAL-041 (hard cancel during mfg) | — | ⚠️ deferred — WO-cancel not built and the WO↔sales-line binding was removed; REQ flipped to `(deferred)` (see REQ-MFG-060) |
| REQ-SAL-050/060 (auto-invoice on ship, payment allocation) | o2c DSL suite (invoice + payment legs) | ✅ |

## REQ-INV — Inventory

| REQ | Covered by | Status |
|---|---|---|
| REQ-INV-001/010/011 (warehouses, balances) | `JdbcStockBalanceWriterIT`, `JdbcStockReservationRepositoryIT` | ✅ |
| REQ-INV-020/021/022/023 (reserve / release) | o2c DSL suite + `StockReplenishment*DslTest`; `JdbcStockReservationRepositoryIT` | ✅ |
| REQ-INV-030/031 (goods receipt) | `JdbcGoodsReceiptRepositoryIT`; receipts driven for real by replenishment + P2P DSL tests | ✅ |
| REQ-INV-040/041 (shipment + gates) | o2c DSL suite (incl. prepayment 409 gate via `OrderToCashDepositPathDslTest`) | ✅ |
| REQ-INV-050/051 (stock adjustment, no-negative) | `JdbcStockAdjustmentRepositoryIT` + domain unit | ✅ |
| REQ-INV-060/070 (movement audit, reorder mirror) | `JdbcStockBalanceWriterIT`; `ReorderPolicyChangedSeamIT` | ✅ |
| REQ-INV-080…088 (unified replenishment loop) | `StockReplenishment{Manufactured,Purchased,WorkOrderShortage}PathDslTest`; `JdbcReplenishmentRequestRepositoryIT` (one-open partial-unique index) | ✅ |
| REQ-INV-086 (unsourceable) | `OrderToCashRejectedPathDslTest` | ✅ |
| REQ-INV-087 (mfg↔pur decoupling) | `StockReplenishmentWorkOrderShortagePathDslTest` (Trigger B routes via inventory) + the architecture grep in CLAUDE.md | ✅ |
| REQ-INV-090/091/092 (MTS model, demand-source-aware, MRP scope) | replenishment DSL suite (reason routing) | ✅ |
| REQ-INV-093 (make/buy-to-order) | `OrderToCash{MakeToOrder,BuyToOrder}PathDslTest` | ✅ |

## REQ-MFG — Manufacturing

| REQ | Covered by | Status |
|---|---|---|
| REQ-MFG-001/002/003 (BOM, authoring, cycle detection) | `JdbcBomRepositoryIT` + BOM-cycle domain unit | ✅ |
| REQ-MFG-010/020/021 (routing, WO release, sub-assembly recursion) | `JdbcWorkOrderRepositoryIT`; `StockReplenishmentSubAssemblyPathDslTest` (+ kept imperative for structural probe) | ✅ |
| REQ-MFG-030 (release for replenishment) | `StockReplenishmentManufacturedPathDslTest` | ✅ |
| REQ-MFG-040 (material reservation + shortage, Trigger B) | `StockReplenishmentWorkOrderShortagePathDslTest` | ✅ |
| REQ-MFG-041/050/051/052 (material status, ops, skip, parent gating) | `JdbcWorkOrderRepositoryMaterialStatusIT` + domain unit; real op completion in manufactured DSL tests | ✅ |
| REQ-MFG-060 (WO cancel) | — | ❌ **feature not implemented** (no `WorkOrder.cancel()` / endpoint; `CANCELLED` is guard-only, never produced). REQ flipped to `(deferred)` — not a test gap. |
| REQ-MFG-070 (prioritisation) | `SetPriorityCascadeDslTest` + `SetPriorityCascadeTest` (kept) | ✅ |
| REQ-MFG-075/090 (sub-assembly consumption, cost rollup) | `StockReplenishmentSubAssemblyWipPostingsDslTest` (075 — consumed at parent completion) + `MaterialsCostRollupServiceTest` (090 — BOM cost rollup) | ✅ |
| REQ-MFG-080 (reject unmakeable) | `OrderToCashRejectedPathDslTest` | ✅ |

## REQ-PUR — Purchasing

| REQ | Covered by | Status |
|---|---|---|
| REQ-PUR-001/010/011 (supplier, prices, dup no-op) | `JdbcSupplierRepositoryIT`, `JdbcSupplierProductPriceRepositoryIT` | ✅ |
| REQ-PUR-020/021 (requisition sources, auto-approve) | `PurchaseToPayHappyPathDslTest`, `StockReplenishment{Purchased,WorkOrderShortage}PathDslTest`, `PurchaseRequisitionToOrderGuardDslTest`; `JdbcPurchaseRequisitionRepositoryIT` | ✅ |
| REQ-PUR-030/031 (PR→PO, approval) | `PurchaseToPayHappyPathDslTest`; `JdbcPurchaseOrderRepositoryIT`, `JdbcPurchaseToPaySagaAdapterIT` | ✅ |
| REQ-PUR-040 (goods receipt) | see REQ-INV-030 | ✅ |
| REQ-PUR-050 (3-way match) | `PurchaseToPayRejectionPathDslTest` | ✅ |

## REQ-FIN — Finance

| REQ | Covered by | Status |
|---|---|---|
| REQ-FIN-010/011/012 (balanced journal, source link, reversal) | domain unit (`JournalEntryTest`, reverse-by-source) + `JdbcJournalEntryRepositoryIT` (balance trigger) | ✅ |
| REQ-FIN-020/021/022 (receipt, supplier invoice, supplier payment) | `PurchaseToPayHappyPathDslTest` (GL legs) | ✅ |
| REQ-FIN-023/024/025 (shipment COGS, customer invoice, customer payment) | `OrderToCashHappyPathDslTest`, `OrderToCashCancelRefundPathDslTest` (GL legs) | ✅ |
| REQ-FIN-026/027 (raw→WIP, WO completion at full standard cost) | `StockReplenishmentWipPostingsDslTest`; `JournalEntryServicePostingsTest` (completion Dr 1220/Cr 1230, WIP-nets-to-zero legs) | ✅ |
| REQ-FIN-028 (sub-assemblies consumed Dr WIP/Cr FG) | `StockReplenishmentSubAssemblyWipPostingsDslTest` (parent consumes child → Dr 1230/Cr 1220); `a_journal()` now disambiguates the two `WORK_ORDER_WIP` legs by account pair | ✅ |
| REQ-FIN-029 (conversion applied + efficiency variance) | `JournalEntryServicePostingsTest` (conversion Dr 1230/Cr 5250; unfavourable/favourable/zero variance; nets-to-zero with actual conversion + variance); `WorkOrderConversionAppliedHandlerTest` (charge at actual + clear variance to 5100); `MaterialsCostRollupServiceTest` (conversion folded into standardCost); `StockReplenishmentConversionWipDslTest` (e2e: material + conversion + favourable variance + completion all net WIP to zero across inventory→manufacturing→finance) | ✅ |
| REQ-FIN-030/031/032 (prepayment deposits, recognition) | `OrderToCashDepositPathDslTest`, `OrderToCashCancelRefundPathDslTest` | ✅ |
| REQ-FIN-040/041 (allocation, prepayment settlement) | `JdbcCustomerInvoiceRepositoryIT` (paid_amount trigger), `JdbcPaymentRepositoryIT`; deposit DSL test | ✅ |
| REQ-FIN-050/051 (supplier invoice, manual-review queue) | `PurchaseToPayRejectionPathDslTest`; `JdbcSupplierInvoiceRepositoryIT` | ✅ |
| REQ-FIN-060 (same-currency pass-through) | exercised throughout — every DSL / IT flow is single-currency (AUD), routing the `CurrencyConverter` same-currency branch | ✅ |
| REQ-FIN-061/062 (cross-currency conversion + inverse-rate fallback; ad-hoc rate lookup) | — | ❌ **multi-currency not implemented** — single-currency (AUD) only today; REQs flipped to `(deferred)`. A **feature** gap, not a test gap. |

## REQ-RPT — Reporting (read views)

| REQ | Covered by | Status |
|---|---|---|
| REQ-RPT-001 (Sales Order 360) | `JdbcSalesOrder360ProjectionIT`, `JdbcSalesOrder360QueryPortIT`; o2c DSL `is_completed()` rollup | ✅ |
| REQ-RPT-010 (PO Tracking) | `JdbcPurchaseOrderTrackingProjectionIT` (money-flow bars ordered/received/invoiced/paid + statuses) | ✅ |
| REQ-RPT-020 (Production board) | `JdbcProductionPlanningQueryPortIT`; `SetPriorityCascadeDslTest` | ✅ |
| REQ-RPT-030 (Material shortage) | `JdbcMaterialShortageQueryPortIT` | ✅ |
| REQ-RPT-040 (ATP) | `JdbcAvailableToPromiseProjectionIT` (on-hand/reserved/available triple + incoming-from-purchase/production) | ✅ |
| REQ-RPT-050 (Financial dashboard) | `JdbcFinancialDashboardProjectionIT`, `JdbcFinancialDashboardQueryPortIT` | ✅ |
| REQ-RPT-060 (Replenishment history) | — | ❌ not shipped (planned) |

## REQ-XBC — Cross-context flows

| REQ | Covered by | Status |
|---|---|---|
| REQ-XBC-010 (order-to-cash) | `OrderToCashHappyPathDslTest` (+ COD/deposit/amendment variants) | ✅ |
| REQ-XBC-020 (procure-to-pay) | `PurchaseToPayHappyPathDslTest` | ✅ |
| REQ-XBC-030 (sales-shortage make-to-stock) | `StockReplenishmentManufacturedPathDslTest`, `OrderToCashPurchasedShortagePathDslTest` | ✅ |
| REQ-XBC-040 (material-shortage auto-requisition, retired→unified) | `StockReplenishmentWorkOrderShortagePathDslTest` | ✅ |
| REQ-XBC-080/081/082 (unified replenishment loop + decoupling) | the three `StockReplenishment*PathDslTest` (Trigger A make/buy + Trigger B) | ✅ |
| REQ-XBC-090 (cancel before shipment) | `OrderToCashCancellationPathDslTest`, `OrderToCashCancelRefundPathDslTest` | ✅ |

## REQ-SEC — Security and roles

| REQ | Covered by | Status |
|---|---|---|
| REQ-SEC-001 (role-based access) | `KeycloakRealmRoleConverterTest` (realm-role → `ROLE_*` authority) + `SalesOrderControllerSecurityTest` (cancel gate: `sales_clerk` 403 / `sales_manager` 200) | ✅ representative |
| REQ-SEC-002 (audit trail) | `JdbcAuditQueryAdapterIT` (shared) + Demo 8 | ✅ |

---

## Resilience (not a REQ section, but demoed — Demo 9)

| Concern | Covered by |
|---|---|
| Broker outage / outbox replay | `KafkaInboxDispatcherDeliveryIT`, `DltRedriverIT`, `JdbcOutboxAdapterIT`, `JdbcInboxAdapterIT`, `JdbcSalesOrderFulfilmentSagaAdapterIT` (lease reclaim), `SalesFulfilmentSagaCrossPartitionRaceIT` |

## Known gaps (open)

- **REQ-RPT-060** — Replenishment History view not shipped (planned).
- **REQ-MFG-060 / REQ-SAL-041** — operator WO-cancel is **not implemented** (no `WorkOrder.cancel()`, no endpoint; `CANCELLED` is only guarded against). Both requirements were flipped from `(shipped)` to `(deferred)` in `business-requirements.md`. To ship: add `WorkOrder.cancel()` (release reservations, halt in-progress ops, status → `cancelled`, write off WIP) + a cancel endpoint + tests. (A make-to-stock WO is not bound to a sales order, so the sales-cancel flow no longer needs it.)
- **REQ-FIN-061 / REQ-FIN-062** — **multi-currency is not implemented**; the system runs single-currency (AUD) only (REQ-FIN-060 is the live path). Cross-currency conversion + the `GET /api/exchange-rate` lookup are not delivered (low-priority — project memory), so this is a **feature gap, not a test gap**. If multi-currency is ever built, add a `CurrencyConverter` unit test (pass-through + cross-currency + inverse fallback) + a `JdbcCurrencyConverterIT` then.

> The cancel-gate test (`SalesOrderControllerSecurityTest`) is the **representative** role-gate test; the other `@RequireXxx` meta-annotations share the same `@PreAuthorize` mechanism (and the realm-role → authority mapping is covered by `KeycloakRealmRoleConverterTest`). Per-endpoint gate tests can be added if a regression ever warrants it.
