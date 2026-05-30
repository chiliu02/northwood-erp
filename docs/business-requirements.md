# Northwood — Business Requirements

## Purpose

This document is the **business specification** for the Northwood ERP/MRP showcase — what the application does for its end users, expressed in the language a planner, accountant, warehouse operator, or buyer would use. It is **not** a developer / framework document; the architectural / implementation conventions live in `docs/architecture.md`, `docs/conventions.md`, `docs/sagas.md`, `docs/messaging.md`, and `docs/persistence.md`.

Each requirement carries a stable identifier (`REQ-<context>-<seq>`) so it can be cross-referenced from PRs, test names, and future business cases without renumber drift. Numbering gaps are deliberate — when a requirement is retired, its number is not reused.

### REQ-ID conventions

| Prefix | Bounded context |
|---|---|
| `REQ-PROD-*`  | Product master |
| `REQ-SAL-*`   | Sales |
| `REQ-INV-*`   | Inventory |
| `REQ-MFG-*`   | Manufacturing |
| `REQ-PUR-*`   | Purchasing |
| `REQ-FIN-*`   | Finance |
| `REQ-RPT-*`   | Reporting (read-only views) |
| `REQ-XBC-*`   | Cross-context business flows |
| `REQ-SEC-*`   | Security / roles |

Each entry follows this shape:

> **REQ-XXX-NNN — Title** *(status: shipped | planned | deferred)*
> Statement of the rule or process step.
> *Rationale:* optional — why the business needs it this way.
> *Acceptance:* optional — what a user-visible test of "is this working?" looks like.

---

## 1. Product Master (REQ-PROD)

The product master is the single source of truth for every SKU sold, manufactured, or purchased. Every other bounded context references products by `product_id` and snapshots the master fields it needs (SKU, name, type) locally at the moment of use, so historical documents stay readable even if the master later changes.

### 1.1 SKU registration

**REQ-PROD-001 — Register a new SKU** *(shipped)*
A product administrator registers a new SKU by entering: SKU code (uppercase alphanumeric, must be unique across the catalogue), display name, product type (Raw Material / Semi-Finished Good / Finished Good / Service), and base unit of measure (e.g. EA, KG, M). The system stamps creation time and the user identity.
*Acceptance:* the new SKU appears in every other context's local snapshot within a few seconds (sales sees it in product pickers, inventory creates a stock item with zero balances, finance receives a default valuation class).

**REQ-PROD-002 — SKU codes are immutable** *(shipped)*
Once registered, the SKU code cannot be changed. Display name and other descriptive fields may be edited.
*Rationale:* SKU codes appear on customer-facing documents (orders, invoices, packing slips) that must remain legible to the audit trail.

**REQ-PROD-003 — Product type drives default behaviour** *(shipped)*
Product type sets sensible defaults across the system:

| Type | Default make-vs-buy | Default valuation class |
|---|---|---|
| Raw Material | Buy | Raw Materials Inventory (1210) |
| Semi-Finished Good | Make | Raw Materials Inventory (1210) |
| Finished Good | Make | FG Inventory (1220) |
| Service | Buy | (no inventory) |

Administrators may override these defaults per SKU.

### 1.2 Make-vs-buy classification

**REQ-PROD-010 — Classify a SKU as makeable, buyable, or both** *(shipped)*
Every SKU carries two flags: `is_purchased` (the company sources it from suppliers) and `is_manufactured` (the company produces it internally). At least one flag must be true; both may be true for vertically-integrated SKUs.
*Rationale:* downstream services use this to decide replenishment routing — manufacturing accepts production orders only for makeable SKUs; purchasing lists buyable SKUs in its procurement catalogue; the new automatic-replenishment flow (REQ-INV-080) routes on it.

**REQ-PROD-011 — Change make-vs-buy classification** *(shipped)*
Administrators may toggle either flag at any time. The change propagates to every consumer immediately.

### 1.3 Reorder policy

**REQ-PROD-020 — Define a reorder policy per SKU** *(shipped)*
Each SKU carries a reorder point (the on-hand quantity at which replenishment should fire) and a reorder quantity (how much to replenish each time). Both default to zero (meaning "no automatic replenishment for this SKU"). Administrators edit them via the product card.

**REQ-PROD-021 — Reorder policy is broadcast to inventory** *(shipped, used by REQ-INV-080)*
When the reorder policy changes, inventory snapshots the new values onto its local stock-item row so the automatic-replenishment monitor (REQ-INV-080) reads them without a cross-service call.

### 1.4 Sales pricing

**REQ-PROD-030 — Maintain a list price per SKU per currency** *(shipped)*
Each SKU may carry list prices in any supported currency (AUD, NZD, USD). Sales orders default to the list price of the order's currency at the time of capture.

**REQ-PROD-031 — Price changes are historical** *(shipped)*
Editing the list price does **not** retro-affect existing sales orders or invoices — those carry the price that applied at capture time.

### 1.5 Standard cost

**REQ-PROD-040 — Maintain a standard cost per SKU** *(shipped)*
Each SKU carries a standard cost in the company base currency. Used by finance to value COGS on shipment.

**REQ-PROD-041 — Manufactured-product cost rollup** *(shipped, partial)*
For a SKU classified as makeable with an active BOM, the standard cost is automatically rolled up from its materials' costs (recursive BOM walk). Edits to a leaf component's standard cost cascade up to recompute parent costs.

### 1.6 Valuation class

**REQ-PROD-050 — Assign a valuation class to each SKU** *(shipped)*
The valuation class determines which GL accounts finance uses when posting inventory movements (e.g. raw-materials inventory vs. finished-goods inventory; materials COGS vs. general COGS). Defaults follow REQ-PROD-003 by product type; administrators may override.

### 1.7 Active BOM

**REQ-PROD-060 — Each makeable SKU may have one active Bill of Materials** *(shipped — read-only authoring)*
The active BOM is the recipe that manufacturing uses to release a work order. BOM authoring (draft / activate / deactivate) is partly shipped — backend authoring endpoints exist; a visual editor is deferred (see §1.8 and the BOM authoring deferral in `dev-todo.md`).

### 1.8 Approved vendor list

**REQ-PROD-070 — Maintain an approved vendor list per buyable SKU** *(shipped)*
Each buyable SKU carries a list of approved suppliers (multiple per SKU; one designated default). Used by purchasing to suggest the supplier when raising a requisition or PO line.

