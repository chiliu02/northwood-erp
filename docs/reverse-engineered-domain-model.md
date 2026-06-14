# Northwood ERP — Domain Model Reverse-Engineered from the Schema

A strategic and tactical Domain-Driven Design analysis derived from `config/postgresql/northwood_erp.sql` (the baseline schema — Liquibase changelogs start empty, so the baseline is the source of truth) cross-checked against the service code. This document treats the database schemas as evidence of bounded-context decomposition and works backwards to the domain model: aggregates, entities, value objects, services, and events.

A note on direction: a real DDD effort starts with conversations, event storming, and the ubiquitous language, then arrives at a model — the schema is a downstream artifact. Reading a schema *as if* it were the model produces useful inferences but conflates "what we persisted" with "what we modelled". Treat this document as a reverse-engineered map; gaps and oddities point at places where the design conversation should still happen.

A note on naming: tables are **singular**, and a master-detail parent takes the `_header` suffix only when its child is `_line` (`sales_order_header` + `sales_order_line`; but `work_order` + `work_order_material`). FK columns end in `_id`. This document uses the PascalCase **aggregate/class** names from the Java model and the snake_case **table** names from the schema interchangeably; the mapping is 1:1 unless noted.

---

## 1. Strategic Design

### 1.1 Bounded contexts at a glance

The schema is partitioned into seven PostgreSQL schemas, and each one corresponds to a bounded context. The split is load-bearing: each context has its own command tables, its own `outbox_message` / `inbox_message`, its own `<service>_service` database role, and its own connection pool that sets `search_path = <service>, shared`. The database-per-service split is a config change, not a refactor. The reporting context is the exception — it is a CQRS read-side derived from events emitted by the others, not a context with its own ubiquitous language.

| Context | Core question it answers | Aggregate roots | Saga / process manager |
|---|---|---|---|
| **Product Master** | What can we make, buy, or sell, and how do we replenish it? | `Product` | — |
| **Sales** | What did the customer order, and where is that order in fulfilment? | `Customer`, `SalesOrder` | `SalesOrderFulfilmentSaga` |
| **Inventory** | What stock is where, what's reserved against it, and what needs replenishing? | `StockReservation`, `GoodsReceipt`, `Shipment`, `StockAdjustment`, `ReplenishmentRequest` | — |
| **Manufacturing** | How do we build a finished good, and is a build in progress? | `Bom`, `WorkOrder` | `WorkOrderSaga` |
| **Purchasing** | What do we need to buy, from whom, at what price, and has it arrived? | `Supplier`, `SupplierProductPrice`, `PurchaseRequisition`, `PurchaseOrder` | `PurchaseToPaySaga` |
| **Finance** | What do we owe, what are we owed, and is the ledger balanced? | `CustomerInvoice`, `SupplierInvoice`, `Payment`, `JournalEntry` | — |
| **Reporting** | (Read-side) What does the world look like right now? | (read models, not aggregates) | — |

The "core" subdomain in Eric Evans' sense is probably **Manufacturing** — that's the part of Northwood Furniture that competitors can't easily replicate, where domain expertise is concentrated (BOMs, sub-assembly recursion, routing operations, scrap factors, WIP costing). Sales, Inventory, Purchasing, and Finance are **supporting subdomains**: necessary but commoditised. Product Master is a **generic subdomain** that could plausibly be replaced by an off-the-shelf PIM. Reporting is infrastructural.

A note on what is *not* in the aggregate columns above: several tables that look like aggregates are actually **projections** (read models maintained by event handlers) or **reference data** (read-only seed tables). `product_card` (every consuming service keeps a snapshot copy of product master), `stock_balance`, `wip_balance`, the `*_facts` tables, `gl_account`, `tax_code`, `unit_of_measure`, `warehouse`, and `exchange_rate` are not aggregate roots. This is the *deltas get aggregates, totals and snapshots get projections* rule: a concept earns an aggregate only when it emits its own delta with identity, lifecycle, and downstream consumers; running sums and upstream-owned snapshots get projection-shaped ports.

### 1.2 Context map

