# Northwood ERP — User Stories (forward-looking backlog)

Persona-driven stories that frame the showcase. Each carries a status flag:

- ✅ **Implemented** — works end-to-end today; demo it from `demo-script.md`.
- 🚧 **Partial** — core flow works but the story has unimplemented sub-acceptance-criteria; specifics noted on the story.
- ⏳ **Planned** — code does not exercise this path. Schema may already reserve the saga states; handlers/endpoints are missing.

For the runbook of what works today, see `demo-script.md`. For architecture invariants, see `CLAUDE.md`. For the implementation backlog (slice-level, not story-level), see `dev-todo.md`.

## Personas

| Persona | Role |
|---|---|
| **Emma** | Product Catalog Manager — owns SKUs and pricing |
| **Sarah** | Sales Order Specialist — takes customer orders, monitors fulfilment |
| **Mike** | Inventory Manager — manages stock balances, reservations, replenishment |
| **Linda** | Production Planner — schedules work orders, watches BOM shortages |
| **Tom** | Purchasing Officer — raises POs, receives goods, matches invoices |
| **Olivia** | Accounts Receivable / Payable Officer — handles invoices, allocates payments |
| **Daniel** | Finance Controller — posts journals, watches the financial dashboard |

## Conventions

- **Trigger** is the inbound command (REST call, message, scheduled tick).
- **Pattern** is the architectural concept the story demonstrates.
- **Services involved** distinguishes who *commands* from who *projects*.
- **Events** are the outbox messages that flow on the bus — names match the production code.
- **Saga state** is what changes in the relevant `*_saga` table.

---

## Demo 1 — CQRS: product master as the upstream Open Host

The product service is the only place SKUs, pricing, and reorder policy are *owned*. Other services keep their own *read copies* populated from events. Reorder policy follows the Material Master / Shape A pattern: R&D / planning configures it on `product.product` as the data of record (Story 1.3); `inventory.stock_item.reorder_point` / `reorder_quantity` are kept in sync as a projection driven by `ReorderPolicyChanged`.

### Story 1.1 — Register a new product ✅

**As** Emma the Catalog Manager
**I want** to register a new SKU with name, type, base UoM, sales price, and standard cost
**So that** every other context can refer to the product without coupling to the catalog service at runtime

