# Northwood ERP — Demo Script

Step-by-step for getting the full stack — Postgres, the seven Spring Boot services, the BFF, and the React Web UI — up and ready to demo, plus the per-demo runbook.

The end-state: open `http://localhost:5173`, click **🎬 Scenarios → 7.1**, and watch all three sagas drive to completion across the saga console while saying "this is what we built" out loud.

Every event name, saga state, endpoint, and request body below is verified against the codebase as of 2026-05-15. Companion docs: `CLAUDE.md` (architecture invariants), `dev-todo.md` (implementation backlog).

---

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| Java | 21 | `java -version` |
| Maven | 3.9+ | `mvn -v` |
| Docker | recent | `docker --version` |
| Node.js | 20+ | `node --version` (Node 25 confirmed; Node 18 likely fine) |
| npm | 10+ | bundled with Node |
| Free ports | 5432, 5173, 8080–8087 | nothing else listening |

**Windows**: PowerShell is the assumed shell. If `npm` fails with `running scripts is disabled on this system`, use `npm.cmd` instead (the Vite shell scripts inside `node_modules/.bin` are unaffected).

---

## One-time setup

```powershell
# from repo root — Postgres 17 + Kafka 4.1.2 (KRaft) + Keycloak 26.
# The -f docker-compose.seed.yml override pre-loads the demo fixtures (products, customers,
# BOMs, GL chart, …) this script relies on; the base file alone comes up with an empty schema.
docker compose -f docker-compose.yml -f docker-compose.seed.yml up -d
mvn install -DskipTests                             # builds all 12 modules (incl. demo-web-ui-bff + erp-web-ui-bff)
cd demo-web-ui ; npm.cmd install ; cd ..            # one-time SPA dep install
cd erp-web-ui ; npm.cmd install ; cd ..             # one-time install for the operational ERP SPA
```

Postgres runs its init scripts once, on the first boot of a fresh data volume. The base `docker-compose.yml` mounts only the **schema baseline** (`db/northwood_erp.sql` — per-service schemas, roles, grants, the saga CHECK constraint already extended for `invoice_paid`); the `docker-compose.seed.yml` override additionally mounts the **demo fixtures** (`db/northwood_erp_seed.sql` — products, customers, suppliers, BOMs, GL chart, …). This walkthrough needs the fixtures (CUST-001, the SKUs, etc.), which is why the command above layers both files with `-f`. For an empty database you populate via events from zero, drop the override and run `docker compose up -d`. To start fresh later, repeat the seeded command after a wipe: `docker compose down -v ; docker compose -f docker-compose.yml -f docker-compose.seed.yml up -d`.

Keycloak loads the `northwood` realm from `db/keycloak/northwood-realm.json` on first boot — 13 roles + 13 demo users (one per role, `username == password`). Admin console at `http://localhost:8090/` (`admin` / `admin`).

### The 13 demo personas (§1 Security)

| Username | Role | Persona |
|---|---|---|
| emma | catalog_manager | Product master CRUD, pricing, reorder policy |
| sarah | sales_clerk | Place orders, view 360, view ATP |
| sales-mgr | sales_manager + sales_clerk | All clerk + cancel orders |
| mike | warehouse_clerk | Stock movements, goods receipts, shipments |
| warehouse-mgr | warehouse_manager + warehouse_clerk | All clerk + force-release reservations |
| linda | production_planner | View board, set priority, complete operations, edit/activate BOM |
| production-sup | production_supervisor + production_planner | All planner + cancel WO, skip operation |
| tom | purchasing_clerk | Create PR/PO (draft), post goods receipts |
| purchasing-mgr | purchasing_manager + purchasing_clerk | All clerk + approve PO, author supplier prices |
| olivia | accountant | Record supplier invoices, record payments |
| daniel | finance_manager + accountant | All accountant + reverse journals, manual-approve invoices |
| auditor | auditor | Read-only on every endpoint |
| sysadmin | sysadmin | Keycloak admin only; no business data |

The **persona switcher** in the ERP Web UI (top-right dropdown next to the user chip) lets a presenter switch between personas without leaving the screen — pick a persona, the BFF logs out, and Keycloak's login form loads with the username pre-filled. Type the password (== username) and continue. The 403-tooltip behaviour on action buttons reacts to the new role automatically.

### Why Kafka is required