Relationships between contexts and the integration pattern in use (Vernon's vocabulary):

```
                                  ┌───────────────────┐
                                  │  Product Master   │  Open Host /
                                  │   (upstream U)    │  Published Language
                                  └─────────┬─────────┘
                                            │ ProductCreated, SalesPriceChanged,
                                            │ ReorderPolicyChanged, MakeVsBuyChanged,
                                            │ ActiveBomChanged, ApprovedVendorListChanged …
         ┌──────────────┬───────────────────┼───────────────────┬──────────────┐
         ▼              ▼                   ▼                   ▼              ▼
   ┌──────────┐    ┌──────────┐        ┌─────────────┐    ┌────────────┐  ┌──────────┐
   │  Sales   │    │Inventory │        │Manufacturing│    │ Purchasing │  │ Finance  │
   └────┬─────┘    └────┬─────┘        └──────┬──────┘    └─────┬──────┘  └────┬─────┘
        │ StockReserv.  │  ▲                  │  ▲              │   ▲          │
        │ Requested ───►│  │ StockReserved    │  │ RawMaterials │   │          │
        │               │  │ RawMaterials-    │  │ Reservation- │   │          │
        │◄ ReplenishmentFulfilled / Cancelled │  │ Requested    │   │          │
        │               │  └──────────────────┘  └──────────────┘   │          │
        │               │  ReplenishmentRequested (target=mfg/pur)  │          │
        │               └───────────────────────────────────────────┘          │
        │ ShipmentPosted / GoodsReceived / SupplierInvoiceApproved / Payments  │
        └──────────────────────────┬───────────────────────────────────────────┘
                                   ▼
                            ┌──────────────┐
                            │  Reporting   │  Conformist (consumes the
                            └──────────────┘  published language as-is)
```

A walk through the major edges:

**Product Master → everything else** is *Open Host Service* + *Published Language*. Product master owns the canonical SKU, name, type, base UoM, pricing, reorder policy, make-vs-buy flags, replenishment strategy, valuation class, active BOM pointer, and approved-vendor list. Every other context subscribes to product-master events and projects the bits it needs into its own local `product_card` table (plus `product_approved_vendor` in manufacturing and purchasing). The denormalisation is deliberate — it preserves the historical name of a product on an old invoice even after the master record is renamed, and it keeps the schema-per-service boundary intact (no cross-schema reads). Events are **fine-grained**: there is no compound `ProductPricingChanged`; `SalesPriceChanged` and `StandardCostChanged` fire separately, and policy facets each get their own event (`ReorderPolicyChanged`, `ReplenishmentStrategyChanged`, `ValuationClassChanged`, `MakeVsBuyChanged`, `ActiveBomChanged`, `PlanningTimeFenceChanged`, `ApprovedVendorListChanged`).

**Sales ↔ Inventory** is a *Customer/Supplier* relationship where Sales is the customer. Sales emits `StockReservationRequested` for a placed order; Inventory replies with `StockReserved` (status `reserved` / `partially_reserved` / `failed`). The integration is event-driven via the outbox; there are no synchronous calls and no foreign keys across the schema boundary (correct for the database-per-service target, but it does make referential integrity the saga's responsibility, not the database's).

**Supply (manufacturing or purchasing) is brokered by Inventory, not requested directly by Sales.** When a reservation is short — or for a `to_order` line that is pegged to dedicated supply — Inventory raises a `ReplenishmentRequest` and emits `ReplenishmentRequested` with `target_service` set to `manufacturing` or `purchasing` (chosen from the product's make-vs-buy flag). The target context dispatches a work order or a purchase requisition and acks with `ReplenishmentDispatched` (or `ReplenishmentUndispatchable` when it can't — e.g. no active BOM, no approved vendor). When the supply lands, Inventory emits `ReplenishmentFulfilled` (or `ReplenishmentCancelled`), and the originating sales saga un-parks. This *reorder-point breach and work-order shortage both flow through one inventory concept* is the central decoupling in the system: **Manufacturing and Purchasing do not talk to each other, and Sales does not talk to either of them directly** — Inventory is the single replenishment broker.

**Manufacturing ↔ Inventory** is *Customer/Supplier* in both directions. A released work order emits `RawMaterialReservationRequested`; Inventory replies with `RawMaterialsReserved`. A raw-material shortage detected by Inventory during that reservation becomes a `ReplenishmentRequest` targeted back at purchasing (the make-vs-buy split routes raw materials to purchase, sub-assemblies to manufacture). On completion, Manufacturing emits `WorkOrderManufacturingCompleted`, which Inventory turns into a finished-goods receipt and a replenishment-fulfilled close-out.

**Purchasing → Finance** is *Customer/Supplier* with Purchasing upstream. Goods receipts (`GoodsReceived`, emitted by Inventory) and supplier invoices feed the three-way match. Finance records the supplier invoice, runs the match, and emits `SupplierInvoiceApproved` / `SupplierInvoiceRejected`; later `SupplierPaymentMade`. The P2P saga consumes those; Finance never writes back into purchasing's schema.

**Sales → Finance** is the same shape: a shipment (`ShipmentPosted`) triggers a commercial invoice; prepayment/deposit terms trigger up-front invoices at placement (`PrepaymentInvoiceRequested` / `DepositInvoiceRequested`). `CustomerInvoiceCreated` and `CustomerPaymentReceived` feed the sales-order fulfilment saga's completion gate and the 360 read model.

**Reporting** is a *Conformist*. It does not negotiate translations; it ingests the published language verbatim, maintaining read models keyed by aggregate IDs. The trade-off is acceptance: if a command-context event schema changes, reporting changes. This is fine because events carry `event_version`.

A note on what's *not* on the diagram: there is no Anti-Corruption Layer yet, because there are no third-party systems integrated. When a real shipping carrier, payment gateway, or supplier EDI feed is added, an ACL belongs at that boundary — almost certainly inside Inventory (carriers), Finance (gateways), and Purchasing (EDI).

---

## 2. Tactical Design Per Context

For each context: ubiquitous language, aggregates with their composition, value objects, domain services (logic that doesn't fit on an aggregate but is still domain logic), application services (orchestration / use-case entry points), and domain events (with their wire-format `EVENT_TYPE`). Where the schema reveals an issue with the modelling, it is called out inline.

### 2.1 Product Master

**Ubiquitous language.** Product, SKU, finished good, raw material, semi-finished good, service, base unit of measure, reorder point, reorder quantity, replenishment strategy (to stock / to order), make-vs-buy, standard cost, sales price, valuation class, planning time fence, active BOM, approved vendor, status (active / inactive / discontinued).

**Aggregates.**

```
Product (root)
├─ product_id, sku ──► Sku VO, name, description
├─ product_type {raw_material, finished_good, semi_finished_good, service}
├─ base_uom_id ──► product.unit_of_measure (reference data, by ID)
├─ flags: is_stocked, is_purchased, is_manufactured, is_sellable
├─ Money: sales_price, standard_cost
├─ ReorderPolicy: reorder_point, reorder_quantity
├─ replenishment_strategy {to_stock, to_order}   (null for services)
├─ valuation_class {raw_materials, finished_goods, semi_finished_goods}
├─ active_bom_id (pointer into manufacturing.bom_header), planning_time_fence_days
├─ approved_vendors: List[ApprovedVendor]   (supplierId, code, name, preferred)
└─ status, version, created_at, updated_at, created_by, last_modified_by
```

`Product` is the only aggregate root in this context. **`UnitOfMeasure` is not an aggregate** — `product.unit_of_measure` is read-only reference/seed data with no mutating class, repository, or events; `Product` references it by `base_uom_id`. (An earlier reading of this document modelled UoM as a separate aggregate with a `UnitOfMeasureRegistered` event; neither the class nor the event exists.)

**Value objects.** `Sku` (validated `^[A-Z][A-Z0-9_-]{1,49}$`, in the shared kernel), `Money` (amount + currency, shared kernel), `ProductType`, `ReplenishmentStrategy`, `ValuationClass`, `Status` (all nested enums carrying `code()` / `fromCode()`), `ReorderPolicy` (the `reorder_point` + `reorder_quantity` pair), and `ApprovedVendor` (a value-object record projected as a child collection to `product.approved_vendor`). The schema flattens these into columns, which is normal for a relational projection.

**Domain services.** None heavy. The replenishment *decision* (whether on-hand has crossed below reorder point) deliberately lives in **inventory**, where stock is, operating on product-master policy projected via events — not on `Product`.

**Application services.** `ProductService` — `createProduct`, `changeSalesPrice`, `changeStandardCost`, `setReorderPolicy`, `changeReplenishmentStrategy`, `setValuationClass`, `changeMakeVsBuy`, `setPlanningTimeFence`, `activateBom`, `setApprovedVendors`, `discontinue` (plus read methods).

**Domain events.** `ProductCreated`, `SalesPriceChanged`, `StandardCostChanged`, `ReorderPolicyChanged`, `ReplenishmentStrategyChanged`, `ValuationClassChanged`, `MakeVsBuyChanged`, `PlanningTimeFenceChanged`, `ActiveBomChanged`, `ApprovedVendorListChanged`, `ProductDiscontinued`. Each carries `EVENT_TYPE = "product.<ClassName>"`. There is no reactivation event today (status mutates without one).

### 2.2 Sales

**Ubiquitous language.** Customer, sales order, order line, open, partially reserved, reserved, partially shipped, shipped, completed, cancelled, rejected, ordered quantity, reserved quantity, shipped quantity, backordered quantity (computed), manufacturing-required quantity, requested delivery date, payment terms (on shipment / prepayment / cash on delivery / deposit), deposit percent.

**Aggregates.**

```
Customer (root)
├─ customer_id, customer_code, name
├─ ContactInfo: email, phone
├─ Address: billing_address, shipping_address
├─ default_payment_terms   (snapshotted onto orders at placement)
└─ status {active, inactive, blocked}, version

SalesOrder (root)              ◄── transactional consistency boundary
├─ sales_order_header_id, order_number
├─ customer_id ──► Customer (by ID; customer_code + customer_name snapshotted)
├─ Money: subtotal_amount, tax_amount, total_amount
├─ payment_terms {on_shipment, prepayment, cash_on_delivery, deposit}
├─ deposit_percent  (null except 'deposit' terms; 0 < pct < 100)
├─ currency_code, exchange_rate
├─ status  (a fold of line statuses — see below)
├─ order_date, requested_delivery_date, cancelled_at, completed_at, version
└─ Lines: List[SalesOrderLine]    ◄── child entities
       ├─ line_number, product_id, product_sku, product_name
       ├─ Quantity: ordered, reserved, shipped, backordered (computed), mfg_required
       ├─ Money: unit_price, tax_amount, line_total
       ├─ TaxApplication: tax_code, tax_rate
       └─ line_status

SalesOrderFulfilmentSaga (process manager — *not* a domain aggregate)
├─ saga_id, sales_order_header_id (unique)
├─ saga_state, current_step, data (JSONB), retry_count, last_error
├─ next_retry_at, lease_owner, lease_expires_at, version, trace_id
└─ created_at, updated_at, completed_at
```

The `SalesOrder` boundary is `(sales_order_header + sales_order_line)`. The status-history table is outside the boundary — it's audit/reporting. Customer is *not* inside the aggregate; the order references it by ID and **snapshots** `customer_code` / `customer_name` at placement, which is correct DDD: those are part of the order's historical state and don't move when the customer is renamed (a `CustomerNameChanged` does not ripple into existing orders).

**Design note — status is a faithful fold, not an orthogonal flag set.** The header `status` is a brainless, lossy fold over the line statuses, with a vocabulary that *equals* the line vocabulary in its fold region (`open ⊏ partially_reserved ⊏ reserved ⊏ partially_shipped ⊏ shipped`) plus three top-down order-level terminals (`completed`, `cancelled`, `rejected`). Because the header label is derived, cancel/amend gates read **line predicates** (`anyLineShipped()`, `isTerminal()`), never a header-status allow-list. The cross-cutting facts a naive design would jam onto the header (stock reserved? invoice paid?) are reassembled by `reporting.sales_order_360_view`, which keeps its own deliberately-separate `order_status` / `stock_status` / `shipment_status` / `invoice_status` / `payment_status` columns. (See `docs/composed-state-machines.html` for the rollup algebra and the per-axis / per-detail-set rule.)

**Entities.** `SalesOrder` (root), `SalesOrderLine` (child entity — identity via `line_number`, no independent lifecycle; `backordered_quantity` is computed `ordered − shipped`, floored at zero, not stored).

**Value objects.** `Money`, `Quantity`, `TaxApplication`, `PaymentTerms`, `LineStatus`, the header `Status` (nested enums with `code()`), `Address`, `ContactInfo`.

**Domain services.** `OrderPricingService` (subtotal/tax/total from lines); backorder-vs-reject policy lives on the line/saga rather than a standalone service.

**Application services.** `SalesOrderService` — `placeOrder`, `addLine`, `changeLineQuantity`, `changeLineUnitPrice`, `removeLine`, `cancelOrder`, `recordShipped`. `CustomerService` — `registerCustomer`, `changeName`, `changeContact`, `changeBillingAddress`, `changeShippingAddress`, `deactivate`.

**Domain events.** Order: `SalesOrderPlaced`, `SalesOrderLineAdded`, `SalesOrderLineRemoved`, `SalesOrderLineQuantityChanged`, `StockReservationRequested`, `PrepaymentInvoiceRequested`, `DepositInvoiceRequested`, `SalesOrderUpfrontPaymentSettled`, `SalesOrderReadyToShip`, `SalesOrderShipped`, `SalesOrderCancellationRequested`, `SalesOrderCompensated`. Customer: `CustomerRegistered`, `CustomerNameChanged`, `CustomerContactChanged`, `CustomerAddressChanged`, `CustomerDeactivated`. (There is no `Drafted` / `Submitted` / `Confirmed` lifecycle — an order is `placed` in one step; subsequent state is the line fold.)

**Saga.** `SalesOrderFulfilmentSaga` is a **pruned orchestration**: its states are only the points it actually branches on, not a mirror of the order's status. States: `started` → `awaiting_release` (planning-time-fence park, woken by the poll) / `awaiting_prepayment` → `prepaid` (up-front-payment gate) → `stock_reservation_requested` → `stock_reservation_incomplete` (short — wait for `ReplenishmentFulfilled`) → `supply_secured` (completion gate: accumulate `orderShipped` + `orderSettled` latches) → `completed`; plus `rejected` (unsourceable), `compensating` / `compensated` (cancel path), `failed`. The post-supply ship → invoice → pay status is the line fold + the 360, so those pass-through states were dropped.

### 2.3 Inventory

**Ubiquitous language.** Warehouse, product card, on-hand quantity, reserved quantity, available quantity, work-in-progress balance, reservation, stock movement (purchase receipt, sales shipment, material issue, finished-goods receipt, adjustment, reservation release), goods receipt, shipment, stock adjustment, replenishment request, reorder-point breach, work-order shortage, sales-order shortage, order-pegged supply, average cost.

**Aggregates.** Inventory has the most aggregate roots, which fits — it really does have several largely-independent transactional areas.

```
StockReservation (root)          + List[StockReservationLine]
├─ stock_reservation_header_id
├─ source: sales_order_id XOR work_order_id  (CHECK enforces exactly one)
├─ warehouse_id, status {reserved, partially_reserved, failed, released}
└─ lines: requested / reserved / shortage quantity + status

GoodsReceipt (root)              + List[GoodsReceiptLine]
└─ purchase_order_header_id, warehouse, status {posted}

Shipment (root)                  + List[ShipmentLine]
└─ sales_order_header_id, customer snapshot, status {posted}

StockAdjustment (root, single-line)
└─ warehouse, product, direction {in, out}, reason, status {posted}

ReplenishmentRequest (root)      ◄── the inventory-side replenishment broker
├─ replenishment_request_id, product_id, warehouse_id, requested_quantity
├─ target_service {manufacturing, purchasing}
├─ reason {reorder_point_breach, work_order_shortage, sales_order_shortage, order_pegged}
├─ status {requested, dispatched, fulfilled, cancelled}
├─ dispatched_aggregate_kind {work_order, purchase_requisition} + dispatched_aggregate_id
├─ linked_purchase_order_id
└─ source_sales_order_header_id, source_sales_order_line_id  (demand-driven reasons only)
```

**Not aggregates — projections and reference data.** `warehouse` is reference data (no aggregate). `product_card` is inventory's snapshot of product master (maintained by inbox handlers, read via a `*Lookup`). `stock_balance` (per-warehouse-per-product on-hand / reserved / `available` generated column / average cost / `version` for OCC) and `wip_balance` (sub-assembly work-in-progress on-hand) are **running totals** maintained by writer ports, not aggregates — promoting a total to an aggregate is exactly the divergence-from-its-facts trap that double-entry was invented to prevent. `sales_order_line_facts` and `purchase_order_line_facts` are demand-side projections used to gate shipment / goods-receipt posting. `stock_movement` is an **append-only, range-partitioned log** — closer to a persisted domain event than a mutable entity; the actual commands (shipment post, GR post, adjustment post) write the movement row as a side effect and bump the balance in the same transaction.

**The `ReplenishmentRequest` aggregate is the load-bearing addition.** It unifies four supply triggers behind one lifecycle:

- `reorder_point_breach` — proactive, policy-driven (on-hand crossed below the projected reorder point). One-open invariant: at most one open request per `(product, warehouse)` (partial unique index), so re-triggering while one is pending is a no-op.
- `work_order_shortage` — Inventory's bridge for a manufacturing raw-material shortage. Same one-open invariant.
- `sales_order_shortage` — a make/buy-to-stock SO line short on the shared pool. **Per-line** (excluded from the one-open index) so each shorted line waits for *its* replenishment.
- `order_pegged` — the `to_order` sibling: dedicated supply earmarked to a specific SO line for the full quantity, never drawing from the shared pool. Marked pegged-on-fulfilment so sales ships without a retry.

Make-vs-buy chooses `target_service`; the request acks via `*Dispatched` / `*Undispatchable` and closes via `ReplenishmentFulfilled` / `ReplenishmentCancelled`.

**Value objects.** `Quantity`, `Money`, `StockReservation.Status` / `Shipment.Status` / `GoodsReceipt.Status` / `StockAdjustment.Status`, and on `ReplenishmentRequest` the `Status` / `TargetService` / `Reason` / `DispatchedAggregateKind` nested enums; plus the package-level `StockMovementType` / `StockMovementDirection`. All carry `code()`.

**Domain / application services.** `ReplenishmentDetectionService` is the MRP heart — on an on-hand decrement it checks the projected reorder point, routes by make-vs-buy, raises a `ReplenishmentRequest` (handling the one-open race via duplicate-key catch), and bridges work-order shortages. `StockReservationService`, `GoodsReceiptService`, `ShipmentService`, `StockAdjustmentService` are the command entry points; `StockMovementWriter` / `WipBalanceWriter` are the internal writer ports that keep the totals in step with the movement log.

**Domain events.** `StockReserved`, `RawMaterialsReserved` (the manufacturing-side parallel), `GoodsReceived`, `ShipmentPosted`, `StockAdjusted`, `ReplenishmentRequested`, `ReplenishmentFulfilled`, `ReplenishmentCancelled`, `SalesOrderLineReservationChanged` (amendments on an already-reserved order), `InventorySalesOrderCancellationApplied` (compensation ack to the sales saga). Each carries `EVENT_TYPE = "inventory.<ClassName>"`.

### 2.4 Manufacturing

**Ubiquitous language.** Bill of materials, BOM line, finished product, component, sub-assembly, scrap factor, component kind (raw / sub-assembly), work order, work-order material, work-order operation, routing, routing operation, work centre, labour/overhead rate, conversion cost, planned/completed/scrapped quantity, material reservation, raw-material shortage, sub-assembly consumption, WIP.

**Aggregates.**

```
Bom (root)                       ◄── note: the class is `Bom`, not BillOfMaterial
├─ bom_header_id, finished_product_id, finished_product_sku, finished_product_name
├─ version (string), status {draft, active, inactive}
└─ Lines: List[BomLine]
       ├─ line_number, component_product_id, component_sku, component_name
       ├─ component_kind {raw, sub_assembly}        ◄── recursion marker
       ├─ quantity_per_finished_unit, scrap_factor_percent
   (UNIQUE on finished_product_id WHERE status='active' — at most one active version)

WorkOrder (root)                 ◄── transactional boundary
├─ work_order_id, work_order_number
├─ origin: sales_order_header_id / sales_order_line_id (MTO) OR replenishment-driven (MTS)
├─ parent_work_order_id          ◄── for sub-assembly child work orders
├─ finished_product_id, bom_id
├─ Quantity: planned, completed, scrapped (invariant: completed + scrapped ≤ planned)
├─ status (5 states: released, in_progress, completed, closed, cancelled)
├─ material_status {reservation_pending, reserved, partially_reserved, shortage}
├─ planned/actual start & completion
└─ Children:
       ├─ List[WorkOrderMaterial]    (one per component; line status)
       └─ List[WorkOrderOperation]   (snapshot of routing ops; operation status)

WorkOrderSaga (process manager)   ◄── NOT "MakeToOrderSaga"
└─ saga_id, sales_order_header_id (nullable), sales_order_line_id (nullable),
   work_order_id (unique), saga_state, data, lease …
```

**Routing and work centres are now modelled** (an earlier reading of this document listed production capacity as an unmodelled gap). `WorkCenter` carries `labour_rate_per_minute` / `overhead_rate_per_minute`; `Routing` (`routing_header` + `routing_operation`) is the active-per-product operation template, lifecycle-managed like a BOM. At release, the routing operations are snapshotted onto `work_order_operation` with planned setup/run minutes. What is *still* unmodelled is **finite-capacity scheduling** — there is no work-centre load/ATP calculation; routing supplies costing, not a capacity constraint.

**Design note — the saga is make-to-stock-shaped.** The saga is named `WorkOrderSaga` (table `work_order_saga`); the older `MakeToOrderSaga` name is gone. It is entered directly at `work_order_created` (the old `started` seed state, which only fed the sales-driven make-to-order path, was retired), and its real states are just `work_order_created → raw_material_reservation_requested → raw_materials_reserved | raw_material_shortage → completed | failed`. A make-to-stock replenishment work order runs this *same* lifecycle with `sales_order_header_id` null. To-order demand reaches manufacturing the same way any other demand does — via Inventory's `ReplenishmentRequested` (`target_service = manufacturing`), not via a dedicated MTO path.

**BOM recursion is synchronous at release.** `WorkOrderReleaseService` walks the BOM; for each `sub_assembly` component it spawns a child work order (with `parent_work_order_id` set) and its own saga seeded at `work_order_created`, and snapshots BOM lines → `work_order_material`, routing ops → `work_order_operation`. On completion, `SubAssembliesConsumed` and the WIP balances reconcile child output into the parent.

**Value objects.** `Bom.Status`, `Bom.ComponentKind`, `WorkOrder.Status`, `WorkOrder.MaterialStatus`, `WorkOrder.MaterialLineStatus`, `WorkOrder.OperationStatus` (all nested enums with `code()`); `Quantity`, `Money`, scrap factor.

**Domain / application services.** `BomService` (`createDraft`, `addLine`, `removeLine`, `activate` — with post-save cycle detection via `BomCycleDetector`), `WorkOrderReleaseService` (BOM explosion + child-WO spawning + routing snapshot), `WorkOrderOperationService` (operation completion → `WorkOrderManufacturingCompleted`, conversion-cost absorption, sub-assembly consume), `MaterialsCostRollupService` (BOM cost roll-up: `Σ component_cost × qty × (1 + scrap%)`, walking the parent graph on a child cost change), and the CQRS-side `WorkOrderPrioritisationService`.

**Domain events.** `BomActivated` (drafting and deactivation emit nothing), `WorkOrderCreated` (carries `status='released'` — there is no separate `WorkOrderReleased`), `RawMaterialReservationRequested`, `RawMaterialShortageDetected`, `OperationCompleted`, `WorkOrderManufacturingCompleted`, `SubAssembliesConsumed`, `WorkOrderConversionApplied`, `ProductMaterialsCostComputed`, `WorkOrderPriorityChanged`, plus the replenishment acks `ReplenishmentDispatched` / `ReplenishmentUndispatchable`. Material *issue* and finished-goods *receipt* are emitted by Inventory, not here. Each carries `EVENT_TYPE = "manufacturing.<ClassName>"`.

### 2.5 Purchasing

**Ubiquitous language.** Supplier, supplier product price (tiered, effective-dated), approved vendor, purchase requisition (manual / stock-replenishment), requisition line, purchase order, PO line, ordered/received/invoiced/paid amount, three-way match.

**Aggregates.**

```
Supplier (root)
└─ supplier_id, supplier_code, name, ContactInfo, Address, status {active, inactive, blocked}

SupplierProductPrice (root)      ◄── the supplier catalogue, now a first-class aggregate
└─ supplier_id, product_id, currency_code, unit_price (> 0),
   effective_from / effective_to, min_quantity (tier break)

PurchaseRequisition (root)       + List[PurchaseRequisitionLine]
├─ purchase_requisition_header_id, requisition_number
├─ source_type {manual, stock_replenishment}
├─ source_product_id | source_work_order_id | source_replenishment_request_id
└─ status, requested_by

PurchaseOrder (root)             + List[PurchaseOrderLine]
├─ purchase_order_header_id, purchase_order_number
├─ supplier_id ──► Supplier, purchase_requisition_header_id (optional)
├─ currency_code, exchange_rate, order_date, expected_receipt_date
├─ Money: subtotal, tax, total, received, invoiced, paid
│   (invariants: received ≤ total; paid ≤ invoiced ≤ total)
├─ status (10-state lifecycle), version
└─ lines: ordered / received (≤ ordered) / invoiced (≤ ordered) + TaxApplication + status

PurchaseToPaySaga (process manager)
└─ saga_id, purchase_order_header_id (unique), sales_order_header_id (nullable cross-saga key), …
```

**The supplier catalogue exists** (an earlier reading of this document listed it as a gap). `SupplierProductPrice` is a real aggregate (tiered, effective-dated prices, emitting `SupplierProductPriceChanged` with no-op suppression on an identical price); `product_approved_vendor` is purchasing's projection of product master's approved-vendor list. `Supplier` is likewise a full aggregate now — `register` / `changeStatus` / `updateDetails`, not a reference row.

**Design note — work-order shortages arrive through Inventory.** Purchasing has **no direct manufacturing listener**. A manufacturing raw-material shortage becomes an inventory `ReplenishmentRequest`; purchasing's `ReplenishmentRequestedHandler` consumes `inventory.ReplenishmentRequested` and creates a requisition with `source_type = stock_replenishment`, emitting `PurchaseRequisitionCreated` + `ReplenishmentDispatched` (or `ReplenishmentUndispatchable` when no approved vendor exists) in one transaction. The `work_order_shortage` source value is retained as schema-prep / history but no longer has a producer. Approval is single-step today: requisitions auto-approve at creation; a shortage-driven PO with a known price auto-sends, a manual PO lands at `draft`.

**Value objects.** `Supplier.Status`, `PurchaseRequisition.SourceType` / `.Status` / `.LineStatus`, `PurchaseOrder.Status` / `.LineStatus` (nested enums, `code()`); `Money`, `Quantity`, `TaxApplication`, `Address`, `ContactInfo`.

**Domain services.** Three-way match logic is exercised in Finance (where the supplier invoice lands) against `purchase_order_line_facts`; supplier *selection* beyond the approved-vendor list and price lookup is not yet an explicit service.

**Application services.** `SupplierService` (`onboard`, `changeStatus`, `updateDetails`), `SupplierProductPriceService` (`setPrice`), `PurchaseRequisitionService` (`createManual`, `createForStockReplenishment`), `PurchaseOrderService` (`convertFromRequisition`, `approve`, `reject`).

**Domain events.** `SupplierRegistered`, `SupplierStatusChanged`, `SupplierDetailsChanged`, `SupplierProductPriceChanged`, `PurchaseRequisitionCreated`, `PurchaseOrderCreated`, `PurchaseOrderApproved`, `PurchaseOrderCancelled`, `ReplenishmentDispatched`, `ReplenishmentUndispatchable`. Receipt / invoice / payment progress is *consumed* (driving saga state), not emitted, by purchasing. Each carries `EVENT_TYPE = "purchasing.<ClassName>"`.

### 2.6 Finance

**Ubiquitous language.** Chart of accounts (GL account), account type, tax code, customer invoice, supplier invoice, invoice type (commercial / prepayment / deposit / balance), internal vs external invoice number, payment, allocation, journal entry, debit, credit, posted, reversed, three-way-match status, WIP sub-ledger.

**Aggregates.**

```
CustomerInvoice (root)           + List[CustomerInvoiceLine]
├─ customer_invoice_header_id, invoice_number, sales_order_id (cross-context, by ID)
├─ invoice_type {commercial, prepayment, deposit, balance}   (controls GL posting timing)
├─ customer snapshot, invoice_date, due_date
├─ status {posted, partially_paid, paid}
├─ Money: subtotal, tax, total, paid_amount, currency_code, exchange_rate
└─ lines: quantity, unit_price, tax, line_total   (immutable once posted)

SupplierInvoice (root)           + List[SupplierInvoiceLine]
├─ internal_invoice_number vs supplier_invoice_number (external)
├─ purchase_order_header_id, goods_receipt_header_id (three-way-match anchors)
├─ status {three_way_match_failed, approved, partially_paid, paid, cancelled}
└─ match_status {matched, variance, failed}

Payment (root)
├─ payment_id, payment_number, direction {incoming, outgoing}, type {customer, supplier}
├─ customer_id | supplier_id (XOR by type), method {bank_transfer, cash, card, cheque}
├─ Money: amount, amount_allocated (≤ amount), status {posted}
└─ allocations: List[PaymentAllocation]   ◄── value-object child, NOT a separate aggregate
       ├─ customer_invoice_header_id | supplier_invoice_header_id (XOR)
       └─ allocated_amount, status {posted, reversed}

JournalEntry (root, write-once)  + List[JournalEntryLine]
├─ journal_entry_header_id, journal_number, posting_date
├─ source_module, source_document_type, source_document_id
├─ status {draft, posted, reversed}, currency_code, exchange_rate
└─ lines: account_id ──► gl_account, debit XOR credit (one zero, other > 0), posting_date
   ◄── deferred-constraint trigger: SUM(debit) = SUM(credit) on post
   ◄── posted entries can only transition to reversed
```

**Not aggregates.** `gl_account` (chart of accounts) and `tax_code` are **reference data** read via lookup ports — neither is an aggregate (an earlier reading modelled `Account` and `TaxCode` as roots). `exchange_rate` is reference data; `product_card`, `work_order_wip` (perpetual WIP sub-ledger), and `purchase_order_line_facts` (three-way-match reference) are projections.

**Design note — `PaymentAllocation` is a child value object, not its own aggregate.** It is a record embedded in `Payment.allocations`, created atomically with the payment. The cross-aggregate invariant it enforces — allocated ≤ invoice outstanding, and Σ allocations ≤ payment amount — is maintained **eagerly by a database trigger** (`maintain_allocation_totals`) that keeps `payment.amount_allocated` and `*_invoice_header.paid_amount` in step. This is the *totals get projections* rule applied to money: the paid-amount running total is never promoted to an aggregate that could diverge from the allocations that produced it.

**Journal balance enforcement** is the highest-value invariant in the system: the deferred-constraint trigger requiring `SUM(debit) = SUM(credit)` on post lives at the only place no misbehaving service or manual SQL fix can bypass it — the database. `JournalEntry` is write-once and emits **no events** (its identity persists for audit/reporting); it is one of the codebase's two sanctioned event-less aggregates.

**Value objects.** `Money` (amount + currency; exchange rate carried on the header), `CustomerInvoice.Status` / `.InvoiceType`, `SupplierInvoice.Status` / `.MatchStatus`, `Payment.Direction` / `.Type` / `.Method` / `.Status`, `PaymentAllocation` + its `AllocationStatus`, `JournalEntry.Status` / `.SourceModule` / `.SourceDocumentType`, `JournalEntryLine` (debit/credit), `TaxApplication`.

**Domain / application services.** `CustomerInvoiceService`, `SupplierInvoiceService` (three-way match against `purchase_order_line_facts` within a price tolerance; `manualApprove` / `manualReject`), `PaymentService` (single- and multi-invoice settlement, cash-on-delivery auto-payment), and `JournalEntryService` — the accounting engine, with a posting method per source document (supplier-invoice approval, goods-received GR/IR, shipment cost, stock adjustment, WIP charge, sub-assembly roll-in, work-order completion, conversion charge, production variance, supplier/customer payment, customer-invoice creation, prepayment revenue recognition, customer refund) plus reversal by source document.

**Domain events.** `CustomerInvoiceCreated`, `SupplierInvoiceApproved`, `SupplierInvoiceRejected`, `SupplierPaymentMade`, `CustomerPaymentReceived` — a deliberately small set focused on cross-context saga routing (`SupplierPaymentMade.purchaseOrderHeaderId`, `CustomerPaymentReceived.salesOrderHeaderId` + `orderFullySettled` are the routing keys). Status transitions and paid-amount changes are carried by the allocation trigger, not by separate `Posted` / `Allocated` / `Reversed` events. Each carries `EVENT_TYPE = "finance.<ClassName>"`.

### 2.7 Reporting

Reporting is a CQRS read-side and a *Conformist* on every other context. It has no aggregates because it has no commands — every table is a projection materialised from events.

**Read models.**

| Read model (`reporting.*`) | Keyed by | Built from events of |
|---|---|---|
| `sales_order_360_view` | `sales_order_header_id` | Sales, Inventory, Manufacturing, Finance |
| `available_to_promise_view` | `product_id` | Product, Inventory, Manufacturing, Purchasing |
| `production_planning_board` | `work_order_id` | Manufacturing, Inventory, Purchasing |
| `material_shortage_view` | `material_product_id` | Manufacturing, Inventory, Purchasing |
| `purchase_order_tracking_view` | `purchase_order_header_id` | Purchasing, Inventory, Finance |
| `replenishment_history_view` | `replenishment_request_id` | Inventory, Manufacturing, Purchasing |
| `customer_dashboard_status` | `customer_id` | Sales |
| `financial_dashboard_daily` | `(dashboard_date, currency_code)` | Sales, Finance, Inventory |
| `product_card` | `product_id` | Product |
| `projection_checkpoint` | `projection_name` | (cursor state, not a domain projection) |

The `sales_order_360_view` is where the cross-cutting status axes the sales aggregate deliberately *doesn't* carry are reassembled: it keeps its own `order_status` / `stock_status` / `manufacturing_status` / `shipment_status` / `invoice_status` / `payment_status` columns — a third status vocabulary, distinct from both the header fold and the line statuses, owned entirely by the read side.

**Projection logic.** Each read model has a `*Projection` interface in `application/inbox/` with a `Jdbc*Projection` implementation in `infrastructure/persistence/`, consuming inbox messages idempotently (upsert on conflict) and advancing its own checkpoint. `projection_checkpoint` stores `last_sequence_number` — the per-schema outbox `sequence_number` cursor (see §3.2), not a timestamp.

**Application services.** The projection handlers themselves; there is no separate command service.

---

## 3. Cross-Cutting Patterns

### 3.1 Sagas / process managers

Three sagas coordinate the long-running cross-context flows. Each is a *process manager* (Hohpe): it has its own state, listens to events from multiple contexts, and emits commands/events. It is *not* an aggregate of any context — it is infrastructure living at the seam between contexts, persisted as its own aggregate with `pendingEvents` drained to its outbox.

- **`SalesOrderFulfilmentSaga`** (`sales.sales_order_fulfilment_saga`) — places an order, reserves stock, waits on replenishment when short, and runs the ship+pay completion gate. §2.2 has the state list.
- **`WorkOrderSaga`** (`manufacturing.work_order_saga`) — drives a work order's material reservation through to completion, for both make-to-order and make-to-stock work orders. Named `WorkOrderSaga`, **not** `MakeToOrderSaga`.
- **`PurchaseToPaySaga`** (`purchasing.purchase_to_pay_saga`) — `started` → `purchase_order_approved` → `waiting_for_goods` → `goods_received` → `supplier_invoice_approved` → (`supplier_partially_paid`) → `completed`, with `failed` (match/invoice rejection) and `cancelled` (draft PO rejected) terminals.

The saga tables share one shape: `saga_state` (a CHECK set kept in lockstep with the saga class's `ALL_STATES` — a boot-time invariant checker fails otherwise), `current_step`, `data` (JSONB), `retry_count`, `next_retry_at`, `lease_owner` / `lease_expires_at`, `version` (OCC), `trace_id` (the W3C trace id of the span that created the row, captured once). Multiple workers poll safely with `SELECT … FOR UPDATE SKIP LOCKED WHERE next_retry_at <= now()`; the lease columns and OCC let siblings skip a claimed saga. The `sales_order_header_id` cross-saga key threads onto the WO and P2P sagas so a single Tempo TraceQL query returns an order alongside its downstream work-order and purchase sub-sagas. See `docs/sagas.md`.

### 3.2 Outbox / inbox

Every command context has its own `outbox_message` and `inbox_message` tables (reporting has inbox only). Both are range-partitioned (with a `_default` partition). The pattern is the standard transactional outbox: in the same transaction that mutates an aggregate, append an outbox row; a separate publisher polls and emits to Kafka; consumers record an inbox row for idempotency.

Two details matter. First, the publisher drains by **status flag**, not a high-water cursor: `SELECT … WHERE status IN ('pending','failed') ORDER BY sequence_number … FOR UPDATE SKIP LOCKED` (`JdbcOutboxAdapter.findPending`). Because it re-scans *pending* rows each tick rather than paginating past a `sequence_number > :cursor` watermark, the classic commit-order trap — a long tx grabs a low `sequence_number` early but commits *after* a higher one, so a watermark consumer skips the late row forever — cannot bite here; a late-committing low-sequence row is simply still `pending` on a later tick. The `ORDER BY sequence_number` does the weaker job of publishing each batch in write order, which (with one publish in flight per service) means a service never emits out of its own write order. (The `last_sequence_number` column in `projection_checkpoint` describes an earlier cursor-polling design intent; the live read side is Kafka offsets + inbox dedup — see below.) Second, a partial index on `WHERE status IN ('pending','failed')` keeps the working index small as published rows accumulate.

The inbox is for idempotent consumption: each handler records `(message_id, handler_name)`, and a config-selectable dedup gate (advisory-lock by default) closes the rebalance-window race so a redelivery doesn't double-apply. See `docs/messaging.md`.

#### Event ordering and disorder

Disorder can enter at four layers, each with a different guarantee:

1. **Producer (within a service)** — the status-flag drainer above publishes each batch in `sequence_number` order with one publish in flight, so a service never emits out of its own write order.
2. **Kafka partitioning** — the partition key is `aggregateId` (`KafkaEventPublisher`), so **per-aggregate order is preserved** but **cross-aggregate and cross-topic order are not**: two aggregates' events — even on the same topic — interleave across partitions.
3. **Consumer concurrency** — topics default to 3 partitions with concurrent listeners, so same-topic, different-aggregate events arrive in arbitrary relative order.
4. **Redelivery** — a transient failure re-seeks without committing the offset, so the message reappears *after* a backoff delay, behind newer messages: redelivery reorders as well as duplicates.

Consumers absorb whatever order survives with a small, layered catalogue — cheapest and most general first, so no single mechanism bears the whole load:

| Mechanism | Absorbs | Bucket | In code |
|---|---|---|---|
| **Inbox dedup** — advisory-lock gate; two statements, lock-then-`EXISTS` on a fresh snapshot | redelivery / duplicates (incl. the rebalance-window concurrent duplicate) | apply-once floor | `AbstractInboxHandler.handle` + `AdvisoryLockInboxDedupStrategy` |
| **Convergent projection** — `INSERT … ON CONFLICT DO UPDATE`, each event writes a disjoint column slice + `COALESCE` | cross-event reorder into a read model (a consequence may seed the stub the originating event later fills) | don't-care / convergent | `JdbcSalesOrder360Projection` (9 entry points, one key) |
| **Saga source-state guard** — a forward `apply*` no-ops unless `saga.state ∈ SOURCE_STATES` | a late forward event that would wrongly advance a compensating / terminal saga | guarded | `JdbcSalesOrderFulfilmentSagaManager` (`STOCK_RESERVED_SOURCE_STATES`, …) |
| **Aggregate terminal guard** — cancel/amend read `isTerminal() ‖ anyLineShipped()`, never a header allow-list | a late event after a terminal — caps blast radius to cosmetic line drift | guarded (blast-radius cap) | `SalesOrder` cancel / reject / amend gate |
| **One-open invariant** — partial unique index + `DuplicateKeyException` catch | concurrent duplicate triggers racing to raise the same request | convergent (DB-arbitrated) | `ReplenishmentDetectionService.raiseIfNoneOpen` |
| **Causal-by-construction** — commit the prerequisite before emitting the dependent | *removes* the disorder instead of tolerating it (the cheap-guarantee half) | guaranteed | local `replenishment_request` commit; saga row before its reply |

The residual class is cross-aggregate prerequisites that are *not* causally ordered — the worked case below, `inventory`'s PR→PO link, was one such residual (now closed via fix (b)). The full guarantee / mechanism / test matrix, the partition-key rationale, and the residual-hazard audit live in `docs/messaging.md`.

**The design principle.** In a distributed system, global total order isn't worth buying — the mechanisms that enforce it (single partition, a global sequencer, distributed locks) trade away the throughput and availability the event architecture exists to gain. So the goal is not to *avoid* disorder but to minimise the disorder that actually matters, which is the intersection of two sets: orderings that **can occur** and orderings your **correctness depends on**. A reordering only bites when it sits in both. You attack it from both sides: **buy the cheap local ordering guarantees first** (partition-key = `aggregateId` for per-aggregate order; causal-by-construction emission — commit the prerequisite before publishing the dependent), **then apply the minimum tolerance machinery to whatever residual remains** (idempotent / convergent / state-guarded handlers + inbox dedup). Tolerance is not free either, so over-tolerating — making everything order-independent when a cheap causal guarantee was available — is its own over-engineering. Every ordering assumption must therefore land in exactly one of three legal buckets: **guaranteed** (same partition / causal emission), **guarded** (source-state or terminal guard), or genuinely **don't-care** (commutative / convergent). The bug class is the illegal fourth bucket — an *implicit* assumption that A precedes B, neither guaranteed nor guarded; that is precisely what the PR→PO residual is. Much of the tolerance machinery is owed to at-least-once delivery anyway (redelivery forces idempotency regardless), so building for exactly-once-effect and building for disorder-tolerance largely pay the same bill once.

**A worked anti-pattern — the PR→PO link.** This residual (now closed — see *the legal fixes* below) was a textbook illegal-fourth-bucket case, worth dissecting because it shows how an *implicit* ordering assumption hides in plain sight and what it costs.

- *The flow.* When a replenishment is routed to purchasing, two purchasing events feed inventory back: `ReplenishmentDispatched` (keyed by the **PR** id — flips the `replenishment_request` to `dispatched`, kind `purchase_requisition`) and, after PR→PO conversion, `PurchaseOrderCreated` (keyed by the **PO** id — `inventory.PurchaseOrderCreatedHandler` stamps `replenishment_request.linked_purchase_order_id`). Different keys → different partitions → no ordering guarantee, even though the producer emits dispatched first.
- *The implicit assumption.* The PO-created handler assumes the dispatch already landed: it looks the request up by `dispatched_aggregate_id` and calls `linkPurchaseOrder(...)`. If the PO event wins the race, the lookup is empty (the request isn't `dispatched` yet) and the link is **silently skipped** — neither guaranteed (cross-partition) nor guarded (empty lookup → no retry, no defer).
- *The cost — not cosmetic.* `GoodsReceiptService` resolves the request from a receipt via `findByLinkedPurchaseOrderId(poId)` and calls `markFulfilled()`, which emits `ReplenishmentFulfilled`. For a `sales_order_shortage` request that event is what un-parks the sales fulfilment saga out of `stock_reservation_incomplete`. Lose the link and the order is **stranded** indefinitely (a reorder-point request merely leaks as permanently `dispatched`). Rare — dispatch is emitted first and usually wins — but severe when hit.
- *Why the obvious fix is wrong.* "Carry `replenishmentRequestId` on `PurchaseOrderCreated` and look the request up by id" does **not** close it: `ReplenishmentRequest.linkPurchaseOrder` enforces a **state invariant** — it throws unless the request is already `dispatched`. The dependency is on the dispatched-state *write*, not the lookup key, so a direct find still can't link a not-yet-dispatched request.
- *The legal fixes* (move it out of the fourth bucket):
  - **(a) guarded/convergent** — bidirectional second-chance: the dispatch handler *also* links if a PO already exists for the PR (needs a PR→PO lookup, e.g. a `source_purchase_requisition_id` column on `purchase_order_line_facts`). *Tolerates* the residual — there's still a transient `dispatched`-but-unlinked window — and adds persisted reconciliation state plus a two-handler handshake.
  - **(b) guaranteed/causal** — make `PurchaseOrderCreated` self-sufficient: a PO existing already proves its PR was dispatched, so the inventory handler does `markDispatched` **and** `linkPurchaseOrder` in one transaction. Crucially it performs the dispatched-state *write* itself — which is exactly what the link's state invariant needs — so unlike the naive link-only fix above, this one *does* close it. Both calls are idempotent, so whichever of `ReplenishmentDispatched` / `PurchaseOrderCreated` lands first wins and the other no-ops; arrival order stops mattering. *Removes* the residual.
  - **(c) ordered/keyed** — emit a dedicated replenishment-purpose event keyed by the **RR id** (a copy of the PR/PO-created facts) so both replenishment signals co-partition. Northwood's per-service inbox consumer (`KafkaInboxDispatcher` — one `@KafkaListener`/group per service, reading each partition in order and fanning out to handlers synchronously) already *is* the single ordered consumer this needs, so co-partitioning would serialise the two applies. Sound as an explicit saga-choreography style; the costs are elsewhere: it **duplicates** an event and leaks inventory's ordering need into purchasing's contract — purchasing would emit an event whose only reason to exist is inventory's consumption order — and it still needs idempotent handlers for redelivery anyway, so correctness rests on an infra ordering invariant (the RR-keyed co-partition, held forever) rather than on the domain logic.
- *Chosen: **(b)*** (shipped — `inventory.ReplenishmentPurchaseOrderLinkHandler`, consumer `inventory.replenishment.pur-po-created`). It *removes* the residual rather than tolerating it (a); and over (c) it adds a single forwarded field (`PurchaseOrderCreated.sourceReplenishmentRequestId`, which the PR already owns via `source_replenishment_request_id`) instead of a duplicated event + a producer-side concern leak, and keeps correctness in idempotent domain logic instead of an ordering invariant. The link was also moved off the line-facts seeder into the replenishment concern (`inventory.PurchaseOrderCreatedHandler` now seeds `purchase_order_line_facts` only), so the two replenishment side-effects — dispatch and link — share one concern instead of being split across two consumers. The same shape applies to the work-order-completion twin and is a follow-on.

### 3.3 CQRS

Commands write to the per-context aggregates; queries read from `reporting.*` projections. The split is real, not nominal: the sales-order detail page (status from sales, stock from inventory, manufacturing progress, invoice/payment from finance) is one read against `sales_order_360_view` rather than a multi-service join. The cost is eventual consistency — the read model lags by however long the projector takes to drain — which the UI must communicate (as-of stamps, or read-your-writes through the command service for a brief window).

### 3.4 Cross-cutting value objects and the shared kernel

The genuinely shared structures live in `shared-kernel` (`com.northwood.shared.domain`), which is Spring-free and callable from every layer:

- **`Money`** — `amount: BigDecimal` + `currencyCode: String`; rejects cross-currency arithmetic. The exchange rate to base currency is carried separately on aggregate headers, not inside `Money`.
- **`Quantity`** — `amount: BigDecimal` + `uomCode: String`; rejects cross-UoM arithmetic (conversion is a domain-service concern).
- **`Sku`** — opaque, validated identifier.
- **`Currencies`** — a constants-holder (`AUD`, `USD`, `NZD`, `BASE_CURRENCY`, `orBase(...)`), deliberately not an enum so ISO 4217 additions don't require a migration.
- **`Assert`** — the argument/state-check helper family (`notNull` / `notBlank` / `notEmpty` / `argument` and the `state*` mirror), replacing inline `IllegalArgumentException` / `Objects.requireNonNull`.

A *Shared Kernel* is the right context-mapping pattern for these. Note that `Address`, `ContactInfo`, `DateRange`, and `TaxApplication` are **not** extracted to the shared kernel — they recur conceptually but exist as schema columns / service-local records, with no cross-service reuse today. Resist putting `Product` or `Customer` in the kernel: those *look* shared but their meaning differs by context (a `Customer` is a buyer to Sales, a debtor to Finance, a dashboard row to Reporting).

---

## 4. Observations and Gaps

What has closed since this document's first reverse-engineering, and what is still implicit:

**Now modelled (schema present; some logic still thin):**

- **Production routing & work centres** — `manufacturing.work_center` (labour/overhead rates), `routing_header` + `routing_operation`, snapshotted onto `work_order_operation` at release and absorbed into WIP as conversion cost. *Still missing:* finite-capacity scheduling (no work-centre load / ATP-by-centre).
- **Supplier catalogue** — `purchasing.supplier_product_price` (tiered, effective-dated) and `product_approved_vendor`. *Still thin:* an explicit supplier-*selection* policy beyond the approved-vendor list.
- **Approved vendors** — `product.approved_vendor` projected into manufacturing and purchasing. *Still thin:* no hard gate forcing POs onto approved suppliers.
- **WIP sub-ledger** — `finance.work_order_wip` plus the manufacturing conversion-cost and materials-cost-rollup events give a perpetual WIP valuation that an earlier design lacked.

**Still open:**

- **Pricing is flat.** `product.sales_price` is one number per product — no price lists, customer-specific pricing, discounts, or promotions. If pricing matters, it deserves its own `Pricing` context.
- **Returns are absent.** No customer returns, supplier returns, or RMA — not edge cases in furniture. (Customer credit notes / refunds are deliberately deprioritised for the showcase, but the gap stands.)
- **Identity / authorisation is not a domain context.** `created_by` / `last_modified_by` / `actor_user_id` columns exist for audit, but there is no user/role/permission model; the demo web BFF stamps a shared-secret bypass header rather than projecting per-user identity. A real identity context integrated via an ACL is the eventual shape.
- **Tax is single-jurisdiction.** `tax_code` is a flat rate per code — no origin/destination, federal/state/local, or reverse-charge. Multi-jurisdiction tax would force a richer `TaxApplication` and a `TaxCalculationService`. (The tax-account split is deprioritised for the demo.)
- **Multi-currency conversion is structural only.** Every monetary header carries `currency_code` + `exchange_rate` and `Currencies` exists, but applying a dated rate at posting is not wired; multi-currency GL consolidation is deliberately low priority.
- **No integration ACLs.** The system is insular; carriers, gateways, and EDI each deserve an anti-corruption layer when added.
- **Saga retry policy is partly implicit.** `retry_count` / `next_retry_at` and the consumer error handler (poison-vs-transient classification + exponential backoff + per-service DLT auto-redrive) exist, but the back-off/dead-letter policy is config + handler code rather than an explicit state in the saga's machine.

---

## 5. Quick Reference

| Aggregate root | Context | Children | Key invariants |
|---|---|---|---|
| `Product` | Product | (`ApprovedVendor` collection) | unique SKU; one active BOM pointer |
| `Customer` | Sales | — | unique customer_code |
| `SalesOrder` | Sales | `SalesOrderLine` | `reserved/shipped/mfg ≤ ordered`; status = fold of line statuses |
| `StockReservation` | Inventory | `StockReservationLine` | exactly one source (sales OR work order) |
| `GoodsReceipt` | Inventory | `GoodsReceiptLine` | posted ⇒ immutable |
| `Shipment` | Inventory | `ShipmentLine` | posted ⇒ immutable |
| `StockAdjustment` | Inventory | — (single-line) | posted ⇒ immutable; direction in/out |
| `ReplenishmentRequest` | Inventory | — | one open per (product, warehouse) for policy/WO reasons; per-line for demand reasons |
| `Bom` | Manufacturing | `BomLine` | one active version per finished product; no component cycles |
| `WorkOrder` | Manufacturing | `WorkOrderMaterial`, `WorkOrderOperation` | `completed + scrapped ≤ planned` |
| `Supplier` | Purchasing | — | unique supplier_code |
| `SupplierProductPrice` | Purchasing | — | unit_price > 0; effective-dated, tiered |
| `PurchaseRequisition` | Purchasing | `PurchaseRequisitionLine` | source columns gated by source_type |
| `PurchaseOrder` | Purchasing | `PurchaseOrderLine` | `received/invoiced ≤ ordered`; `paid ≤ invoiced ≤ total` |
| `CustomerInvoice` | Finance | `CustomerInvoiceLine` | `paid_amount ≤ total`; immutable when posted |
| `SupplierInvoice` | Finance | `SupplierInvoiceLine` | as customer; plus three-way-match status |
| `Payment` | Finance | `PaymentAllocation` (VO child) | `amount_allocated ≤ amount`; one party kind (customer XOR supplier) |
| `JournalEntry` | Finance | `JournalEntryLine` | `SUM(debit) = SUM(credit)` on post; write-once → reversed; line is debit XOR credit |

**Reference data / projections (not aggregates):** `unit_of_measure`, `warehouse`, `gl_account`, `tax_code`, `exchange_rate` (reference); `product_card`, `stock_balance`, `wip_balance`, `*_facts`, `work_order_wip`, `stock_movement` (projections / append-only log) — these carry running totals or upstream-owned snapshots, so they get projection-shaped ports, never an aggregate.

| Process manager | Owns the flow | Listens to | Emits |
|---|---|---|---|
| `SalesOrderFulfilmentSaga` | order → reserve → (replenish) → ship + pay | Inventory, Finance | reservation request, up-front-invoice requests, ready-to-ship, cancellation, compensation |
| `WorkOrderSaga` | work order → reserve materials → complete | Inventory | raw-material reservation request |
| `PurchaseToPaySaga` | PO → goods receipt → supplier invoice → match → pay | Inventory, Finance | (drives PO state; no own domain events) |

---

*End of analysis.*