**Trigger** — `POST /api/products` on product-service
**Acceptance criteria**
- ✅ The new row exists in `product.product` with `version = 1` and `status = 'active'` (the persistence contract: `version = 0` is the in-memory sentinel for "not yet saved"; the DB row is never at `version = 0`).
- ✅ A `product.ProductCreated` event lands in `product.outbox_message`.
- ✅ Within one outbox poll cycle, the event is forwarded to the bus and consumed by every downstream service that maintains a product read model: inventory (`stock_item` stub), sales (`product_pricing` stub), manufacturing (`product_replenishment` stub), finance (`product_accounting` stub), reporting (atp). Purchasing has no product read model of its own (documented as inferred-only in `event-flow.html`'s coverage gaps).
- ✅ `inventory.stock_item` gains a new row carrying SKU + name + type — populated **only from the event**, with no synchronous call to product-service.
- ✅ The new product's `reorder_point` / `reorder_quantity` default to 0/0 and are set explicitly via `setReorderPolicy` (Story 1.3); the `inventory.stock_item` row inherits 0/0 until that command runs.

**Pattern** — CQRS write side / event-driven read-side projection (Open Host upstream)
**Services involved** — product (command), inventory + sales + manufacturing + finance + reporting/atp (projections)
**Events** — `product.ProductCreated`

### Story 1.2 — Change the price of a SKU ✅

**As** Emma
**I want** to change a product's sales price and standard cost
**So that** sales quotes use the new price immediately and finance values inventory at the new standard cost

**Trigger** — `PUT /api/products/{id}/sales-price` and/or `PUT /api/products/{id}/standard-cost`
**Acceptance criteria**
- ✅ `product.product.sales_price`, `standard_cost`, and `version` all update independently per call; `updated_at` advances.
- ✅ A `product.SalesPriceChanged` event fires from the sales-price endpoint; a `product.StandardCostChanged` event fires from the standard-cost endpoint. Each carries its own `oldValue`, `newValue`, `currencyCode`.
- ✅ Sales-service consumes `SalesPriceChanged` via `SalesPriceChangedHandler`; new sales orders quote the new price; existing orders remain unchanged (price is denormalised on the line at order time).
- ✅ Finance-service consumes `StandardCostChanged` via `StandardCostChangedHandler` → writes `finance.product_accounting.standard_cost`. `ShipmentPostedCogsHandler` reads that column to drive the COGS posting; the shipment-line-stamped `unitCost` is only a documented cold-start fallback (see `design-notes.md` → COGS standard cost).

**Pattern** — CQRS with **point-in-time denormalisation** on the consumer side
**Events** — `product.SalesPriceChanged`, `product.StandardCostChanged`

### Story 1.3 — Set reorder policy on a SKU ✅

**As** the planning steward (R&D-owned, with Mike consuming the rule on the inventory side)
**I want** to set `reorder_point` and `reorder_quantity` on a product
**So that** inventory's projection picks the new thresholds up via event and downstream replenishment uses them on the very next planning cycle

**Trigger** — `PUT /api/products/{id}/reorder-policy`
**Acceptance criteria**
- ✅ `product.product.reorder_point` / `reorder_quantity` update; `version` increments and `updated_at` advances.
- ✅ A `product.ReorderPolicyChanged` event lands in `product.outbox_message`.
- ✅ Within one outbox poll cycle, the event reaches inventory-service's inbox; `ReorderPolicyChangedHandler` updates the matching `inventory.stock_item` row idempotently. There is **no** inventory-side command — inventory's reorder columns are a projection only.
- ✅ The endpoint rejects with a domain error when the product is `discontinued`.

**Pattern** — Material Master / PIM hub (Shape A): R&D-owned data of record on product, every downstream context keeps its own projection.
**Events** — `product.ReorderPolicyChanged`
**Read models that update** — `inventory.stock_item.reorder_point` / `reorder_quantity`

> Covered by integration test `ReorderPolicyChangedSeamIT`.

### Story 1.4 — Discontinue a product ✅

**As** Emma
**I want** to mark a product as discontinued
**So that** sales can no longer quote it, manufacturing won't release new work orders for it (or for any FG whose active BOM references it), purchasing won't accept new requisitions for it, and downstream consumers stamp `discontinued_at` for audit / UI greying

**Trigger** — `POST /api/products/{id}/discontinue`
**Acceptance criteria**
- ✅ `product.product.status` flips to `discontinued`. Subsequent calls to the same endpoint are idempotent.
- ✅ A `product.ProductDiscontinued` event is emitted exactly once.
- ✅ **Sales** rejects new order lines for the SKU with `ProductDiscontinuedException` at `placeOrder` (consumer `sales.product-discontinued` stamps `sales.product_pricing.discontinued_at`). The check is asynchronous against the projection — between publish and inbox delivery, a placed order may slip through; manufacturing's classification gate (`rejected_not_manufactured`) is the defence-in-depth that catches it and routes the fulfilment saga to `stock_reservation_failed` → order header `rejected`.
- ✅ **Inventory** stamps `inventory.stock_item.discontinued_at`.
- ✅ **Manufacturing** retires `product_replenishment` (flips `is_purchased` / `is_manufactured` to false and stamps `discontinued_at`), clears the discontinued product's `product_active_bom`, and (§1.4 B.2, shipped 2026-05-15) **cascade-clears `product_active_bom` for every parent FG whose active BOM references the discontinued component**. New orders for the affected parents land at `rejected_no_bom` on `ManufacturingRequestedHandler` until a planner activates a new BOM revision that no longer references the discontinued SKU. WARN-logged per affected parent so impact is visible.
- ✅ **Purchasing's PR entry-point** rejects new requisitions for the SKU (`PurchaseRequisitionService.createManual` / `createForWorkOrderShortage` both gate via `DiscontinuedProductLookup`).
- ✅ **Manufacturing's BOM editor** (§1.4 B.3, shipped 2026-05-15) — `BomEditService.addLine` rejects new lines that name a discontinued component, throwing `BomComponentDiscontinuedException`. Mirrors purchasing's pattern; backed by the same `manufacturing.product_replenishment.discontinued_at` column.
- ✅ **Finance + reporting/atp** stamp their own `discontinued_at` for audit / UI greying.
- ✅ **In-flight is allowed to complete** (design decision, not a gap): SOs already at `submitted` / `in_fulfilment` / `ready_to_ship`, WOs already at `released` / `in_progress`, and PRs / POs already created continue to flow through. The discontinue gate runs at the entry points only — placing new orders, releasing new WOs, raising new PRs, adding new BOM lines. Hard-stop alternatives (cancel in-flight orders + scrap in-progress WOs) are out of scope.

**Pattern** — CQRS with **policy effect** on consumers (each context reacts in its own way to the same event) + **cascade-on-clear** for the parent-BOM hygiene
**Events** — `product.ProductDiscontinued`
**Services involved** — product (command), sales + inventory + manufacturing + purchasing + finance + reporting/atp (projections / gating)

> Covered by tier 2 unit tests `ProductDiscontinuedHandlerTest` (in each consuming service) plus `BomEditServiceTest.AddLine.rejects_when_component_is_discontinued` for the editor gate.

**Out-of-scope, captured here so they aren't re-discovered**

- **No reactivation path.** `Product.discontinue()` is one-way; `Product.Status.INACTIVE` is declared on the enum but no command writes it. A mistaken discontinue can only be reversed by direct DB edit. Other aggregates (Customer, Supplier) toggle `active` ↔ `inactive`; Product doesn't. Pull forward when a stakeholder asks for it.
- **No auto-reorder-suggestion job.** `inventory.stock_item.reorder_point` / `reorder_quantity` are projected but nothing reads them to emit PRs today; the discontinue flag is ready-to-honour whenever that job lands.
- **No activate-time discontinued-component check.** `BomEditService.addLine` gates, but a draft BOM authored before a component was discontinued can still be activated. Cheap follow-up (one extra lookup per component in `BomEditService.activate`); not story-blocking because the runtime cascade (B.2) plus the manufacturing classification gate would still surface the problem on first WO release.
- **SPA visual treatment** for discontinued rows in lists is a frontend concern not part of this story; `reporting.available_to_promise_view.discontinued_at` is stamped and ready for the UI to consume.

### Story 1.5 — Make-vs-buy classification ✅ *(not in original story set)*

**As** Emma
**I want** to mark a product as manufactured-internally / purchased-externally
**So that** make-to-order rejects orders for purchased-only SKUs up front (no work order) and accepts orders for manufactured ones

**Trigger** — `PUT /api/products/{id}/make-vs-buy`
**Outbox** — `product.MakeVsBuyChanged`
**Projections** — manufacturing's `ProductReplenishmentProjectionService`
**Behaviour** — `ManufacturingRequestedHandler` reads the projection before BOM lookup; an order line for a purchased-only product is dispatched as `rejected_not_manufactured`, the saga flips to `stock_reservation_failed`, and the order header to `rejected`.

---

## Demo 2 — CQRS: cross-context read models in reporting

Reporting is a pure read-side service. It owns *no* command APIs — it only consumes events and stitches them into views. Six projections wired today.

### Story 2.1 — Available-to-Promise for a SKU ✅

**As** Sarah
**I want** to see, for a SKU, how many units are physically on hand, how many are reserved, how many are inbound, and how many are committed on sales orders
**So that** I can promise a delivery date during a customer call without phoning inventory or purchasing

**Trigger** — `GET /api/atp/{productId}` *(keyed by productId, not SKU)* on reporting-service. List form: `GET /api/atp`.
**Acceptance criteria**
- ✅ Response merges data originating from inventory (on-hand, reserved-for-sales, reserved-for-production), purchasing (incoming-from-purchase), and manufacturing (incoming-from-production).
- ✅ Reporting-service holds **none** of this as authoritative state — every field traces to events from the owning context.
- ✅ Refreshes within one inbox poll of the relevant event.

**Read model** — `reporting.available_to_promise_view`
**Events consumed** — 7 events from inventory, manufacturing, and purchasing

### Story 2.2 — Production Planning Board 🚧

**As** Linda
**I want** to see all work orders, their current state, the materials each needs, and which materials are short
**So that** I can see at a glance what's blocked on raw materials vs. ready to start

**Trigger** — `GET /api/work-orders/{id}/board` (per-WO detail) and `GET /api/work-orders` (full list, every status, newest activity first) on reporting-service.
**Acceptance criteria**
- ✅ Each row shows the work order's status (`released`, `in_progress`, `completed`, `cancelled`), planned/completed quantity, material status (`pending`, `partially_reserved`, `reserved`, `shortage`), priority, shortage count + summary, and `open_purchase_orders_count` (how many shortage-driven POs are still outstanding for this WO).
- ✅ Shortage flag is computed from manufacturing's reservation events; shortage materials are summarised as a comma-separated list with a count.
- ✅ Refreshes within one inbox poll of any relevant event.
- ✅ List endpoint at `GET /api/work-orders` returns every status including `cancelled`; the SPA's two consumers slice client-side — `/production-board` (3-lane Kanban) renders `released` / `in_progress` / `completed` only, `/work-orders` (table view) exposes a status filter that includes `cancelled` for cancellation audit.
- ⏳ Scheduling-date columns on the projection — `expected_material_available_date`, `planned_start_date`, `planned_end_date` — stay null today. No current event carries scheduling data; would need a planning-module slice (parked in `dev-todo.md` §2.1).

**Read model** — `reporting.production_planning_board`
**Events consumed** — 10 events across 4 services:
- **manufacturing** — `WorkOrderCreated`, `OperationCompleted`, `WorkOrderManufacturingCompleted`, `WorkOrderCancelled`, `WorkOrderPriorityChanged`, `RawMaterialShortageDetected`
- **inventory** — `RawMaterialsReserved`, `GoodsReceived`
- **purchasing** — `PurchaseOrderCreated`
- **finance** — `SupplierPaymentMade`

The last three feed `ProductionPlanningProjection.setOpenPoCount` so Linda sees the live count of shortage-driven POs still outstanding per work order — a small but real cross-service join that demonstrates the read-model pattern.

**Out-of-scope, captured here so they aren't re-discovered**

- **Scheduling-date columns.** Need a planning module that emits start/end-date events; parked in `dev-todo.md` §2.1. Demo dataset doesn't pull on this gap.
- **Sub-assembly child grouping.** Sub-assembly WOs created via `WorkOrderReleaseService` recursion appear as standalone rows; the projection has no `parent_work_order_id` column. Linda groups by `order_number` mentally today. Adding hierarchy would mean a new column + handler change + SPA tree rendering — bigger slice, not story-blocking.
- **No retention policy.** Completed / cancelled rows accumulate forever. Fine on the demo dataset (5 SKUs); a real ERP would archive after N months.

### Story 2.3 — Material Shortage tracker ✅ *(not in original story set)*

**Trigger** — `GET /api/material-shortages` (active by default), `GET /api/material-shortages/{productId}`
**Behaviour** — One row per material with shortage; status walks `open → purchase_requested → purchase_ordered → resolved`.
**Events consumed** — `manufacturing.RawMaterialShortageDetected`, `purchasing.PurchaseRequisitionCreated` (work-order-shortage source), `purchasing.PurchaseOrderCreated`, `inventory.GoodsReceived`.

### Story 2.4 — Sales Order 360 ✅ *(not in original story set)*

**Trigger** — `GET /api/sales-orders/{id}/360`
**Behaviour** — Single-row view of an order's full timeline: order → manufacturing → shipment → invoice → payment. Every status column populated from a different inbox handler.

### Story 2.5 — Purchase Order tracking ✅ *(not in original story set)*

**Trigger** — `GET /api/purchase-orders/{id}/tracking`
**Behaviour** — Per-PO accumulator of received / invoiced / paid amounts; status walks `pending → partially_received → received → matched → paid`.

### Story 2.6 — Financial Dashboard Daily ✅

**Trigger** — `GET /api/financial-dashboard` (list by currency, AUD default), `GET /api/financial-dashboard/{date}` (per-day row), `GET /api/financial-dashboard/snapshot` (as-of-now totals).
**Acceptance criteria**
- ✅ One row per `(dashboard_date, currency_code)` with a **hybrid shape**:
  - **Flow columns** (`sales_revenue`, `cost_of_goods_sold`, `gross_profit`, `cash_received`, `cash_paid`) — per-day deltas, written incrementally by event handlers on `CustomerInvoiceCreated` / `SupplierInvoiceApproved` / `CustomerPaymentReceived` / `SupplierPaymentMade`. Each handler bumps its column on the row matching the event's `occurredAt::date`.
  - **Balance columns** (`accounts_receivable`, `accounts_payable`, `inventory_value`, `open_sales_orders_count`, `open_purchase_orders_count`, `open_work_orders_count`) — point-in-time balances, overwritten every 60 s by `FinancialDashboardBalanceWorker.refresh` via SUM-window over reporting's local projections (SO360 → AR + open-SO; PO tracking → AP + open-PO; ATP × `product_standard_cost` → inventory_value; production_planning_board → open-WO).
- ✅ `GET /api/financial-dashboard/{date}` returns historical balances — Daniel can ask "what was AR on 2026-05-08?" and get the value the rollup worker persisted on that date, not just an as-of-now read.
- ✅ `GET /api/financial-dashboard/snapshot` is the real-time as-of-now view, computed synchronously on every request via the same SUM-window logic. Cheap (4 small queries against indexed projections); paired with the worker so the SPA gets immediate feedback while the daily row catches up within a minute.
- ✅ `FinancialDashboardSnapshot` carries `wipValue` (always `0` today) so the wire shape stays uniform with `FinancialDashboardView` — flip to a real computation when the WIP-costing decision lands.
- ⏳ `wip_value` itself stays at `0`. Gated on a costing decision (LIFO / FIFO / weighted-avg) for `manufacturing.wip_balance.average_cost`. Schema column + DTO field + SPA tile are all wired through; only the value derivation is parked.

**Read model** — `reporting.financial_dashboard_daily`
**Events consumed** — `finance.CustomerInvoiceCreated`, `finance.SupplierInvoiceApproved`, `finance.CustomerPaymentReceived`, `finance.SupplierPaymentMade` (flow-column writers). The balance columns aren't event-driven — they're computed via the rollup worker reading reporting's own SO360 / PO tracking / ATP / production_planning_board projections, which are themselves event-driven from ~20 cross-service events.

> Covered by integration via the end-to-end harness E2E tests (`OrderToCashHappyPathTest`, `PurchaseToPayHappyPathTest`) which drive every flow-column writer; daily-balance rollup runs on the reporting service in production and is verified live in the demo runthrough.

**Out-of-scope, captured here so they aren't re-discovered**

- **`wip_value` derivation.** Gated on the costing-method decision. Schema column + DTO field + SPA tile are in place; the worker writes 0 today.
- **Currency mismatch on `inventory_value` vs AR / AP.** The `?currency=` parameter filters AR / AP / open-SO / open-PO on the **transaction** currency; `inventory_value` filters on the **product valuation** currency (`product_standard_cost.currency_code`). These can diverge in a multi-currency rollout. AUD-only demo doesn't bite; documented inline at `JdbcFinancialDashboardQueryPort.findSnapshot`.
- **Multi-currency SPA selector.** `erp-web-ui/.../FinancialDashboard.tsx` hardcodes `CURRENCY = "AUD"`. Backend supports `?currency=USD`; frontend doesn't expose a switcher. Per the project-level *"multi-currency GL consolidation is low priority"* memo.
- **Customer collections widget.** `CustomerDeactivatedHandler` writes `reporting.customer_dashboard_status` for AR-collections targeting, but no SPA widget surfaces the projection today. Land alongside an AR-aging slice.
- **Snapshot caching.** Computed on every request via 3 small SUM queries; fine for the demo, would want materialised-view or cache backing at real scale.

---

## Demo 3 — Saga: sales-order fulfilment ✅

`sales.sales_order_fulfilment_saga` orchestrates one customer order from placement to invoice settlement.

### Story 3.1 — Order runs end-to-end with no manual intervention ✅

**As** Sarah
**I want** to place a sales order and watch it auto-fulfil through to invoice
**So that** I don't have to babysit each step

**Trigger** — `POST /api/sales-orders` (sales-service)
**Saga state machine** (`sales.sales_order_fulfilment_saga.saga_state`):
1. `started` → worker emits `sales.StockReservationRequested`
2. `stock_reservation_requested` → consume `inventory.StockReserved`
3. `stock_reserved` → worker emits `sales.ManufacturingRequested`
4. `manufacturing_requested` → consume `manufacturing.WorkOrderCreated`
5. `manufacturing_in_progress` → consume `manufacturing.WorkOrderManufacturingCompleted`
6. `ready_to_ship` → operator posts shipment via `POST /api/shipments` (inventory)
7. `goods_shipped` → consume `finance.CustomerInvoiceCreated` (auto-created from `sales.SalesOrderShipped`)
8. `invoice_created` → consume `finance.CustomerPaymentReceived` (full settlement)
9. `completed`

Partial customer payments single-hop to `invoice_paid` and park.

**Important framing:** every order with an active BOM goes through make-to-order. The reservation is the customer-facing slot, supply comes from manufacturing. There is no separate "stock-only" path that bypasses manufacturing.

**Defence-in-depth on step 6** (shipped 2026-05-12): inventory projects `inventory.sales_order_line_facts` from `sales.SalesOrderPlaced` so that `POST /api/shipments` rejects with 400 any line whose `(salesOrderLineId, productId)` pair doesn't match the originating SO. Catches a buggy / malicious client that posts the wrong product for an SO line — without this check the symptom was either silent stock corruption on the wrong product or an opaque 500 when `stock_balance.on_hand_quantity >= 0` eventually fired. Lines posted with `salesOrderLineId = null` (unlinked manual shipments) skip the check.

**Pattern** — Saga / process manager with **command-event** style
**Saga state** — `sales.sales_order_fulfilment_saga`

> Covered by tier 2 unit tests for every inbox handler that drives the saga (`StockReservedHandlerTest`, `ManufacturingDispatchedHandlerTest`, `WorkOrderManufacturingCompletedHandlerTest`, `ShipmentPostedHandler` exists, `CustomerInvoiceCreatedHandlerTest` exists, `CustomerPaymentReceivedHandlerTest`).

---

## Demo 4 — Saga: failure paths

### Story 4.1 — Customer cancels an order mid-fulfilment ✅

**As** Sarah
**I want** to cancel a sales order that's been reserved but not yet shipped
**So that** the stock is released and no invoice is generated

**Status** — Shipped 2026-05-06. `POST /api/sales-orders/{id}/cancel` body `{reason}`. Header flips to `'cancelled'` immediately + `cancelled_at = now()`; the fulfilment saga flips to `'compensating'` in the same transaction and emits `sales.SalesOrderCancellationRequested`. Inventory's handler releases the stock reservation (`reserved_quantity` decremented, header `'released'`) and acks with `inventory.SalesOrderCancellationApplied`. Manufacturing's handler cancels every active WO for the order (`WorkOrder.cancel(reason)` → status `'cancelled'` + emits `manufacturing.WorkOrderCancelled`), force-flips associated `make_to_order_saga`(s) to `'compensated'`, and acks with `manufacturing.SalesOrderCancellationApplied` (always, even with zero WOs). Inventory's `WorkOrderCancelledHandler` releases the raw-material reservation. When both acks land in sales, the saga advances `compensating → compensated` and emits `sales.SalesOrderCompensated`; reporting consumes that and flips `sales_order_360_view.order_status` to `'cancelled'`.

Cancellation rejected with HTTP 409 once the order is past `goods_shipped` (credit-note / return-goods flow is dev-todo §4.2, deferred). Hard-cancel by design — WIP is written off; soft-cancel ("let production finish then scrap") is dev-todo §3.7.

**Pattern** — Saga compensation / **rollback semantics across services**

### Story 4.2 — Reservation comes back partial or failed 🚧

**As** Sarah
**I want** an order that can't be fully reserved to either make-to-order the gap or fail clearly with no half-fulfilled state
**So that** I'm never stuck guessing

**Status** — Partial-reservation case ✅: `StockReservedHandler` stashes the shortage on saga.data and still advances to `stock_reserved`; the worker emits `ManufacturingRequested` covering the shortage and manufacturing fills it via make-to-order (Story 5.x).

Fail-fast case 🚧: when manufacturing reports zero accepted lines (`ManufacturingDispatched` with all `rejected_*`), `ManufacturingDispatchedHandler` flips the saga to `stock_reservation_failed` and the order header to `rejected`. ⏳ A user-visible domain error returned to the original `POST` (today the order goes through the async path).

**Events consumed** — `inventory.StockReserved` (with status `reserved` / `partially_reserved` / `failed`), `manufacturing.ManufacturingDispatched`

---

## Demo 5 — Saga: make-to-order ✅

`manufacturing.make_to_order_saga` — one row per work order. Sub-assembly children get their own saga at `work_order_created`.

### Story 5.1 — Order with sufficient raw materials ✅

**End-to-end flow**
1. Sales emits `sales.ManufacturingRequested`.
2. Manufacturing's `ManufacturingRequestedHandler` releases work orders (recursing on `sub_assembly` BOM lines) and stamps `manufacturing.ManufacturingDispatched` back to sales (carries the per-line `accepted` / `rejected_*` outcome).
3. Make-to-order saga: `started` → `work_order_created` → `raw_material_reservation_requested` → `raw_materials_reserved` (driven by `inventory.RawMaterialsReserved` inbox).
4. Operator completes operations: `POST /api/work-orders/{id}/operations/{seq}/complete` per op. The last op transitions the saga to `completed` in the same txn.
5. Parent-on-children gating: a parent WO whose ops are all done holds at `in_progress` while any sub-assembly child is unfinished; when the last child completes, the operation service cascades up the parent chain and finishes the parent.

**Pattern** — **Saga-to-saga handoff**: fulfilment saga signals demand, make-to-order saga responds.
**Saga state** — `manufacturing.make_to_order_saga`

### Story 5.2 — Work order missing a raw material ✅

**Setup** — Some component is short (e.g. only 2 of `RM-LEG-001` on hand, need 4 for the WO).

**Acceptance criteria**
- ✅ Make-to-order saga lands on `raw_material_shortage` (driven by `inventory.RawMaterialsReserved` with status `partially_reserved` / `failed`); per-product shortage stashed on saga.data.
- ✅ `manufacturing.RawMaterialShortageDetected` emitted; purchasing's handler creates a `purchase_requisition` with `source_type='work_order_shortage'` (auto-converts to PO in same txn — kicks off Demo 6).
- ✅ Reporting `material_shortage_view` shows the gap in real time for Linda.
- ✅ Once the inbound PO is received and goods are put away, manufacturing's `GoodsReceivedHandler` decrements the stash; on full coverage, un-parks the saga back to `work_order_created` and re-emits the reservation request.

**Pattern** — Saga that **waits indefinitely** for an external trigger (no polling — event-driven wake-up).

> Covered by tier 2 unit tests `RawMaterialsReservedHandlerTest` and `GoodsReceivedHandlerTest`.

---

## Demo 6 — Saga: purchase-to-pay ✅

`purchasing.purchase_to_pay_saga` — one row per PO, inserted at `started` in the same txn the PO is created.

### Story 6.1 — PR → PO → goods receipt → invoice match → payment ✅

**Trigger** — `POST /api/purchase-requisitions` (Tom — manual or auto from work-order shortage)

**Flow**
1. ✅ PR creates a PO at `'draft'` inside the same txn; saga inserted at `started`. Tom approves via `POST /api/purchase-orders/{id}/approve` body `{approver, reason}` to flip the header → `'sent'` and emit `purchasing.PurchaseOrderApproved`. Saga walks `started → purchase_order_approved → waiting_for_goods` (last hop on the worker tick). Shortage-driven auto-PRs auto-approve inline when `northwood.purchasing.shortagePoAutoApprove=true` (default), so the make-to-order saga still flows without a human.
2. ✅ Tom posts goods receipt. Saga: `waiting_for_goods` → `goods_received` (driven by `inventory.GoodsReceived`, only on full receipt — partial receipts park the PO header at `partially_received` and leave the saga in `waiting_for_goods`).
3. ✅ Olivia records the supplier invoice via `POST /api/supplier-invoices`. Finance runs **quantity + price-variance** 3-way match: per-line invoice unit price is compared against the PO line's snapshotted unit_price; relative variance > `northwood.finance.match.priceTolerancePercent` (default 2.0%) parks the invoice at `three_way_match_failed` (a `SupplierInvoice.status`, **not** a saga state — the saga itself stays at `goods_received`). On match, emits `finance.SupplierInvoiceApproved` and the saga advances `goods_received → supplier_invoice_approved`.
4. ✅ **Variance resolution fork** (manual-reject closure shipped 2026-05-15). Manual review queue at `GET /api/supplier-invoices/pending-review`. `POST /{id}/manual-approve` body `{reviewer, reason}` re-emits `SupplierInvoiceApproved` and the saga continues as in step 3. `POST /{id}/reject` flips the invoice to `cancelled` and emits `finance.SupplierInvoiceRejected`; purchasing's handler lands the saga in terminal `failed`.
5. ✅ Olivia pays via `POST /api/payments` (single invoice) or `POST /api/payments/multi` (one cheque, multiple invoices — single supplier, single currency). On full settlement: → `completed`, PO header flips to `paid`. On partial: → `supplier_payment_made`, parks until the next allocation closes it.

**Acceptance criteria**
- ✅ Each step is independently idempotent — replaying any inbox message has no side effects.
- ✅ **Defence-in-depth on goods receipt** (shipped 2026-05-12): inventory projects `inventory.purchase_order_line_facts` from `purchasing.PurchaseOrderCreated` so that `POST /api/goods-receipts` rejects with 400 any line whose `(purchaseOrderLineId, productId)` pair doesn't match the originating PO line. Lines posted with `purchaseOrderLineId = null` (unlinked manual receipts) skip the check.

**Saga state** — `purchasing.purchase_to_pay_saga` (`ALL_STATES` = `started`, `purchase_order_approved`, `waiting_for_goods`, `goods_received`, `supplier_invoice_approved`, `supplier_payment_made`, `completed`, `failed`)

> Covered by tier 2 unit test `SupplierPaymentMadeHandlerTest`, the saga-state unit test `JdbcPurchaseToPaySagaManagerTest`, and the end-to-end harness tests `PurchaseToPayHappyPathTest` (auto-match happy path) + `PurchaseToPayRejectionPathTest` (variance → manual-reject → terminal `failed`).

**Out-of-scope, captured here so they aren't re-discovered**

- **No PO-cancellation flow.** The saga has no `compensating` / `compensated` states. The cancel-order saga shipped 2026-05-06 covers sales + manufacturing only; if a supplier won't deliver, or Tom needs to abort a PO, there is no command path today. Future slice; not story-blocking.
- **No over-receipt rejection.** `GoodsReceiptService` doesn't validate `receivedQuantity` against the PO line's remaining `(orderedQuantity − alreadyReceived)`. Receiving 50 against a PO line of 40 still advances the saga to `goods_received`. Defensive validation, not story-blocking.
- **Supplier price-list authoring** (`purchasing.supplier_product_price` + `SupplierProductPriceService.setPrice` + `SupplierProductPriceChanged`) exists and feeds the price-variance reference — documented in `build-status.md` but not narrated as a Story step here.
- **Multi-currency P2P** (multi-pay across currencies) is rejected at the application layer; consistent with the project-level "multi-currency GL consolidation is low priority" memo.

---

## Demo 7 — End-to-end: all three sagas + GL posting ✅

The showpiece. One sales order kicks off all three sagas in series; finance posts six balanced GL pairs along the way.

### Story 7.1 — Big order touches every service ✅

See `demo-script.md` § Demo 7 for the runbook. All steps work end-to-end today.

**What the audience sees on screens**
- Sales-Order 360 view, Production Planning Board, Available-to-Promise view, Material Shortage view, Purchase Order tracking, Financial Dashboard.
- Three saga state tables: rows progress in lock-step.
- `finance.journal_entry_header` / `_line`: six pairs balanced (Dr=Cr enforced by deferred trigger `enforce_journal_balance` at COMMIT).

**Acceptance criteria**
- ✅ The whole flow runs to completion without manual intervention except: goods receipt, supplier invoice, supplier payment, customer payment (each one REST call).
- ✅ Every monetary effect balances: sum of debits = sum of credits in `finance.journal_entry_line`; outstanding AR + cash collected = total customer-invoice value; outstanding AP + cash paid = total supplier-invoice value.
- ✅ Replaying any event from any inbox is a no-op.
- ✅ Disabling the bus mid-flow and re-enabling it: the system catches up; no events are lost (transactional outbox guarantee).

**Patterns demonstrated**
- CQRS read models drive every screen the audience watches.
- Three sagas run concurrently and exchange signals via events, not direct calls.
- Compensations are reserved in the schema but not exercised in the happy path of this demo (the failure variants in Demo 4 stay 🚧 / ⏳ until cancellation lands).

---

## Suggested demo run-order

1. **1.1 → 1.3** (3 min) — establish the upstream/downstream pattern with simple commands.
2. **2.1** (1 min) — show one cross-context read view; introduce reporting.
3. **3.1** (3 min) — first complete saga; the audience sees state transitions live.
4. **4.2** (2 min) — failure path; introduces the partial-reservation handoff into make-to-order.
5. **5.2** (4 min) — saga waits for goods, naturally completes the unfinished part of 4.2.
6. **6.1** (3 min) — last saga; introduces 3-way match (quantity-only today).
7. **7.1** (8 min) — the full thing.

Total ~25 minutes; ~35 with Q&A breaks. Skip 2.2 / 5.1 if pressed for time.

---

## What's deliberately out of scope (and why)

- **Authentication / users / multi-tenancy.** Architectural patterns are the point.
- **UI mock-ups.** Screens shown above are reporting-service `GET` endpoints + raw JSON or `psql` queries.
- **Concrete event schema versions.** Each event has an `eventVersion` field and additive evolution rules — separate design doc.
- **Performance / load characteristics.** Showcase aims for *correctness*, not throughput.

## Low-priority items not pulled forward

These would land last (per project memory):

- Multi-currency GL consolidation (architecture is in place, no demand pull).
- Customer credit notes / refunds (forward AR happy path is sufficient).
- GST tax-account split (current tax-inclusive posting works for the demo).
- Perpetual-inventory daily balance snapshots (already implemented for flows; the snapshot side adds no new patterns).