The seven services run as separate JVMs in this layout. The only wired event bus is `KafkaEventPublisher` + `KafkaInboxDispatcher`, both registered under `@Profile("kafka")` (along with each service's outbox drainer). Under the default `dev` profile no `EventPublisher` is registered — events accumulate in the outbox table with nothing draining them, so cross-service flows (e.g. sales emitting `ManufacturingRequested` for manufacturing to consume) silently never fire.

Every demo below assumes services are launched with `SPRING_PROFILES_ACTIVE=kafka` (set in each terminal before `mvn spring-boot:run`). The Kafka container in `docker-compose.yml` is pre-configured for the single-broker case (`KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1` and friends — without those, internal topics fail to create and consumer groups silently hang on `FIND_COORDINATOR`).

---

## Bringing the stack up

You need **nine terminals** for a full demo: seven services + the BFF + the SPA. IntelliJ run configurations (or `tmux split-windows`) help; the simplest path is nine PowerShell windows.

**Set the Kafka profile in each service terminal before `mvn spring-boot:run`** — without it, services use the in-JVM bus and cross-service events go nowhere. The BFF and the SPA don't need a profile.

```powershell
# Run this once per service terminal (terminals 1–7) before the mvn command:
$env:SPRING_PROFILES_ACTIVE = "kafka"
```

| # | Terminal | Command (run from repo root) | Profile | Port | Wait for |
|---|---|---|---|---|---|
| 1 | product-service       | `mvn -pl product-service spring-boot:run`       | `kafka` | 8081 | `Started ProductApplication` |
| 2 | sales-service         | `mvn -pl sales-service spring-boot:run`         | `kafka` | 8082 | `Started SalesApplication` |
| 3 | inventory-service     | `mvn -pl inventory-service spring-boot:run`     | `kafka` | 8083 | `Started InventoryApplication` |
| 4 | manufacturing-service | `mvn -pl manufacturing-service spring-boot:run` | `kafka` | 8084 | `Started ManufacturingApplication` |
| 5 | purchasing-service    | `mvn -pl purchasing-service spring-boot:run`    | `kafka` | 8085 | `Started PurchasingApplication` |
| 6 | finance-service       | `mvn -pl finance-service spring-boot:run`       | `kafka` | 8086 | `Started FinanceApplication` |
| 7 | reporting-service     | `mvn -pl reporting-service spring-boot:run`     | `kafka` | 8087 | `Started ReportingApplication` |
| 8 | **demo-web-ui-bff**   | `mvn -pl demo-web-ui-bff spring-boot:run`       | (none)  | 8080 | `Started BffApplication` |
| 9 | demo-web-ui           | `cd demo-web-ui ; npm.cmd run dev`              | (none)  | 5173 | `Local: http://localhost:5173/` |
| 10 | **erp-web-ui-bff**   | `mvn -pl erp-web-ui-bff spring-boot:run`        | (none)  | 8089 | `Started ErpBffApplication` |
| 11 | erp-web-ui           | `cd erp-web-ui ; npm.cmd run dev`               | (none)  | 5174 | `Local: http://localhost:5174/` |

The **BFF** sits in front of the seven services and gives the SPA a single origin. Without it the SPA can't reach any service through the simplified Vite proxy. Start it after at least one of the saga-owning services (sales / manufacturing / purchasing) is up — the aggregated saga endpoint will gracefully degrade to whichever services are reachable.

Each service takes ~10–15 s to boot. Bringing them all up takes ~2 minutes; you can start them in parallel. Kafka itself takes ~30 s after `docker compose up` to pass its healthcheck — wait for that before launching the services or the first publish will fail.

**Smoke check**: visit `http://localhost:5173` — the **Dashboard** should show "Today · AUD" with all-zero KPIs. The bottom **Event drawer** is the Phase 1 stub (synthetic events every ~2 s).

If any service fails to boot, look at the terminal that failed. Most boot failures are Postgres-not-up, Kafka-not-up, or another instance already on the port.

---

## Seed data the demos rely on

| Entity | UUID / code | Notes |
|---|---|---|
| Customer **Sydney Home Living** | code `CUST-001` · id `00000000-0000-7000-8000-000000000030` | seed buyer used in every sales-side scenario |
| Product **FG-TABLE-001** Wooden Dining Table | `00000000-0000-7000-8000-000000000001` | 2 on hand, BOM active |
| Product **RM-LEG-001** Table Leg | `00000000-0000-7000-8000-000000000003` | 20 on hand; 4 used per table |
| Product **RM-BOARD-001** Wooden Board | `00000000-0000-7000-8000-000000000002` | 5 on hand; 1 per table |
| Product **FG-CABINET-001** Storage Cabinet | `00000000-0000-7000-8000-000000000200` | sub-assembly demo set |
| Warehouse **MAIN** | `00000000-0000-7000-8000-000000000020` | |
| Supplier **Pinewood Supplies** | seeded; check `purchasing.supplier` for id | used for shortage requisitions |

---

## Three ways to drive the demo

You can pick one or mix. The audience-facing experience is best with **A. Scenario Runner**; the deep-dive experience is best with **B. Manual UI**; the wire-level engineering deep-dive is **C. curl + Swagger**.

### A. Scenario runner (recommended for live audiences)

1. Open `http://localhost:5173/saga-console` (so the audience can see the three saga columns).
2. Click **🎬 Scenarios** in the top bar.
3. Pick a scenario:
   - **3.1 — Sales fulfilment (happy path)** — small order, no shortage. ~3 minutes including human steps.
   - **5.2 — Raw material shortage** — order exceeds raw materials, drives PR/PO/receipt. ~3 minutes.
   - **7.1 — Big order touches every service** — full end-to-end. ~6 minutes.
4. The runner modal opens; auto steps fire immediately, human steps pause with a hint about which page to open. Click **Run step** when the human action is complete; click **Skip** to bypass; **Abort** if something goes wrong.
5. Captured context UUIDs (sales order id, PO id, etc.) appear in the collapsible panel at the bottom of the modal — copy them into the inline forms when prompted.

While the scenario is running, navigate to other pages with the sidebar and the runner stays alive in the background; the **Scenarios** button in the top bar pulses to show it's active.

### B. Manual UI walkthrough (deep dive)

Drive the demo step by step using the persona-grouped sidebar. Suggested run-order:

1. **Emma** — `/products` → click ✏ on Wooden Dining Table → change the price → confirm `product.SalesPriceChanged` (and/or `product.StandardCostChanged`) lands in the saga / event log.
2. **Sarah** — `/sales-orders/new` → place 1 × FG-TABLE-001 → submit → land on the **Saga Console**.
3. **Linda** — `/production-board` → click the released WO → **Complete operation** for each op (sequence 10, 20, 30…). Each completion advances the make-to-order saga.
4. **Mike** — `/shipments` → paste the SO id from the saga's `domainKey` → post the shipment.
5. Watch finance auto-create a customer invoice (the sales saga jumps to `invoice_created`).
6. **Olivia** — `/payments` → Customer (AR) tab → paste the customer-invoice id → Record. Sales saga reaches `completed`.

For 5.2 / 7.1 manually, place a 10-quantity order to force the shortage and follow §Demo 7 from step 4 onward.

### C. curl + Swagger (engineering deep dive)

The `curl` bodies in §Demo 1–7 below are the canonical wire-level walkthroughs. Useful when you need to show the actual JSON, tail Postgres in parallel, or hit endpoints the UI doesn't surface (e.g. `POST /api/journal-entries/{id}/reverse`).

Each service exposes Swagger UI at `http://localhost:808X/swagger-ui/index.html`.

---

## What to point at while the audience watches

| Page | URL | What's interesting |
|---|---|---|
| **Saga Console** | `/saga-console` | Three live columns, current state pulses, rows flash on `version` change. The **headline view**. |
| **Sales Order 360** | `/sales-orders` (then click a row) | Six-stage timeline filling in left-to-right as events arrive. |
| **PO tracking** | `/purchase-orders` | Money-flow bars (ordered / received / invoiced / paid) accumulate. |
| **Production Board** | `/production-board` | Per-WO progress bars; shortage callouts. |
| **ATP** | `/atp` | On-hand minus reservations + incoming. |
| **Material Shortages** | `/material-shortages` | 4-stage walk: open → purchase_requested → purchase_ordered → resolved. |
| **Financial Dashboard** | `/` | KPIs accumulate: revenue, COGS, gross %, open SO/PO/WO counts. |
| **Event drawer** | bottom of every page | Phase 1 stub today (synthetic events) — real `/events` aggregator is in `dev-todo.md`. |

### Watching the database directly (for the wire-level deep dive)

Three useful psql queries to project on a side screen:

```sql
-- Sales fulfilment saga (one row per order)
SELECT sales_order_header_id, saga_state, current_step, data
  FROM sales.sales_order_fulfilment_saga ORDER BY updated_at DESC;

-- Make-to-order saga (one row per work order)
SELECT work_order_id, saga_state, current_step
  FROM manufacturing.make_to_order_saga ORDER BY updated_at DESC;

-- Purchase-to-pay saga (one row per PO)
SELECT purchase_order_header_id, saga_state, current_step
  FROM purchasing.purchase_to_pay_saga ORDER BY updated_at DESC;
```

---

## Demo 1 — CQRS: product master as the upstream Open Host

The product service owns SKUs and pricing. Reorder policy is owned upstream too (Material Master / Shape A): inventory's `reorder_point` / `reorder_quantity` are projected from `product.ReorderPolicyChanged`.

**UI alternative for Demo 1:** all four mutating commands have inline modals on `/products` (click ✏ on a row).

### 1.1 — Register a new product

```bash
curl -X POST http://localhost:8081/api/products \
  -H 'content-type: application/json' \
  -d '{"sku":"FG-CHAIR-001","name":"Dining Chair","productType":"finished_good",
       "baseUomId":"00000000-0000-7000-8000-000000000010",
       "salesPrice":120,"standardCost":45,"currencyCode":"AUD"}'
```

**Outbox:** `product.ProductCreated`. **Today's projections (§1F.2 / §1F.6a / §1F.6b, 2026-05-14 → 2026-05-15):** five services each seed a stub row from the event — `inventory.stock_item`, `sales.product_card` (NULL price + currency until `SalesPriceChanged`), `manufacturing.product_card` (type-derived make-vs-buy default), `finance.product_card` (NULL `standard_cost` + `valuation_class` until the respective change event), `reporting.available_to_promise_view`. Purchasing has no product read model of its own.

### 1.2 — Change pricing

```bash
curl -X PUT http://localhost:8081/api/products/{id}/sales-price \
  -H 'content-type: application/json' \
  -d '{"salesPrice":135,"currencyCode":"AUD"}'

curl -X PUT http://localhost:8081/api/products/{id}/standard-cost \
  -H 'content-type: application/json' \
  -d '{"standardCost":50,"currencyCode":"AUD"}'
```

**Outbox:** `product.SalesPriceChanged` from the first call; `product.StandardCostChanged` from the second. **Projections:** sales-service consumes `SalesPriceChanged` via `SalesPriceChangedHandler` (new orders quote the new price; existing lines are price-denormalised at order time). Finance consumes `StandardCostChanged` via `StandardCostChangedHandler` → writes `finance.product_card.standard_cost`; `ShipmentPostedCogsHandler` reads that column to drive COGS (shipment-line-stamped `unitCost` is only a documented cold-start fallback).

### 1.3 — Set reorder policy

```bash
curl -X PUT http://localhost:8081/api/products/{id}/reorder-policy \
  -H 'content-type: application/json' \
  -d '{"reorderPoint":5,"reorderQuantity":10}'
```

**Outbox:** `product.ReorderPolicyChanged`. **Projection:** `inventory.stock_item` updates within one outbox poll. The integration test `ReorderPolicyChangedSeamIT` exercises this end-to-end. The endpoint rejects on a `discontinued` product.

### 1.4 — Discontinue

```bash
curl -X POST http://localhost:8081/api/products/{id}/discontinue
```

**Outbox:** `product.ProductDiscontinued`. **Today's behaviour (§1F.1, 2026-05-14):** six services consume the event. Sales `placeOrder` rejects new lines for the SKU with `ProductDiscontinuedException`. Manufacturing retires `product_replenishment` and the active BOM. Purchasing's PR entry-point rejects requisitions for the SKU. Inventory + finance + reporting/atp stamp their own `discontinued_at`. Existing draft sales orders are **not** retroactively flagged; "reorder rules stop generating purchase suggestions" remains future-tense (no auto-reorder job exists today for the flag to suppress).

### 1.5 — Make-vs-buy classification

```bash
curl -X PUT http://localhost:8081/api/products/{id}/make-vs-buy \
  -H 'content-type: application/json' \
  -d '{"manufacturedInternally":true,"purchasedExternally":false}'
```

**Outbox:** `product.MakeVsBuyChanged`. **Projection:** manufacturing's `ProductReplenishmentProjectionService` consumes this; the make-to-order entry gate (`ManufacturingRequestedHandler`) reads it before BOM lookup so a "purchased only" product is rejected up front.

---

## Demo 2 — CQRS: cross-context read models in reporting

Reporting is inbox-only — no command APIs, no outbox. Six projections are wired today.

| Read model | Endpoint | UI page | Driven by |
|---|---|---|---|
| `sales_order_360_view` | `GET /api/sales-orders/{id}/360` | `/sales-orders` | sales + manufacturing + inventory + finance events |
| `purchase_order_tracking_view` | `GET /api/purchase-orders/{id}/tracking` | `/purchase-orders` | purchasing + inventory + finance events |
| `production_planning_board` | `GET /api/work-orders/{id}/board` | `/production-board` | manufacturing + inventory events |
| `material_shortage_view` | `GET /api/material-shortages` (list) `/{productId}` | `/material-shortages` | manufacturing + purchasing + inventory events |
| `available_to_promise_view` | `GET /api/atp` (list) `/{productId}` | `/atp` | inventory + manufacturing + purchasing events |
| `financial_dashboard_daily` | `GET /api/financial-dashboard` (list, AUD default) `/{date}` | `/` (Dashboard) | finance + sales + purchasing + manufacturing events |

The financial dashboard's `inventory_value`, `accounts_receivable`, and `accounts_payable` are populated via `GET /api/financial-dashboard/snapshot` (shipped 2026-05-12; reporting projects `product_card` from `StandardCostChanged` and JOINs ATP × cost; AR/AP are SUM-windows over the SO360 + PO tracking projections). Only `wip_value` is still 0 — gated on a costing decision (LIFO / FIFO / weighted-avg) for `wip_balance.average_cost`.

---

## Demo 3 — Saga: sales-order fulfilment (happy path)

**Important framing:** every sales order with an active BOM goes through make-to-order. The reservation in step 2 reserves a customer-facing slot; supply is produced by manufacturing in step 4. There is no separate "stock-only" path.

### 3.1 — Place an order; watch all three sagas drive it

Pre-state: 2 × FG-TABLE-001 on hand, 20 × RM-LEG-001 on hand (need 4 per table = 4 ok).

```bash
curl -X POST http://localhost:8082/api/sales-orders \
  -H 'content-type: application/json' \
  -d '{"orderNumber":"SO-DEMO-3-1",
       "customerCode":"CUST-001",
       "currencyCode":"AUD",
       "lines":[{"productId":"00000000-0000-7000-8000-000000000001",
                 "productSku":"FG-TABLE-001",
                 "productName":"Wooden Dining Table",
                 "orderedQuantity":1,
                 "unitPrice":320}]}'
```

**Saga state machine** (`sales.sales_order_fulfilment_saga.saga_state`):

| State | Trigger to advance |
|---|---|
| `started` | worker emits `sales.StockReservationRequested` |
| `stock_reservation_requested` | inbound `inventory.StockReserved` |
| `stock_reserved` | worker emits `sales.ManufacturingRequested` |
| `manufacturing_requested` | inbound `manufacturing.WorkOrderCreated` |
| `manufacturing_in_progress` | inbound `manufacturing.WorkOrderManufacturingCompleted` |
| `ready_to_ship` | post a shipment via `POST /api/shipments` (inventory) |
| `goods_shipped` | inbound `finance.CustomerInvoiceCreated` (auto-created from `sales.SalesOrderShipped`) |
| `invoice_created` | inbound `finance.CustomerPaymentReceived` (full settlement) |
| `completed` | terminal |

Partial customer payment lands on `invoice_paid` instead, parking until the next allocation.

**To drive the saga to completion (UI: Scenario 3.1 does all of this):**

1. Wait for the make-to-order saga to hit `raw_materials_reserved` (auto, single tick).
2. Complete each work order operation (UI: production board → ▶ Complete operation):
   ```bash
   curl -X POST http://localhost:8084/api/work-orders/{wo-id}/operations/{seq}/complete \
     -H 'content-type: application/json' \
     -d '{"actualMinutes":30}'
   ```
3. Post a shipment (UI: `/shipments`):
   ```bash
   curl -X POST http://localhost:8083/api/shipments \
     -H 'content-type: application/json' \
     -d '{"shipmentNumber":"SH-DEMO-3-1",
          "salesOrderHeaderId":"...",
          "warehouseCode":"MAIN",
          "lines":[{"salesOrderLineId":"...",
                    "productId":"00000000-0000-7000-8000-000000000001",
                    "productSku":"FG-TABLE-001",
                    "productName":"Wooden Dining Table",
                    "shippedQuantity":1,
                    "unitCost":150}]}'
   ```
4. Customer invoice is auto-created from the shipment event. Pay it (UI: `/payments` → Customer tab):
   ```bash
   curl -X POST http://localhost:8086/api/payments/customer \
     -H 'content-type: application/json' \
     -d '{"paymentNumber":"PMT-DEMO-3-1",
          "customerInvoiceHeaderId":"...",
          "amount":320,
          "paymentMethod":"bank_transfer"}'
   ```

`paymentMethod` must be one of `bank_transfer | cash | card | cheque` — the API rejects anything else.

**What the audience sees:** all three saga rows progress in lock-step in the saga console; the Sales Order 360 view at `/sales-orders/{id}` updates after every event.

---

## Demo 4 — Saga: failure paths

### 4.1 — Cancel an order mid-fulfilment

Place an order **as Sarah (sales_clerk)**, advance it to `manufacturing_in_progress` (e.g. follow Demo 3 partway: place order → wait for `stock_reserved` → wait for first `WorkOrderCreated`).

**Security demo moment.** With Sarah still logged in, hover over the **Cancel order** button on the sales-order detail page — it's disabled with a tooltip "Requires role: sales_manager". Sarah doesn't have authority to cancel; only sales-mgr does. Open the persona switcher → pick **sales-mgr** → re-login. Now the Cancel button is enabled. Click it, supply a reason, confirm. Behind the scenes Spring Security's `@PreAuthorize("hasRole('sales_manager')")` accepts the call.

```bash
# Equivalent curl (with Bearer token from sales-mgr's session):
curl -X POST http://localhost:8082/api/sales-orders/{salesOrderId}/cancel \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer <sales-mgr-token>' \
  -d '{"reason": "Customer changed mind"}'
```

A 403 from sarah's token vs 200 from sales-mgr's is the simplest live demo of the role gate.

Returns 200 with the order body now showing `status='cancelled'` and `cancelledAt` set. Behind the scenes:

1. **Sales** — header flipped to `'cancelled'`, saga flipped to `'compensating'`, `sales.SalesOrderCancellationRequested` emitted.
2. **Inventory** consumes — releases the stock reservation (`stock_balance.reserved_quantity` decremented; reservation header status `'released'`), emits `inventory.SalesOrderCancellationApplied`.
3. **Manufacturing** consumes — for every active WO (status NOT IN `completed/closed/cancelled`): `WorkOrder.cancel()` flips it to `'cancelled'` + emits `manufacturing.WorkOrderCancelled`. The associated `make_to_order_saga` is flipped to `'compensated'`. One `manufacturing.SalesOrderCancellationApplied` ack per cancel command.
4. **Inventory** also consumes `manufacturing.WorkOrderCancelled` to release the raw-material reservation (`stock_balance.reserved_quantity` decremented for the rm components).
5. **Sales** consumes both acks (one inbox handler each). When both have arrived, the saga advances `compensating → compensated` and emits `sales.SalesOrderCompensated`.
6. **Reporting** consumes `sales.SalesOrderCompensated` and flips `sales_order_360_view.order_status` to `'cancelled'`.

Watch the Saga Console UI walk `manufacturing_in_progress → compensating → compensated`. The 360 view (`/sales-orders/{id}/360`) shows `order_status='cancelled'` afterwards.

After `goods_shipped` the cancel is rejected with HTTP 409 — that path requires the credit-note / return-goods flow (out of scope, dev-todo §4.2). Hard-cancel by design: WIP from in-progress operations is written off rather than letting production finish (soft-cancel parked in dev-todo §3.7).

### 4.2 — Reservation comes back partial or failed

Pre-state: 0 × FG-TABLE-001 on hand. Place an order for 2 of them.

The `StockReservedHandler` stashes the shortage on saga.data and **still advances to `stock_reserved`**. The worker then emits `ManufacturingRequested` carrying the shortage map; manufacturing fills the gap by releasing work orders. End-state is the same as 3.1, just longer.

If manufacturing has **no active BOM** for the product, `ManufacturingDispatchedHandler` flips the saga to `stock_reservation_failed` and the order header to `rejected`. To force this branch in a demo, deactivate the BOM via `POST /api/boms/{id}/activate` with a different BOM, or use a finished-good seeded without a BOM.

---

## Demo 5 — Saga: make-to-order

The `manufacturing.make_to_order_saga` row appears the moment sales emits `ManufacturingRequested`. One saga row per work order (not per sales-order line — sub-assembly children get their own saga at `work_order_created`).

### 5.1 — Order with sufficient raw materials

The make-to-order state machine:

| State | Trigger |
|---|---|
| `started` | worker creates the work order, snapshots BOM + routing |
| `work_order_created` | worker emits `manufacturing.RawMaterialReservationRequested` |
| `raw_material_reservation_requested` | inbound `inventory.RawMaterialsReserved` |
| `raw_materials_reserved` | worker releases production |
| (worker drives ops to completion) | `POST /api/work-orders/{id}/operations/{seq}/complete` per op |
| `completed` | terminal — set in the same txn as the last operation lands |

Sub-assembly recursion: if a BOM line is `sub_assembly`, the release service recurses, creating a child WO with `parent_work_order_id` set, and pre-attaches a child saga at `work_order_created`. Parent holds at `in_progress` until all children finish; the operation service cascades up the parent chain in the same transaction.

### 5.2 — Raw material shortage

Pre-state: only 2 × RM-LEG-001 on hand; ordered quantity needs 4.

`RawMaterialsReservedHandler` sees status `partially_reserved` / `failed`, transitions saga to `raw_material_shortage`, stashes the per-product shortage on saga.data, and emits `manufacturing.RawMaterialShortageDetected`. Purchasing's `RawMaterialShortageDetectedHandler` consumes this and creates a `purchase_requisition` with `source_type='work_order_shortage'` (which auto-converts to a PO in the same transaction — see Demo 6). When goods arrive, `GoodsReceivedHandler` (manufacturing-side) decrements the stash; once fully covered, the saga returns to `work_order_created` and re-emits the reservation request.

---

## Demo 6 — Saga: purchase-to-pay

`purchasing.purchase_to_pay_saga` row is inserted at `started` in the same txn as the PO is created. **PO draft/approve flow (since 2026-05-06)**: manual PRs land their PO at `'draft'` and require a human to call `POST /api/purchase-orders/{id}/approve`; shortage-driven PRs (Demo 5.2) auto-approve when `northwood.purchasing.shortagePoAutoApprove=true` (default), so the make-to-order saga still flows automatically. Saga walks `started → purchase_order_approved → waiting_for_goods`. `three_way_match_*` saga states stay reserved for future variance-handling workflows.

### 6.1 — Manual requisition → approve → goods receipt → invoice → payment

1. Tom raises a requisition manually (UI not yet wired for PR creation; `/purchase-orders` is read-only):
   ```bash
   curl -X POST http://localhost:8085/api/purchase-requisitions \
     -H 'content-type: application/json' \
     -d '{"requisitionNumber":"PR-DEMO-6-1",
          "requestedBy":"tom",
          "lines":[{"productId":"00000000-0000-7000-8000-000000000003",
                    "productSku":"RM-LEG-001",
                    "productName":"Table Leg",
                    "requestedQuantity":40,
                    "requiredDate":"2026-06-01"}]}'
   ```
   The PR auto-converts to a **draft** PO inside the same txn. PO header status is `'draft'`. Saga state: `started` (parked, waiting for human approval).

2. Tom approves the PO (the response from step 1 carries the PO id; or list draft POs with `GET /api/purchase-orders/{id}` after capturing the id from logs):
   ```bash
   curl -X POST http://localhost:8085/api/purchase-orders/{poId}/approve \
     -H 'content-type: application/json' \
     -d '{"approver":"tom","reason":"Needed for Aug build"}'
   ```
   PO flips to `'sent'`, emits `purchasing.PurchaseOrderApproved`. Saga: `started → purchase_order_approved` (inline, in the approve txn). Next worker tick: `purchase_order_approved → waiting_for_goods`.

3. Tom posts a goods receipt (UI: `/goods-receipts`):
   ```bash
   curl -X POST http://localhost:8083/api/goods-receipts \
     -H 'content-type: application/json' \
     -d '{"goodsReceiptNumber":"GR-DEMO-6-1",
          "purchaseOrderHeaderId":"...",
          "warehouseCode":"MAIN",
          "lines":[{"purchaseOrderLineId":"...",
                    "productId":"00000000-0000-7000-8000-000000000003",
                    "productSku":"RM-LEG-001",
                    "productName":"Table Leg",
                    "receivedQuantity":40,
                    "unitCost":25}]}'
   ```
   Saga state: `waiting_for_goods` → `goods_received` (driven by `inventory.GoodsReceived` inbox).

4. Olivia records the supplier invoice (UI: `/supplier-invoices`):
   ```bash
   curl -X POST http://localhost:8086/api/supplier-invoices \
     -H 'content-type: application/json' \
     -d '{"internalInvoiceNumber":"INV-DEMO-6-1",
          "supplierInvoiceNumber":"PINEWOOD-9001",
          "purchaseOrderHeaderId":"...",
          "supplierId":"...",
          "supplierName":"Pinewood Supplies",
          "currencyCode":"AUD",
          "lines":[{"purchaseOrderLineId":"...",
                    "productId":"00000000-0000-7000-8000-000000000003",
                    "productSku":"RM-LEG-001",
                    "productName":"Table Leg",
                    "quantity":40,
                    "unitPrice":25}]}'
   ```
   Finance runs **quantity + price-variance 3-way match** (since 2026-05-06). Per-line invoice unit price is compared against the PO line's snapshotted unit_price; relative variance > `northwood.finance.match.priceTolerancePercent` (default 2.0%) parks the invoice at `three_way_match_failed`. On match, emits `finance.SupplierInvoiceApproved`. Purchasing saga: `goods_received` → `supplier_invoice_approved`.

   To force a price-variance failure for the demo, post the same invoice with `unitPrice: 30` against a PO line whose unit_price is 25 — the invoice lands in `three_way_match_failed`. List the queue at `GET /api/supplier-invoices/pending-review`; resolve via `POST /api/supplier-invoices/{id}/manual-approve` (with `{reviewer, reason}`) or `POST /{id}/reject`.

5. Olivia pays (UI: `/payments` → Supplier tab):
   ```bash
   curl -X POST http://localhost:8086/api/payments \
     -H 'content-type: application/json' \
     -d '{"paymentNumber":"PMT-DEMO-6-1",
          "supplierInvoiceHeaderId":"...",
          "amount":1000,
          "paymentMethod":"bank_transfer"}'
   ```
   On full settlement: `supplier_invoice_approved` → `completed`; PO header flips to `paid`. On partial: → `supplier_payment_made`, parks.

   Multi-invoice supplier payment (one cheque settles multiple approved invoices):
   ```bash
   curl -X POST http://localhost:8086/api/payments/multi -H 'content-type: application/json' -d '...'
   ```

GL postings (six pairs) fire alongside each step — see Demo 7's GL section.

---

## Demo 7 — End-to-end with all three sagas + GL posting

The showcase. One sales order touches every service. **UI: Scenario 7.1 runs the auto-driven parts and prompts at each human step.**

### 7.1 — Big order with raw-material shortage

Pre-state: 0 × FG-TABLE-001 on hand; 5 × RM-LEG-001 on hand; need 40 (4 × 10 tables); plenty of every other RM; one customer (Sydney Home Living); one supplier.

```bash
curl -X POST http://localhost:8082/api/sales-orders \
  -H 'content-type: application/json' \
  -d '{"orderNumber":"SO-DEMO-7-1",
       "customerCode":"CUST-001",
       "currencyCode":"AUD",
       "lines":[{"productId":"00000000-0000-7000-8000-000000000001",
                 "productSku":"FG-TABLE-001",
                 "productName":"Wooden Dining Table",
                 "orderedQuantity":10,
                 "unitPrice":320}]}'
```

**Watch this unfold:**

1. Sales fulfilment saga: `started` → `stock_reservation_requested` → `stock_reserved` (with shortage stash) → `manufacturing_requested`.
2. Make-to-order saga starts; advances to `raw_material_shortage` because RM-LEG-001 is short by 35 units.
3. `manufacturing.RawMaterialShortageDetected` fires → purchasing creates a PR + PO → P2P saga starts at `started` → `waiting_for_goods`.
4. **Time-skip:** Tom posts a goods receipt for 35 × RM-LEG-001 (Demo 6 step 2 with `receivedQuantity:35`).
5. Inventory bumps `stock_balance.on_hand_quantity`. P2P saga: `waiting_for_goods` → `goods_received`.
6. Manufacturing's `GoodsReceivedHandler` un-parks the make-to-order saga: → `work_order_created` → re-emits reservation → `raw_materials_reserved`.
7. Operation completion: 10 work orders × however many ops each. `manufacturing.WorkOrderManufacturingCompleted` × 10.
8. Sales fulfilment saga's tracker: `outstandingWorkOrderIds` empties as completions arrive; advances to `ready_to_ship` only when `expectedWorkOrderCount` (stamped at dispatch) matches.
9. Post a shipment for 10 units. Sales: `ready_to_ship` → `goods_shipped`. Inventory decrements on-hand and releases the reservation.
10. `sales.SalesOrderShipped` triggers finance to auto-create a customer invoice. Sales: `goods_shipped` → `invoice_created`.
11. Olivia records the supplier invoice and pays it (Demo 6 steps 3 & 4). P2P saga: → `supplier_invoice_approved` → `completed`.
12. Olivia receives the customer's payment for the customer invoice (Demo 3 final step). Sales saga: `invoice_created` → `completed`.

### What the audience sees on screens

- **Saga Console** (`/saga-console`): three columns drive in lock-step.
- **Sales Order 360 view** (`/sales-orders/{id}`): every status column updates in real time.
- **Purchase Order tracking** (`/purchase-orders/{id}`): received/invoiced/paid amounts accumulate.
- **Production Planning Board** (`/production-board`): each WO walks `released → in_progress → completed`.
- **Material Shortage** (`/material-shortages`): row appears at `open`, walks `purchase_requested → purchase_ordered → resolved`.
- **ATP** (`/atp/{productId}`): on-hand drops at shipment, incoming-from-purchase rises on PO creation, drops on receipt.
- **Financial Dashboard** (`/`): sales_revenue, COGS, gross_profit, cash_received/paid, plus open SO/PO/WO counts per day.

### GL postings to watch in `finance.journal_entry_header` / `_line`

Six balanced debit/credit pairs land in the same txn as their originating action:

| Action | Dr | Cr |
|---|---|---|
| Goods receipt | 1200 Inventory | 1300 GRNI (goods received not invoiced) |
| Supplier invoice approval | 1300 GRNI | 2100 AP |
| Supplier payment | 2100 AP | 1000 Bank |
| Shipment | 5000 COGS | 1200 Inventory |
| Customer invoice | 1100 AR | 4000 Revenue |
| Customer payment | 1000 Bank | 1100 AR |

Reverse a journal entry: `POST /api/journal-entries/{id}/reverse` (creates a debit/credit-flipped reversal in the same txn that flips the original from `posted` → `reversed`).

### Acceptance criteria for the demo

- Three sagas run concurrently; nothing gets stuck.
- Replaying any inbox event is a no-op (idempotency).
- Sum of debits = sum of credits in `finance.journal_entry_line` (enforced by the deferred trigger `enforce_journal_balance`).
- Stop and restart any service mid-flow: the polling outbox + inbox catch up; no events lost (transactional outbox guarantee).

---

## Demo 8 — Security: roles, audit, and persona-aware UI

3-4 minute walkthrough that fits inside Demo 7 once the order has placed and the saga is running. Tells the security story end-to-end: authentication, role-gated mutations, actor-stamped events, audit timeline.

### 8.1 — Login flow

Open `http://localhost:5174` cold (or click the logout button). The SPA redirects to Keycloak's login form (`http://localhost:8090/realms/northwood`). Type `sarah` / `sarah`. Land back on the SPA — top-right user chip shows "Sarah Sales · sales_clerk". The BFF holds the access token in a server-side session; no token ever reaches the browser.

### 8.2 — Role-gated mutation (the cancel-order moment)

On a sales-order detail page (any order before `goods_shipped`), hover the **Cancel order** button. Sarah's `sales_clerk` doesn't include `sales_manager`, so the button is disabled with tooltip "Requires role: sales_manager".

Open the **persona switcher** (top-right, next to the user chip) → **Sales Manager** → confirm. Browser logs out, lands on Keycloak with username pre-filled, type the password (== `sales-mgr`), continue. The Cancel button is now enabled. Click it. Saga walks `compensating → compensated`. Audit row stamps `actor_user_id = "sales-mgr"`.

If you want the loud version: switch to **Auditor** first, try to click anything mutating — every mutation button is disabled with a tooltip. Read-only persona; reading works.

### 8.3 — Audit log timeline

On any sales-order detail page, click **View audit** (top-right). Lands on `/system/audit-log?aggregateId=<id>`. The BFF fans out to all 7 services and merges the timeline by occurredAt desc. Every event is labelled with its source service, event type, and actor. The cancellation row shows `actor_user_id = "sales-mgr"`; the original placement row shows `actor_user_id = "sarah"`. Saga-driven rows (e.g., `manufacturing.WorkOrderCreated`) show actor as "system".

Same screen accessible directly via sidebar **System → Audit Log** with no filter — shows recent activity across the whole stack.

### 8.4 — finance reverses a journal

Switch to **Daniel** (finance_manager). Navigate to **Finance → Journal Entries**. Pick a posted entry. Click **Reverse**. New journal entry created with debits/credits flipped; original flips to `'reversed'`. Open the audit log for that journal — both rows have actor=`daniel`.

Try the same as Olivia (accountant) — Reverse button is disabled, tooltip "Requires role: finance_manager".

### 8.5 — Acceptance criteria for the security demo

- Login redirect works on cold-cache load.
- Sarah cancel-order → 403 + disabled button + tooltip.
- sales-mgr cancel-order → 200 + saga compensates.
- Auditor can read every screen but mutates nothing.
- `actor_user_id` is non-null on all user-driven outbox rows; null on saga/system rows.
- `/system/audit-log` shows a coherent cross-service timeline within a few seconds of the action.

---

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| Dashboard says "Couldn't reach reporting-service on :8087" | reporting-service or the BFF isn't running. Start them. |
| Saga console shows "bff offline" | The BFF (port 8080) isn't running. Start `mvn -pl demo-web-ui-bff spring-boot:run`. |
| Saga console column empty even with BFF up | The owning saga service isn't running. The aggregator gracefully degrades and logs the upstream failure once per pump cycle at DEBUG. |
| Event drawer is empty | Phase 1 stub kicks in after ~2 s — refresh if it stays blank. Real `/events` aggregator is in `dev-todo.md`. |
| Place order returns 400 with `Customer not found` | `CUST-001` lives in the **seed** file, so the stack came up without the seed override. Re-up with `docker compose -f docker-compose.yml -f docker-compose.seed.yml up -d`; `docker compose logs postgres` should then show `02-northwood_erp_seed.sql ... DONE`. |
| Goods receipt 400s | The PO id in the form must be a real PO header UUID. Either get it from the **PO tracking** view, or use the scenario runner which captures it for you. |
| Goods receipt 400 with `Unknown purchase_order_line_id` | The receipt line names a `purchaseOrderLineId` that inventory hasn't projected yet — usually because the PO hasn't been created at all, or `PurchaseOrderCreated` hasn't reached inventory's inbox yet (cold start). Use the picker in `/goods-receipts` rather than hand-typed line ids. |
| Goods receipt 400 with `Product mismatch on purchase_order_line_id` | The receipt line's `productId` doesn't match the `purchase_order_line.product_id`. Defence-in-depth check against client-side picker bypass. Re-pick the line from the picker. |
| Shipment 400 with `Unknown sales_order_line_id` / `Product mismatch on sales_order_line_id` | Same shape on the SO side. Inventory projects `sales_order_line_facts` from `SalesOrderPlaced`; the validation runs in `ShipmentService.post`. Lines without a `salesOrderLineId` (unlinked manual shipments) skip the check. |
| Make-to-order saga stuck on `started` (and `manufacturing.work_order_header` stays empty) | Manufacturing-service didn't receive the `sales.ManufacturingRequested` event. Either manufacturing-service isn't running, **or it was launched without `SPRING_PROFILES_ACTIVE=kafka`** so it's on the in-JVM bus and never sees cross-JVM events. Set the profile and restart. |
| Sales saga stuck on `manufacturing_requested` | Same as above — make-to-order saga can't advance because manufacturing isn't getting the event. Check the kafka profile is set on every service. |
| Service starts but logs `FIND_COORDINATOR` errors / consumer group never joins | Stale Kafka volume from before the single-broker replication-factor overrides were added. `docker compose down -v ; docker compose -f docker-compose.yml -f docker-compose.seed.yml up -d`. The volume gets wiped, internal topics (`__consumer_offsets`, `__transaction_state`) get recreated with replication-factor=1. |
| `kafka-console-consumer.sh --from-beginning` returns 0 messages even though publishes succeeded | Same root cause as above — the spawned consumer-group can't find its coordinator. Probe with `kafka-get-offsets.sh --topic <name>` first to confirm messages are actually there. |
| Customer payment 400 with CHECK constraint error | Out-of-date Postgres volume from before the `2026-05-05-extend-fulfilment-saga-states.sql` migration. Either let Liquibase apply it on next sales-service boot, or wipe the volume: `docker compose down -v ; docker compose -f docker-compose.yml -f docker-compose.seed.yml up -d`. |
| Manufacturing-service fails to boot with `Unexpected formatting in formatted changelog ... line N` | A `--` comment line accidentally starts with a Liquibase keyword (`changeset`, `rollback`, etc.). See CLAUDE.md § Liquibase gotchas. |

To reset and start fresh: `docker compose down -v ; docker compose -f docker-compose.yml -f docker-compose.seed.yml up -d` then re-bring up the services (with `SPRING_PROFILES_ACTIVE=kafka` per terminal) + BFF + SPA.

---

## Tearing down

```powershell
# stop the SPA + BFF + each service: Ctrl+C in each terminal
docker compose down                 # stop postgres but keep data
docker compose down -v              # also wipe the volume (full reset)
```

---

## Tests

```powershell
mvn test                                       # all 308 backend unit tests
mvn -pl inventory-service verify               # Testcontainers IT (~50s)
cd demo-web-ui ; npm.cmd run typecheck ; cd .. # technical demo SPA typecheck
cd demo-web-ui ; npm.cmd run build ; cd ..     # technical demo SPA production bundle
cd erp-web-ui ; npm.cmd run typecheck ; cd ..  # operational ERP SPA typecheck
cd erp-web-ui ; npm.cmd run build ; cd ..      # operational ERP SPA production bundle
```

---

## What's deliberately out of scope today

These are tracked in `dev-todo.md` (where relevant):

- Multi-currency GL consolidation (low priority)
- Customer credit notes / refunds (low priority)
- GST tax-account split (low priority)
- `wip_value` on the financial dashboard (gated on a costing decision for `wip_balance.average_cost`)
- Reorder-alert job that would honour the `discontinued_at` flag (today the flag is stamped but no auto-suggester reads it)
- Real `/events` aggregator (today's bottom event drawer is a Phase 1 stub)

---

## Where to next

- `dev-todo.md` — implementation backlog (slice-level).
- `CLAUDE.md` — architecture invariants (read first if you're modifying the backend).