### 1.9 Discontinuation

**REQ-PROD-080 — Discontinue a SKU** *(shipped)*
An administrator may discontinue a SKU. Effects propagate:
- inventory marks its stock-item row discontinued (the SKU is still readable; no further reservations may be raised against it);
- sales pickers hide the SKU from new-order entry;
- purchasing hides the SKU from new-requisition entry;
- manufacturing rejects new work-order requests for the SKU;
- finance retains all historical postings.

Discontinuation is logical, not physical — every fact already recorded keeps referring to the SKU.

---

## 2. Sales (REQ-SAL)

Sales captures customer commitments and shepherds them through to dispatch and payment.

### 2.1 Customer master

**REQ-SAL-001 — Register a customer** *(shipped)*
A customer record carries: customer code (unique), display name, billing address, shipping address, primary contact (email, phone), default currency, payment terms.

**REQ-SAL-002 — Edit customer details** *(shipped)*
Name, addresses, and contact may be edited. Edits do not retro-modify documents already issued (existing invoices keep the address that applied at issue time).

**REQ-SAL-003 — Deactivate a customer** *(shipped)*
A deactivated customer no longer appears in new-order entry. Historical documents remain readable.

### 2.2 Sales order capture

**REQ-SAL-010 — Place a sales order** *(shipped)*
A sales clerk captures: customer, currency (defaults to customer's), one or more lines (SKU + quantity), an optional requested-by date. Per line the system snapshots: SKU, name, list price at capture, ordered quantity, line subtotal, line tax. Order subtotal, tax total, and grand total are computed at capture.

**REQ-SAL-011 — Line-level prices and quantities are positive** *(shipped)*
Quantities must be positive numbers in the SKU's base UoM. Prices must be non-negative. Discount handling is currently out of scope (line prices are the snapshot of list price).

**REQ-SAL-012 — Order numbers are sequential and human-legible** *(shipped)*
Orders carry both an internal UUID (for system traceability) and an external number (`SO-YYYY-NNNNNN`) shown to the customer.

### 2.3 Payment terms and prepayment

**REQ-SAL-020 — Payment terms per order** *(shipped)*
Each order carries payment terms (default from the customer, override per order). Two terms are supported today:
- **Net 30** — invoice on shipment, payment due 30 days later. Shipment is gated only by stock availability.
- **Prepayment 100%** — a deposit invoice is raised at order placement; **shipment is held until the deposit is paid in full**.

**REQ-SAL-021 — Prepayment invoice on order placement** *(shipped)*
For prepayment orders, the system raises a deposit invoice immediately at order placement (full order total, before any goods move). The customer-portal status lozenge reads "Awaiting prepayment" until the deposit lands.

**REQ-SAL-022 — Shipment is gated by prepayment status** *(shipped)*
The fulfilment Saga (REQ-SAL-030 → REQ-SAL-035) refuses to advance to "ready to ship" while a prepayment invoice for the order is unsettled. When the deposit payment is matched (REQ-FIN-040), the gate releases automatically.

### 2.4 Order fulfilment lifecycle (Sales Order Fulfilment Saga)

A sales order moves through a fixed sequence of states. Operators do not click between states — events from other contexts drive transitions automatically.

**REQ-SAL-030 — State: Placed** *(shipped)*
The order has been captured and is durable. Inventory has been asked to reserve stock. If a line is short, inventory itself decides make-vs-buy and raises a replenishment request (§2.37) — sales does not ask manufacturing or purchasing directly; it parks until the shortage is replenished.

**REQ-SAL-031 — State: Awaiting Prepayment** *(shipped, prepayment terms only)*
The order is placed and a deposit invoice has been raised but not yet paid in full.

**REQ-SAL-032 — State: Awaiting Stock** *(shipped)*
Reservations are open for one or more lines (inventory has confirmed it does not have full stock available right now; the system is waiting for production completion or a future receipt).

**REQ-SAL-033 — State: Ready to Ship** *(shipped)*
Every line is fully reserved and the prepayment gate (if applicable) has cleared. Warehouse staff may post a shipment.

**REQ-SAL-034 — State: Goods Shipped** *(shipped)*
A shipment has been posted. A customer invoice has been auto-raised (REQ-FIN-030). The customer page shows tracking information.

**REQ-SAL-035 — State: Closed** *(shipped)*
The customer payment has been received and fully allocated to the invoice. The order is complete; no further activity is expected.

**REQ-SAL-036 — Compensation: Cancelled** *(shipped)*
A cancellation request at any pre-shipment state releases reservations, cancels any open work orders for the order's lines, reverses any prepayment journal postings, and closes the order at "Compensated/Cancelled". Cancellation past `goods_shipped` is rejected (the customer must process a return — out of scope today).

### 2.5 Cancellation

**REQ-SAL-040 — Customer-initiated cancellation** *(shipped)*
A cancel request flows through the sales API. The fulfilment Saga compensates in reverse: releases stock reservations, cancels open work orders, and reverses any prepayment-related GL postings. The customer sees the order as "Cancelled".

**REQ-SAL-041 — Hard cancel during manufacturing in progress** *(shipped)*
If a work order tied to the cancelled line is already in progress, the WO is hard-cancelled (WIP is written off). Soft-cancel (let the WIP finish then scrap) is deferred (§2.3 in `dev-todo.md`).

### 2.6 Customer invoicing (sales-side actions)

**REQ-SAL-050 — Customer invoice auto-raised on shipment** *(shipped)*
When the warehouse posts a shipment for an order, finance auto-creates the customer invoice using the shipment's line quantities, the order's line prices, the customer's billing address, and the order's tax rate.

### 2.7 Customer payments (sales view)

**REQ-SAL-060 — Apply customer payment to invoice(s)** *(shipped)*
A customer payment may be allocated to one or more invoices (single or multi-allocation). The order moves to Closed only when the linked invoice is fully paid.

---

## 3. Inventory (REQ-INV)

Inventory tracks the physical state of stock: how much exists, where, how much is committed, and how much can be promised to new demand. Every change is recorded as an immutable movement; balances are running sums over those movements.

### 3.1 Warehouses

**REQ-INV-001 — Warehouses are reference data** *(shipped)*
The system models multiple warehouses (e.g. `MAIN`, `MELB`). Two are seeded for the demo. Today the demo flow pins to `MAIN`; multi-warehouse fulfilment is deferred (§3.11 in `dev-todo.md`).

### 3.2 Stock balances

**REQ-INV-010 — Each warehouse-SKU pair has four running balances** *(shipped)*

| Balance | Meaning |
|---|---|
| **On-hand** | Physical quantity in the warehouse, including reserved portion. |
| **Reserved** | Quantity that has been committed to open sales orders or work orders — physically present but not available to new demand. |
| **Available** | `on_hand − reserved`. The quantity the system may promise to new demand. |
| **WIP** | Quantity currently being built into a sub-assembly — applies only to intermediate SKUs in a multi-level BOM. |

Balances are never edited directly. They are running sums over the movement journal.

**REQ-INV-011 — Tracked vs. non-tracked SKUs** *(shipped)*
Each SKU declares a tracking mode. Tracked SKUs (raw materials, finished goods) have balances; non-tracked (services) bypass inventory entirely.

### 3.3 Stock reservations

**REQ-INV-020 — Reserve stock for a sales-order line** *(shipped)*
When a sales order is placed, inventory tries to reserve each line's full quantity. Outcomes per line:
- **Fully reserved** — `on_hand` was sufficient; `reserved` increases by the quantity, available decreases.
- **Partially reserved / shortage** — `on_hand` was insufficient; inventory reserves what it can and, in the same transaction, raises a `ReplenishmentRequest(reason=sales_order_shortage)` that it routes by make-vs-buy (§2.37 — a make-to-stock work order or a purchase order). The sales order waits in Awaiting Stock until the replenishment tops up `on_hand` and the reservation retries.
- **Cancelled and re-emitted** — if the saga later needs to retry the reservation (e.g. once a replenishment has landed), the prior reservation is cancelled first to avoid double-booking.

**REQ-INV-021 — Reserve raw materials for a work order** *(shipped)*
When manufacturing releases a work order, inventory reserves each raw-material component (the BOM walk). Same outcomes as REQ-INV-020. A shortage triggers the unified replenishment loop via inventory (REQ-XBC-080, Trigger B).

**REQ-INV-022 — Release reservations on shipment** *(shipped)*
Posting a shipment reduces both `on_hand` and `reserved` by the shipped quantity. Available stays unchanged (the stock that left the building was already committed).

**REQ-INV-023 — Release reservations on cancel** *(shipped)*
A sales-order or work-order cancellation releases all of its reservations: `reserved` decreases, `available` increases, `on_hand` unchanged.

### 3.4 Goods receipts (from purchase orders)

**REQ-INV-030 — Post a goods receipt against a PO line** *(shipped)*
Receiving staff record received quantity per PO line. The system bumps `on_hand` by the received quantity, records a stock movement, and signals purchasing that the line is (partially or fully) received. Receipts can be split across multiple events for a single PO line.

**REQ-INV-031 — Receipts are immutable** *(shipped)*
Once posted, a receipt cannot be edited. Corrections post a new receipt with a reversing quantity (or a stock adjustment — REQ-INV-050).

### 3.5 Shipments (to customer)

**REQ-INV-040 — Post a shipment against a sales-order line** *(shipped)*
Warehouse staff confirm that goods left the building for a sales-order line. The system reduces `on_hand` and `reserved` by the shipped quantity, records a stock movement, and signals finance to raise the customer invoice (REQ-FIN-030).

**REQ-INV-041 — Shipments are gated** *(shipped)*
Shipment posting refuses if:
- the sales-order line is not fully reserved (REQ-INV-020 outcome must be Fully Reserved);
- a prepayment gate is active and unsettled (REQ-SAL-022);
- the order has already been shipped or cancelled.

### 3.6 Stock adjustments

**REQ-INV-050 — Post a stock adjustment** *(shipped)*
Warehouse staff post adjustments to correct discrepancies (cycle-count finds, breakages, write-offs). An adjustment carries: warehouse, SKU, delta quantity (positive or negative), reason code (e.g. cycle_count, breakage, write_off), free-text note. The system updates `on_hand` and records the movement.

**REQ-INV-051 — Negative adjustments cannot drive on-hand below zero** *(shipped)*
A negative adjustment that would breach zero is rejected (the user is asked to investigate the discrepancy).

### 3.7 Stock movement audit

**REQ-INV-060 — Every balance change is auditable** *(shipped)*
The system records an immutable movement row for every on-hand change (receipt, shipment, adjustment, work-order completion). Each row carries: warehouse, SKU, delta, source-document type and id (e.g. `purchase_order` + PO line id), timestamp, actor. Running balances are reproducible from the movement log.

### 3.8 Reorder policy projection

**REQ-INV-070 — Inventory mirrors each SKU's reorder policy locally** *(shipped)*
Reorder point and reorder quantity (REQ-PROD-020) are snapshotted onto the stock-item row at policy change. The user-visible stock-item page shows them per SKU.

### 3.9 Automatic replenishment *(NEW — §2.35)*

The reorder policy from REQ-PROD-020 is read by the new monitor described here. Together with REQ-INV-080 → REQ-INV-088 they implement Northwood's single unified replenishment loop — covering both policy-driven (reorder-point) and demand-driven (WO raw-material shortage) triggers. Full cross-context flow at REQ-XBC-080.

**The load-bearing design point of §2.35** — inventory becomes the single orchestration seam for replenishment. Manufacturing and purchasing only signal inventory; they never signal each other. The pre-§2.35 direct edge (`manufacturing.RawMaterialShortageDetected → purchasing.RawMaterialShortageDetectedHandler`, REQ-XBC-040 OLD) is removed and replaced by the new routing described below.

**REQ-INV-080 — Two trigger sources for automatic replenishment** *(planned, §2.35)*
The system raises a Replenishment Request from two triggers:

1. **Reorder-point breach** — after every action that reduces on-hand (shipment posting, stock adjustment, and any other on-hand-decrementing event), if `on_hand < reorder_point` AND `reorder_point > 0` AND no replenishment for this SKU/warehouse is already open.
2. **WO raw-material shortage** — when a work-order release fails to reserve enough of a raw-material component (inventory's reservation handler emits `RawMaterialShortageDetected`). Inventory's new shortage-to-replenishment handler converts each shortage component into a Replenishment Request — provided no replenishment for the SKU/warehouse is already open.

Both triggers raise the same `ReplenishmentRequest` aggregate and emit the same `inventory.ReplenishmentRequested` event. The downstream routing is identical.

**REQ-INV-081 — Replenishment Request fields** *(planned, §2.35)*
Each request carries: SKU, warehouse, requested quantity (defaults to the SKU's reorder quantity for reorder-point triggers; to the shortage component's missing quantity for WO-shortage triggers), target service (`manufacturing` or `purchasing`), reason (`reorder_point_breach` | `work_order_shortage`), status (`requested` → `dispatched` → `fulfilled`, or `cancelled`), timestamps for each transition.

**REQ-INV-082 — Route to manufacturing or purchasing based on make-vs-buy** *(planned, §2.35)*
The target service is derived from the SKU's make-vs-buy flags (REQ-PROD-010, snapshotted into inventory by REQ-INV-085 below):
- `is_manufactured = true` → manufacturing (preferred when both flags are true);
- `is_purchased = true` only → purchasing;
- both false (unsourceable SKU) → no request is raised; the system logs a warning and continues (REQ-INV-086).

Raw-material SKUs are typically buy-only (`is_purchased = true`, `is_manufactured = false`), so WO-shortage replenishments almost always route to purchasing.

**REQ-INV-083 — At most one open replenishment per SKU per warehouse** *(planned, §2.35)*
If a request is already in `requested` or `dispatched` status for the SKU/warehouse, a fresh breach (from either trigger) does **not** raise a duplicate. The system relies on the open request to close the gap.
*Rationale:* prevents the monitor from amplifying every shipment after a breach — or every WO release short on the same raw material — into a cascade of redundant requisitions / work orders. The system-wide invariant is enforced by a partial unique index in `inventory.replenishment_request`.

**REQ-INV-084 — Replenishment closes when downstream is fulfilled** *(planned, §2.35)*
- For manufacturing-routed requests: the request moves to `fulfilled` when the linked work order completes (the produced goods bump `on_hand`).
- For purchasing-routed requests: the request moves to `fulfilled` when the linked purchase order's receipt lands (`on_hand` increases at receipt).

**REQ-INV-085 — Inventory mirrors each SKU's make-vs-buy classification locally** *(planned, §2.35 Slice A)*
The `is_purchased` and `is_manufactured` flags are snapshotted into inventory at product creation (with defaults from product type) and updated on every classification change.

**REQ-INV-086 — Unsourceable-SKU handling** *(planned, §2.35)*
If both make-vs-buy flags are false for a SKU and either trigger fires, the system logs a warning and skips the request. The discrepancy is visible in logs but does not block other operations. Operators investigate and either classify the SKU correctly or accept the SKU as no-replenish.

**REQ-INV-087 — Manufacturing and purchasing never communicate directly about replenishment** *(planned, §2.35 — architectural invariant)*
After §2.35 ships, there is no operational event flowing directly from manufacturing to purchasing or vice versa for replenishment purposes. Both contexts subscribe to events emitted by inventory; the cross-context coupling is mediated by `inventory.ReplenishmentRequest`.
*Rationale:* preserves Northwood's bounded-context discipline — manufacturing reasons about production, purchasing reasons about procurement, and the "what should we replenish" decision is owned by inventory (which holds the authoritative on-hand state and the policies).
*Acceptance:* the architecture grep `Grep '^import com\.northwood\.manufacturing\.' purchasing-service/**/*.java → zero` and the reverse remain green. No new inbox handler in purchasing references a manufacturing event class, and vice versa, for replenishment-related events.

**REQ-INV-088 — Replenishment is asynchronous and visible to operators** *(planned, §2.35)*
Every step (request raised, dispatched, fulfilled) is observable in the Replenishment History view (REQ-RPT-060). Operators do not need to take action for the common case; the system advances the state automatically. Operators only intervene when a request stalls (e.g. supplier delivery delayed) or when the unsourceable-SKU warning surfaces (REQ-INV-086).

---

## 4. Manufacturing (REQ-MFG)

Manufacturing turns BOMs + routings into work orders, manages their progress through the shop floor, and signals when finished goods are ready for inventory.

### 4.1 BOMs (Bills of Materials)

**REQ-MFG-001 — Each makeable SKU may have one active BOM** *(shipped)*
A BOM has a header (the SKU it produces, version, status) and lines (component SKU + quantity + UoM). Lines may reference sub-assemblies (their own BOMs), forming a multi-level recipe.

**REQ-MFG-002 — BOM authoring** *(partial)*
Drafting, line editing, and activation are wired in the backend. The visual editor for BOMs is deferred (`dev-todo.md` §3.4). Today, fixtures and SQL seed are the primary authoring path.

**REQ-MFG-003 — BOM cycle detection** *(shipped)*
The system rejects a BOM-line edit that would create a cycle (A requires B which requires A). Detection runs on every change that could close a cycle.

### 4.2 Routings

**REQ-MFG-010 — Each makeable SKU may have an active routing** *(shipped — read-only)*
A routing lists the operations needed to produce the SKU (operation code, work center, estimated duration). The routing snapshot is copied onto each work order at release time so historical work orders are reproducible even after routings change.

### 4.3 Work orders — release mechanics

**REQ-MFG-020 — Release a work order** *(shipped — make-to-stock since §2.37)*
When a work order is released, the system walks the active BOM, snapshots component materials onto WO material lines, snapshots the routing onto WO operation lines, and asks inventory to reserve the raw materials. Since §2.37 the only release trigger is a manufacturing-routed Replenishment Request from inventory (REQ-MFG-030 / REQ-INV-082) — manufacturing builds **make-to-stock**, replenishing on-hand rather than fulfilling a specific sales-order line. (Before §2.37 a sales-order shortage routed to manufacturing directly via a `ManufacturingRequested` event; that make-to-order path is retired — inventory now owns the make-vs-buy decision.)

**REQ-MFG-021 — Sub-assembly recursion** *(shipped)*
A WO whose product is a multi-level BOM releases child WOs for the sub-assemblies first. A parent WO does not start manufacturing operations until its children complete. Parent-on-children gating is automatic.

### 4.4 Work orders — stock replenishment *(NEW — §2.35)*

**REQ-MFG-030 — Release a work order for a replenishment request** *(planned, §2.35 Slice C)*
When inventory raises a manufacturing-routed Replenishment Request (REQ-INV-082), manufacturing releases a stock work order. The WO is **not** tied to a sales-order line; it carries the originating `replenishment_request_id` instead (and, since §2.37 Slice 4, the `source_sales_order_header_id` of the order whose shortage triggered it, so reporting's production-planning board keeps the SO↔WO link). The BOM walk + material reservation + operation snapshotting follow the shared WO-release mechanics (REQ-MFG-020). Completion bumps the FG `on_hand` (REQ-MFG-080) and signals the replenishment as fulfilled (REQ-INV-084). If the SKU has no active BOM, manufacturing emits `ReplenishmentUndispatchable` and inventory cancels the request (which, for a sales-order-shortage replenishment, rejects the originating order).

### 4.5 Material reservation + shortage

**REQ-MFG-040 — Reserve raw materials at WO release** *(shipped)*
Each WO material line asks inventory to reserve its quantity. A shortage triggers REQ-XBC-080 (the unified replenishment loop, Trigger B — inventory raises a Replenishment Request and routes it to purchasing through the same channel as reorder-point breaches).

**REQ-MFG-041 — Material check status per WO** *(shipped)*
A WO carries a material status: `not_checked` → `material_check_pending` → `waiting_for_materials` or `released` based on reservation outcomes. Only `released` WOs may begin operations.

### 4.6 Operations and completion

**REQ-MFG-050 — Complete an operation** *(shipped)*
A shop-floor operator marks an operation complete. The system stamps actual-completion time and advances to the next operation. The WO moves between `planned` → `in_progress` → `partially_completed` → `completed` based on operation progress.

**REQ-MFG-051 — Skip an operation** *(shipped)*
An authorised operator may skip an operation (with a reason). Skipped operations are auditable but do not block WO completion.

**REQ-MFG-052 — Parent on children gating** *(shipped)*
A parent WO's operations cannot start until all child sub-assembly WOs are completed. The system enforces this automatically; the parent shows as `waiting_for_materials` until children are done.

### 4.7 WO cancellation

**REQ-MFG-060 — Cancel a work order** *(shipped — hard cancel)*
A WO may be cancelled. Reservations release; in-progress operations are halted; status moves to `cancelled`. Today hard-cancel applies regardless of operation progress (WIP is written off). Soft-cancel (let WIP finish, then scrap) is deferred (`dev-todo.md` §2.3).

### 4.8 WO prioritisation

**REQ-MFG-070 — Set a WO's priority** *(shipped)*
A planner may raise or lower a WO's priority via a single REST call. The reporting production-planning board re-sorts accordingly. Pure read-side action (no aggregate state change beyond the priority value).

### 4.9 Sub-assembly consumption

**REQ-MFG-075 — Sub-assemblies are consumed at parent completion** *(shipped)*
When a parent WO completes, the sub-assembly children it built are consumed: their WIP balances drop by the parent's completed quantity. Sub-assemblies that were dropped (data corruption: a completed child child with null quantity) are logged at WARN; their WIP stays elevated until reconciliation.

### 4.10 Make-vs-buy projection (manufacturing-side)

**REQ-MFG-080 — Manufacturing rejects WO requests for unmakeable SKUs** *(shipped)*
Manufacturing mirrors each SKU's make-vs-buy classification locally. A WO request for a SKU classified as buy-only (`is_manufactured = false`) is rejected at the saga level — the sales order remains open in `awaiting_stock`, and a manual escalation is needed.

### 4.11 Standard cost rollup

**REQ-MFG-090 — Materials cost rollup over the BOM** *(shipped)*
For each makeable SKU with an active BOM, manufacturing computes the materials-cost rollup recursively (component cost × component quantity, walked over sub-assemblies). The rolled-up cost is the manufacturing-side input to product master's `standard_cost` (REQ-PROD-041).

---

## 5. Purchasing (REQ-PUR)

Purchasing turns demand for buyable SKUs into supplier orders and tracks them through to delivery.

### 5.1 Supplier master

**REQ-PUR-001 — Register a supplier** *(shipped)*
A supplier record carries: supplier code (unique), display name, address, contact, default currency, default payment terms.

### 5.2 Supplier price list

**REQ-PUR-010 — Maintain supplier prices per SKU** *(shipped)*
A supplier may publish a price for any SKU it sells, with effective-from date and minimum-quantity threshold. Multiple suppliers may price the same SKU; multiple price tiers (different `min_quantity`) are supported.

**REQ-PUR-011 — Setting a duplicate price is a no-op** *(shipped)*
Setting the same price as already on file emits no event and writes no audit row.

### 5.3 Purchase requisitions

**REQ-PUR-020 — Purchase Requisitions arise from two live sources after §2.35** *(planned migration; pre-§2.35 state listed for history)*

| Source type | Trigger | Status |
|---|---|---|
| `manual`              | A buyer raises a requisition through the UI                                                                            | Shipped |
| `stock_replenishment` | Inventory's automatic replenishment loop (REQ-INV-080) raised a purchasing-routed replenishment — covers BOTH reorder-point breaches AND former WO raw-material shortages | Planned (§2.35) |
| `work_order_shortage` | *(retired by §2.35)* — was: manufacturing's shortage signal triggered purchasing directly                              | Removed by §2.35; historical rows preserved |

*Note:* the `work_order_shortage` flow is being retired specifically to enforce the manufacturing↔purchasing decoupling (REQ-INV-087). Existing PRs already in the database with that source type remain readable; the CHECK constraint keeps the value valid as a historical marker. The Java path that produced new ones (`PurchaseRequisitionService.createForWorkOrderShortage(...)` plus `purchasing.RawMaterialShortageDetectedHandler`) is **deleted** in §2.35 Slice D.

**REQ-PUR-021 — Auto-approval policy** *(shipped — to be extended)*
Pre-§2.35: PRs created by the work-order-shortage flow auto-approve at creation (configurable). Post-§2.35: PRs created by the `stock_replenishment` flow inherit the same auto-approve policy — so the operator experience is unchanged whether the request came from a reorder-point breach or a former WO-shortage. Manual PRs continue to land at draft and require buyer approval.

### 5.4 Purchase orders

**REQ-PUR-030 — Convert an approved PR to a PO** *(shipped)*
An approved PR is converted to a PO: supplier selected (default = approved-vendor's default), pricing pulled from the supplier price list, payment terms snapshotted from the supplier.

**REQ-PUR-031 — PO approval** *(shipped)*
A PO carries an approval flow: draft → approved. Only approved POs may be sent to the supplier and receive goods.

### 5.5 Goods receipts

**REQ-PUR-040 — Receive goods against a PO line** *(shipped, see REQ-INV-030)*
The receipt is recorded on the PO line (received quantity tally) and triggers inventory to bump on-hand.

### 5.6 Three-way match (purchasing perspective)

**REQ-PUR-050 — Supplier invoice quantity + price match** *(shipped, see REQ-FIN-060)*
When the supplier invoice arrives in finance, it is matched against the PO line and the receipt. Quantity must match the received total (tolerance: exact); price must match the PO line price within a configurable tolerance (default 2%). Mismatches park the invoice in a manual-review queue.

---

## 6. Finance (REQ-FIN)

Finance keeps the books in money. Every economic event in the other contexts (shipment, receipt, payment) posts a balanced journal entry to the GL. The journal is the system of record; the trial balance is a report off it.

### 6.1 Chart of accounts

**REQ-FIN-001 — Chart of accounts is seed data** *(shipped)*
The GL accounts are seeded once by SQL. Accounts cannot be edited through the application. The set covers: Cash/Bank (`1010`), Accounts Receivable (`1110`), Customer Deposits (`2110`), Accounts Payable (`2210`), Raw Materials Inventory (`1210`), FG Inventory (`1220`), Work In Progress (`1230`, §2.42), GRNI (`1300`), Revenue (`4000`), Materials COGS (`5200`), General COGS (`5000`), Write-off (`5500`).

### 6.2 Journal entries — double-entry posting

**REQ-FIN-010 — Every economic event posts one balanced journal entry** *(shipped)*
Debit total must equal credit total. Each line carries: account, debit/credit, amount, currency, line description, source-document type + id. The journal is append-only; lines are immutable once posted.

**REQ-FIN-011 — Journals tie back to their source document** *(shipped)*
Every journal carries `source_document_type` + `source_document_id` so a user can navigate from a shipment to the journal it produced (or vice versa). Bulk reversal by source-document tuple is supported.

**REQ-FIN-012 — Journal reversal** *(shipped)*
A posted journal may be reversed by posting an inverse-signed copy. The original stays on file; the reversal is itself a new journal. Reversal of reversal is rejected (the chain is immutable).

### 6.3 The perpetual-inventory postings

Each posts one balanced journal at the moment its source event fires. These are the operational heart of finance. The six purchase/sale postings (REQ-FIN-020–025) cover the buy→sell cycle; the three manufacturing postings (REQ-FIN-026–028, §2.42) cover the make cycle.

**REQ-FIN-020 — Goods receipt — Dr Inventory / Cr GRNI** *(shipped)*
On goods receipt: debit the SKU's inventory account (1210 or 1220 by valuation class), credit GRNI (1300) at the PO line price × received quantity.

**REQ-FIN-021 — Supplier invoice — Dr GRNI / Cr AP** *(shipped)*
On supplier invoice approval: debit GRNI (1300), credit AP (2210) at the invoice amount. The GRNI account net-zeros over a matched receipt-invoice pair.

**REQ-FIN-022 — Supplier payment — Dr AP / Cr Bank** *(shipped)*
On supplier payment: debit AP (2210), credit Bank (1010) at the payment amount.

**REQ-FIN-023 — Shipment — Dr COGS / Cr Inventory** *(shipped)*
On shipment posting: debit the SKU's COGS account (5200 or 5000 by valuation class), credit the SKU's inventory account, at the SKU's standard cost × shipped quantity.

**REQ-FIN-024 — Customer invoice — Dr AR / Cr Revenue** *(shipped)*
On customer invoice creation (auto-triggered by shipment): debit AR (1110), credit Revenue (4000) at the invoice amount.

**REQ-FIN-025 — Customer payment — Dr Bank / Cr AR** *(shipped)*
On customer payment: debit Bank (1010), credit AR (1110) at the payment amount.

**REQ-FIN-026 — Raw materials issued to a work order — Dr WIP / Cr Raw Materials** *(shipped, §2.42)*
When a work order's raw materials are fully reserved (issued to production), debit Work In Progress (1230), credit the material's inventory account (1210) at standard cost × reserved quantity. Establishes the manufacturing→finance edge; perpetual WIP, material-cost-only.

**REQ-FIN-027 — Work order completion — Dr Finished Goods / Cr WIP** *(shipped, §2.42)*
When a work order completes, debit the finished good's inventory account (1220), credit WIP (1230) at standard cost × completed quantity. Because every WIP leg posts at standard cost, WIP nets to zero per work order — no variance accounts in the material-only cut.

**REQ-FIN-028 — Sub-assemblies consumed — Dr WIP / Cr Finished Goods** *(shipped, §2.42)*
When a parent work order consumes its completed sub-assembly children, debit parent WIP (1230), credit the sub-assembly's FG account (1220) at standard cost — rolling each child's value into the parent's WIP so the parent's completion releases the full rolled-up cost.

### 6.4 Prepayments (customer deposits)

**REQ-FIN-030 — Prepayment invoice posts to Customer Deposits, not Revenue** *(shipped, §2.31)*
A prepayment invoice (REQ-SAL-021) posts: Dr AR / Cr Customer Deposits (2110). The deposit is a liability until the goods ship.

**REQ-FIN-031 — Prepayment payment** *(shipped)*
On customer prepayment receipt: Dr Bank / Cr AR (standard customer-payment shape; the AR has been Dr'd by REQ-FIN-030).

**REQ-FIN-032 — Revenue recognition at shipment** *(shipped, §2.31 Slice C)*
On shipment for a prepayment order, an additional journal posts: Dr Customer Deposits / Cr Revenue, releasing the deposit liability into revenue.

### 6.5 Customer invoices and payments

**REQ-FIN-040 — Customer payment allocation** *(shipped)*
A payment may be allocated across one or more invoices. The allocation total must equal the payment amount. Invoice `paid_amount` is maintained by DB trigger over the allocation rows — never updated directly.

**REQ-FIN-041 — Prepayment settlement** *(shipped, §2.31)*
A prepayment payment is matched against the prepayment invoice; the saga's prepayment gate (REQ-SAL-022) releases when matched.

### 6.6 Supplier invoices

**REQ-FIN-050 — Record a supplier invoice** *(shipped)*
A buyer / AP clerk records the supplier invoice number, date, lines (PO line + invoiced quantity + invoiced price). The system runs the three-way match (REQ-PUR-050) and either auto-approves or parks the invoice in the manual-review queue.

**REQ-FIN-051 — Manual-review queue** *(shipped)*
Invoices parked for manual review surface in `GET /api/supplier-invoices/pending-review`. An authorised user may manually approve or reject. Approval posts the GRNI/AP journal (REQ-FIN-021). Rejection is final (no resubmission flow today).

### 6.7 Currencies and exchange rates

**REQ-FIN-060 — Same-currency pass-through** *(shipped)*
Same-currency operations require no conversion.

**REQ-FIN-061 — Cross-currency conversion via exchange-rate table** *(shipped, partial)*
Cross-currency conversions look up an exchange rate from a maintained rate table (with inverse-rate fallback for missing direct rates). Triangulation through a base currency is out of scope.

**REQ-FIN-062 — Ad-hoc rate lookup** *(shipped)*
`GET /api/exchange-rate?from=&to=&date=` returns the exchange rate that would apply for a given pair on a given date (404 if no rate is on file).

---

## 7. Reporting (REQ-RPT)

Reporting is read-only. Every panel is a projection over events from the operational contexts. None of these views accept writes.

### 7.1 Sales Order 360

**REQ-RPT-001 — Sales Order 360 view** *(shipped)*
For any sales order: customer, lines, fulfilment-state lozenge, prepayment status (if applicable), shipment(s), invoice(s), payment(s) — everything about the order on one page.
*URL:* `GET /api/sales-orders/{id}/360`.

### 7.2 Purchase Order Tracking

**REQ-RPT-010 — PO Tracking view** *(shipped)*
For any purchase order: supplier, lines, approval status, receipt status (open / partial / received), invoice status, payment status. Used by buyers to track open POs.
*URL:* `GET /api/purchase-orders/{id}/tracking`.

### 7.3 Production Planning Board

**REQ-RPT-020 — Work-order board** *(shipped)*
For any work order: WO header, materials, operations, current status, priority, parent / child links. Sorted by priority. Used by shop-floor planners.
*URL:* `GET /api/work-orders/{id}/board`.

### 7.4 Material Shortages

**REQ-RPT-030 — Open material-shortage list** *(shipped)*
Lists every open material shortage (WO short on raw materials), with the requisition / PO that closes it (if any). Used by buyers and planners.
*URL:* `GET /api/material-shortages`.

### 7.5 Available-to-Promise

**REQ-RPT-040 — ATP view per SKU** *(shipped)*
For each SKU: on-hand, reserved, available, plus forward-looking adjustments (open POs adding stock, open WOs adding stock, open SOs consuming stock). Used by sales staff before promising delivery dates.
*URL:* `GET /api/atp`.

### 7.6 Financial Dashboard

**REQ-RPT-050 — Financial dashboard snapshot** *(shipped)*
Single-page view of: inventory value, accounts receivable, accounts payable, work-in-progress value (zero today — gated on a costing decision). Updated continuously as events flow.
*URL:* `GET /api/financial-dashboard/snapshot`.

### 7.7 Replenishment History *(NEW — §2.35)*

**REQ-RPT-060 — Replenishment history view per SKU** *(planned, §2.35 Slice F)*
For any SKU: chronological list of replenishment requests (REQ-INV-081) with their status, target service, requested quantity, and the linked downstream WO or PO. Used by planners reviewing the system's automatic replenishment behaviour.
*URL:* `GET /api/replenishment-history?productId=&limit=`.
Displayed as a widget on the stock-items page in both the demo SPA and the operational ERP SPA.

---

## 8. Cross-context business flows (REQ-XBC)

Each business flow below spans multiple bounded contexts. They are the orchestration layer end users actually perceive. The detailed state machine and compensation logic for each is in `docs/sagas.md` (developer reference); this section captures only the user-visible business semantics.

### 8.1 Order-to-Cash (the main sales flow)

**REQ-XBC-010 — Order-to-Cash end-to-end** *(shipped)*
A customer order moves through: order placed → (optional prepayment invoice + payment) → stock reserved → (if shortage) inventory raises a replenishment that tops up on-hand (a make-to-stock WO or a purchase, §2.37) → reservation retries and succeeds → shipment posted → customer invoice raised → customer payment received → order closed.

### 8.2 Procure-to-Pay (the main purchasing flow)

**REQ-XBC-020 — Procure-to-Pay end-to-end** *(shipped)*
Demand (manual / shortage / replenishment) raises a PR → PR approved + converted to PO → PO approved + sent to supplier → goods received → supplier invoice approved (three-way match) → supplier payment posted.

### 8.3 Sales-shortage → make-to-stock replenishment

**REQ-XBC-030 — Sales-shortage make-to-stock flow** *(shipped — re-routed through inventory by §2.37)*
A sales order for a makeable SKU with insufficient stock no longer triggers manufacturing directly. Inventory detects the shortage on reservation, raises a `ReplenishmentRequest(reason=sales_order_shortage)`, and (because the SKU is makeable) routes it to manufacturing, which releases a **make-to-stock** work order (or a tree of sub-assembly WOs — REQ-MFG-021). Operations complete bottom-up; the top-level WO's completion bumps FG on-hand, which fulfils the replenishment; the parked sales order retries its reservation, succeeds, and advances to Ready to Ship. (Before §2.37 the WO was bound to the sales-order line and tracked by the sales saga; now the WO replenishes stock and the sales order simply re-reserves once stock is available. See REQ-XBC-080 for the unified replenishment loop this is now a case of.)

### 8.4 Material-Shortage → Auto-Requisition

**REQ-XBC-040 — Material-shortage auto-requisition** *(shipped via direct edge; retired by §2.35 — see REQ-XBC-080)*

*Pre-§2.35 (the flow currently in production):* when a WO release finds short raw materials, manufacturing emits `RawMaterialShortageDetected` which purchasing consumes directly to raise a PR with source type `work_order_shortage`. The PR auto-approves, converts to a PO, and is dispatched to the approved supplier. When the goods are received, the WO's material status flips to `released` and operations may begin.

*Post-§2.35 (planned):* the same business outcome is preserved, but the path changes. Inventory consumes `RawMaterialShortageDetected` and raises a `ReplenishmentRequest` with `reason='work_order_shortage'`; that request flows through `inventory.ReplenishmentRequested → purchasing.replenishment-dispatcher → PurchaseRequisition (source_type='stock_replenishment')`. Manufacturing and purchasing no longer share an operational event. See REQ-XBC-080 for the unified flow, REQ-INV-087 for the decoupling invariant.

*User-visible difference:* none — the auto-approval, supplier selection, PO conversion, and WO material-status flip all happen the same way. The change is purely architectural.

### 8.5 Unified Replenishment Loop *(NEW — §2.35)*

**REQ-XBC-080 — Inventory-orchestrated replenishment, end-to-end** *(planned, §2.35 — full loop)*
Northwood's single unified replenishment flow. Covers both policy-driven (reorder-point) and demand-driven (WO raw-material shortage) triggers through one channel.

**Trigger A — Reorder-point breach (policy-driven):**
1. A planner sets a SKU's reorder point and reorder quantity via the product card (REQ-PROD-020).
2. Operations reduce on-hand — shipments (REQ-INV-040), write-down adjustments (REQ-INV-050).
3. The first decrement that brings `on_hand < reorder_point` raises a Replenishment Request with `reason='reorder_point_breach'`.

**Trigger B — WO raw-material shortage (demand-driven):**
1. Manufacturing releases a work order whose BOM lists raw materials that inventory cannot fully reserve.
2. Inventory emits `RawMaterialShortageDetected` (already existing).
3. Inventory's new shortage-to-replenishment handler converts each shortage component into a Replenishment Request with `reason='work_order_shortage'`.

**Common downstream — identical for both triggers:**

4. The request is routed by make-vs-buy (REQ-INV-082):
   - **Manufactured-path:** manufacturing releases a stock work order (REQ-MFG-030); on completion, the FG `on_hand` bumps and the request flips to fulfilled.
   - **Purchased-path:** purchasing creates a stock-replenishment PR (REQ-PUR-020); the PR auto-approves, converts to a PO, the PO is sent to the supplier, goods are received, `on_hand` bumps, and the request flips to fulfilled.
5. The Replenishment History view (REQ-RPT-060) shows the request lifecycle per SKU with the trigger reason for audit and tuning.
6. The loop closes silently — no operator action is needed.

**REQ-XBC-081 — Operator-visible behaviour** *(planned, §2.35)*
A planner using the stock-items page sees:
- The on-hand level drop on each shipment.
- When the level crosses the reorder point (Trigger A) OR a WO release reports a shortage (Trigger B), a new "Replenishment activity" row appears for the SKU with the appropriate reason badge.
- The row shows status progression (Requested → Dispatched → Fulfilled).
- The linked WO or PO is reachable from the row.
- After fulfilment, the row closes; on-hand reflects the topped-up level.

The planner never has to manually raise a PR or a WO for either trigger.

**REQ-XBC-082 — Bounded-context decoupling invariant** *(planned, §2.35)*
After §2.35 ships, manufacturing and purchasing exchange **no** operational events with each other in the replenishment domain. Every shortage signal flows through inventory's `ReplenishmentRequest`. This is the load-bearing architectural point of §2.35; see REQ-INV-087 for the testable invariant. Reference-data flows (e.g. `purchasing.SupplierProductPriceChanged → manufacturing.materials-cost-rollup`) are out of scope — they are read-side data projections, not operational coupling.

### 8.6 Cancellation (sales-initiated)

**REQ-XBC-090 — Cancel a sales order before shipment** *(shipped)*
A cancel request before shipment compensates the order: releases reservations (REQ-INV-023), cancels open work orders for the order's lines (REQ-MFG-060), reverses any prepayment GL postings (REQ-FIN-012). The order closes at Cancelled. Cancellation after shipment is rejected.

---

## 9. Security and roles (REQ-SEC)

**REQ-SEC-001 — Role-based access** *(shipped)*
Endpoints are gated by realm role. Today's role set:

| Role | What they do |
|---|---|
| `sales_clerk`            | Place / cancel sales orders, view customer info |
| `warehouse_operator`     | Post shipments, post goods receipts, view stock |
| `warehouse_manager`      | Force-release reservations, post adjustments *(scaffold only)* |
| `production_planner`     | Release / prioritise / cancel work orders |
| `production_operator`    | Complete / skip operations |
| `buyer`                  | Raise PRs, approve POs, manage supplier prices |
| `ap_clerk`               | Record supplier invoices, post supplier payments |
| `ar_clerk`               | Record customer payments, view invoices |
| `accountant`             | Reverse journals, view financial dashboard, edit standard cost |
| `product_administrator`  | Maintain SKUs, BOMs, reorder policies, valuation classes |
| `auditor`                | Read-only everywhere *(scaffold only)* |
| `sysadmin`               | Keycloak realm admin only *(scaffold only)* |

Demo accounts include one user per role.

**REQ-SEC-002 — Audit trail** *(shipped)*
Every state-changing API call is recorded in the audit trail with: actor, endpoint, body summary, target aggregate. Visible via `GET /api/audit-entries`.

---

## Appendix — terms used in this document

| Term | Meaning |
|---|---|
| **SKU** | Stock-Keeping Unit — a product variant identified by a unique product code. |
| **BOM** | Bill of Materials — the recipe (component SKUs × quantities) for a makeable SKU. |
| **WO** | Work Order — a manufacturing instruction to produce N units of a SKU. |
| **PR / PO** | Purchase Requisition (internal demand) / Purchase Order (supplier-facing). |
| **GRNI** | Goods Received Not Invoiced — the bridging account between receipt and supplier invoice. |
| **AR / AP** | Accounts Receivable / Accounts Payable. |
| **ATP** | Available to Promise — the quantity sales may commit to new orders. |
| **WIP** | Work-In-Progress — sub-assemblies built but not yet consumed by their parent WO. |
| **Saga** | A multi-step business transaction that spans contexts, with compensation if interrupted. |
| **Reorder point** | The on-hand level at which automatic replenishment fires. |
| **Reorder quantity** | The quantity the system requests each time replenishment fires. |
| **Target service** | For a Replenishment Request: which downstream context (manufacturing or purchasing) handles it. |
