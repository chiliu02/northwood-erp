# Northwood ERP — Demo Script

Step-by-step for getting the full stack — Postgres, the seven Spring Boot services, the ERP BFF, and the operational ERP SPA — up and ready to demo, plus the per-demo runbook.

The end-state: open `http://localhost:5174`, sign in through Keycloak as a persona (e.g. **sarah** / **sarah**), place a sales order, and watch it drive to completion across the **Sales Order detail** page and the **Inventory / Finance / Reporting** screens while saying "this is what we built" out loud.

Every event name, saga state, endpoint, and request body below is verified against the codebase as of 2026-06-02. Companion docs: `CLAUDE.md` (architecture invariants), `dev-todo.md` (implementation backlog).

---

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| Java | 21 | `java -version` |
| Maven | 3.9+ | `mvn -v` |
| Docker | recent | `docker --version` |
| Node.js | 20+ | `node --version` (Node 25 confirmed; Node 18 likely fine) |
| npm | 10+ | bundled with Node |
| Free ports | 5432, 5174, 8081–8087, 8089, 8090 | nothing else listening |

**Windows**: PowerShell is the assumed shell. If `npm` fails with `running scripts is disabled on this system`, use `npm.cmd` instead (the Vite shell scripts inside `node_modules/.bin` are unaffected).

---

## One-time setup

```powershell
# from repo root — Postgres 17 + Kafka 4.1.2 (KRaft) + Keycloak 26.
# The -f docker-compose.seed.yml override pre-loads the demo fixtures (products, customers,
# BOMs, GL chart, …) this script relies on; the base file alone comes up with an empty schema.
docker compose -f docker-compose.yml -f docker-compose.seed.yml up -d
mvn install -DskipTests                             # builds all 11 Maven modules (incl. erp-web-ui-bff)
cd erp-web-ui ; npm.cmd install ; cd ..             # one-time install for the operational ERP SPA
```

The repo is **11 Maven modules + the `erp-web-ui` SPA** (shared-kernel, shared, the seven services, and `erp-web-ui-bff`).

Postgres runs its init scripts once, on the first boot of a fresh data volume. The base `docker-compose.yml` mounts only the **schema baseline** (`db/northwood_erp.sql` — per-service schemas, roles, grants, the saga CHECK constraint already extended for `invoice_partially_paid`); the `docker-compose.seed.yml` override additionally mounts the **demo fixtures** (`db/northwood_erp_seed.sql` — products, customers, suppliers, BOMs, GL chart, …). This walkthrough needs the fixtures (CUST-001, the SKUs, etc.), which is why the command above layers both files with `-f`. For an empty database you populate via events from zero, drop the override and run `docker compose up -d`. To start fresh later, repeat the seeded command after a wipe: `docker compose down -v ; docker compose -f docker-compose.yml -f docker-compose.seed.yml up -d`.

Keycloak loads the `northwood` realm from `db/keycloak/northwood-realm.json` on first boot — 13 roles + 13 demo users (one per role, `username == password`). Admin console at `http://localhost:8090/` (`admin` / `admin`). The ERP SPA authenticates real users via OIDC code flow against this realm — there is no shared-secret bypass.

### The 13 demo personas (§1 Security)

| Username | Role | Persona |
|---|---|---|
| emma | catalog_manager | Product master CRUD, pricing, reorder policy |
| sarah | sales_clerk | Place orders, view sales orders, view ATP |
| sam | sales_manager + sales_clerk | All clerk + cancel orders |
| mike | warehouse_clerk | Stock movements, goods receipts, shipments |
| wendy | warehouse_manager + warehouse_clerk | All clerk + force-release reservations |
| linda | production_planner | View board, set priority, complete operations, edit/activate BOM |
| paul | production_supervisor + production_planner | All planner + cancel WO, skip operation |
| tom | purchasing_clerk | Create PR/PO (draft), post goods receipts |
| priya | purchasing_manager + purchasing_clerk | All clerk + approve PO, author supplier prices |
| olivia | accountant | Record supplier invoices, record payments |
| daniel | finance_manager + accountant | All accountant + reverse journals, manual-approve invoices |
| aaron | auditor | Read-only on every endpoint |
| ian | sysadmin | Keycloak admin only; no business data |

**Switching personas** in the ERP Web UI: there is no in-app persona dropdown — the user chip (top-right) shows the signed-in name + role and carries a **Sign out** action. To switch persona mid-demo, sign out, then sign in on Keycloak's login form as the desired persona (password == username). The 403-tooltip behaviour on action buttons reacts to the new role automatically once you land back in the SPA.

### Why Kafka is required

The seven services run as separate JVMs in this layout. The only wired event bus is `KafkaEventPublisher` + `KafkaInboxDispatcher`, both registered under `@Profile("kafka")` (along with each service's outbox drainer). Under the default `dev` profile no `EventPublisher` is registered — events accumulate in the outbox table with nothing draining them, so cross-service flows (e.g. inventory emitting `ReplenishmentRequested` for manufacturing or purchasing to consume) silently never fire.

Every demo below assumes services are launched with `SPRING_PROFILES_ACTIVE=kafka` (set in each terminal before `mvn spring-boot:run`). The Kafka container in `docker-compose.yml` is pre-configured for the single-broker case (`KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1` and friends — without those, internal topics fail to create and consumer groups silently hang on `FIND_COORDINATOR`).

---

## Bringing the stack up

You need **nine terminals** for a full demo: seven services + the ERP BFF + the SPA. IntelliJ run configurations (or `tmux split-windows`) help; the simplest path is nine PowerShell windows.

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
| 8 | **erp-web-ui-bff**    | `mvn -pl erp-web-ui-bff spring-boot:run`        | (none)  | 8089 | `Started ErpBffApplication` |
| 9 | erp-web-ui            | `cd erp-web-ui ; npm.cmd run dev`               | (none)  | 5174 | `Local: http://localhost:5174/` |

The **ERP BFF** sits in front of the seven services and gives the SPA a single origin (the fetch chain is `5174 → 8089 → backend service → postgres`). It also runs the OIDC code flow against Keycloak and holds the access token in a server-side session — no token reaches the browser. Without the BFF every `/api/*` call fails with `ECONNREFUSED` in the Vite proxy log. Start it after at least one read-side service (reporting is the minimum for most list pages) is up — it gracefully degrades to whichever services are reachable.

Each service takes ~10–15 s to boot. Bringing them all up takes ~2 minutes; you can start them in parallel. Kafka itself takes ~30 s after `docker compose up` to pass its healthcheck — wait for that before launching the services or the first publish will fail.

**Smoke check**: visit `http://localhost:5174` — you should be redirected to the Keycloak login form. Sign in as `sarah` / `sarah` and land on the **Home** dashboard. The **Sales Orders** list (`/sales-orders`) should render rows from reporting's projection if the seed override is loaded.

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

## Two ways to drive the demo

You can pick one or mix. The audience-facing experience is best with **A. ERP UI walkthrough**; the wire-level engineering deep-dive is **B. curl + Swagger**.

### A. ERP UI walkthrough (recommended for live audiences)

Drive the demo step by step through the operational SPA, signed in as the appropriate persona. The module-grouped sidebar (Master Data / Sales / Purchasing / Inventory / Manufacturing / Finance / Reporting / System) mirrors how a real operator thinks — "I'm working in Sales" rather than "I am persona X". Suggested run-order for the happy path:

1. **Emma** (catalog_manager) — **Master Data → Products** → open Wooden Dining Table → change the sales price / standard cost / reorder policy via the detail page actions.
2. **Sarah** (sales_clerk) — **Sales → Sales Orders → New** → place 1 × FG-TABLE-001 for CUST-001 → submit → land on the **Sales Order detail** page (`/sales-orders/:id`). With 2 on hand the line reserves fully, so the sales saga goes straight to `ready_to_ship` — no work order is raised (a stock-covered order skips manufacturing entirely since §2.37).
3. **Linda** (production_planner) — *(only fires when the order is short)* — to see a work order, place a quantity above on-hand so inventory raises a make-to-stock replenishment WO. Then **Manufacturing → Production Board** → open the released WO → **Complete operation** for each op (sequence 10, 20, 30…). Each completion advances the work-order (make-to-stock) saga; when the WO completes it bumps on-hand and the parked sales order re-reserves and reaches `ready_to_ship`.
4. **Mike** (warehouse_clerk) — **Inventory → Shipments → New** → pick the ready-to-ship order → post the shipment.
5. Watch finance auto-create a customer invoice (the sales saga jumps to `invoice_created`); the **Sales Order detail** roll-up shows the new invoice.
6. **Olivia** (accountant) — **Finance → Payments → New** → Customer (AR) → pick the customer invoice → record full payment. Sales saga reaches `completed`.

The **Sales Order detail** page is the headline screen: it carries the cross-context fulfilment roll-up (reservation / shipment / invoice / payment status pulled from reporting's `sales_order_360_view` projection), updating after each event. Open it on a side screen and refresh as the saga advances.

### B. curl + Swagger (engineering deep dive)

The `curl` bodies in §Demo 1–7 below are the canonical wire-level walkthroughs. Useful when you need to show the actual JSON, tail Postgres in parallel, or hit endpoints the UI doesn't surface (e.g. `POST /api/journal-entries/{id}/reverse`). Note the SPA's `/api/*` calls go through the BFF on 8089 (which attaches the session's bearer token); the raw service ports below are unauthenticated only if the resource server is run without security — for the UI path the BFF supplies the JWT.

Each service exposes Swagger UI at `http://localhost:808X/swagger-ui/index.html`.

---

## What to point at while the audience watches

| Page | Sidebar path / URL | What's interesting |
|---|---|---|
| **Sales Order detail** | Sales → Sales Orders → (row) · `/sales-orders/:id` | Cross-context fulfilment roll-up filling in as events arrive — the **headline view**. |
| **Sales Orders list** | Sales → Sales Orders · `/sales-orders` | Live status column per order. |
| **PO Tracking** | Reporting → PO Tracking · `/purchase-orders/tracking` | Money-flow bars (ordered / received / invoiced / paid) accumulate. |
| **Production Board** | Manufacturing → Production Board · `/production-board` | Per-WO progress; shortage callouts. |
| **Available-to-Promise** | Reporting → Available-to-Promise · `/atp` | On-hand minus reservations + incoming. |
| **Material Shortage** | Reporting → Material Shortage · `/material-shortages` | 4-stage walk: open → purchase_requested → purchase_ordered → resolved. |
| **Financial Dashboard** | Reporting → Financial Dashboard · `/financial-dashboard` | KPIs accumulate: revenue, COGS, gross %, open SO/PO/WO counts. |
| **Journal Entries** | Finance → Journal Entries · `/journal-entries` | Balanced debit/credit pairs landing per action; reverse from here. |
| **Audit Log** | System → Audit Log · `/system/audit-log` | Cross-service, actor-stamped timeline. |

### Watching the database directly (for the wire-level deep dive)

Three useful psql queries to project on a side screen:

```sql
-- Sales fulfilment saga (one row per order)
SELECT sales_order_header_id, saga_state, current_step, data
  FROM sales.sales_order_fulfilment_saga ORDER BY updated_at DESC;

-- Make-to-stock work-order saga (one row per work order; table still named work_order_saga, rename tracked as §2.39)
SELECT work_order_id, saga_state, current_step
  FROM manufacturing.work_order_saga ORDER BY updated_at DESC;

-- Purchase-to-pay saga (one row per PO)
SELECT purchase_order_header_id, saga_state, current_step
  FROM purchasing.purchase_to_pay_saga ORDER BY updated_at DESC;
```

```bash
docker exec -it northwood-postgres psql -U postgres -d northwood_erp
```

---

## Demo 1 — CQRS: product master as the upstream Open Host

The product service owns SKUs and pricing. Reorder policy is owned upstream too (Material Master / Shape A): inventory's `reorder_point` / `reorder_quantity` are projected from `product.ReorderPolicyChanged`.

**UI alternative for Demo 1 (as Emma):** all four mutating commands have inline actions on the **Master Data → Products** detail page.

### 1.1 — Register a new product

```bash
curl -X POST http://localhost:8081/api/products \
  -H 'content-type: application/json' \
  -d '{"sku":"FG-CHAIR-001","name":"Dining Chair","productType":"finished_good",
       "baseUomId":"00000000-0000-7000-8000-000000000010",
       "salesPrice":120,"standardCost":45,"currencyCode":"AUD"}'
```

**Outbox:** `product.ProductCreated`. **Today's projections (§1F.2 / §1F.6a / §1F.6b):** five services each seed a stub row from the event — `inventory.product_card` (§2.38 — descriptive columns + reorder + type-derived make-vs-buy default), `sales.product_card` (NULL price + currency until `SalesPriceChanged`), `manufacturing.product_card` (type-derived make-vs-buy default), `finance.product_card` (NULL `standard_cost` + `valuation_class` until the respective change event), `reporting.available_to_promise_view`. Purchasing has no product read model of its own.

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

**Outbox:** `product.ReorderPolicyChanged`. **Projection:** `inventory.product_card` updates within one outbox poll. The integration test `ReorderPolicyChangedSeamIT` exercises this end-to-end. The endpoint rejects on a `discontinued` product.

### 1.4 — Discontinue

```bash
curl -X POST http://localhost:8081/api/products/{id}/discontinue
```

**Outbox:** `product.ProductDiscontinued`. **Today's behaviour (§1F.1):** six services consume the event. Sales `placeOrder` rejects new lines for the SKU with `ProductDiscontinuedException`. Manufacturing flips its `product_card` make-vs-buy flags off and retires the active BOM. Purchasing's PR entry-point rejects requisitions for the SKU. Inventory + finance + reporting/atp stamp their own `discontinued_at`. Existing draft sales orders are **not** retroactively flagged; "reorder rules stop generating purchase suggestions" remains future-tense (no auto-reorder job exists today for the flag to suppress).

### 1.5 — Make-vs-buy classification

```bash
curl -X PUT http://localhost:8081/api/products/{id}/make-vs-buy \
  -H 'content-type: application/json' \
  -d '{"manufacturedInternally":true,"purchasedExternally":false}'
```

**Outbox:** `product.MakeVsBuyChanged`. **Projection:** inventory mirrors the `is_manufactured` / `is_purchased` flags onto its `product_card`; since §2.37 inventory's replenishment routing reads them to decide make-vs-buy (manufactured → make-to-stock WO, purchased → purchasing). Manufacturing also keeps its own make-vs-buy projection so it can reject a replenishment for a SKU with no active BOM (`ReplenishmentUndispatchable`).

---

## Demo 2 — CQRS: cross-context read models in reporting

Reporting is inbox-only — no command APIs, no outbox. Six projections are wired today, surfaced through the **Reporting** module plus the **Sales Order detail** roll-up.

| Read model | Endpoint | UI surface | Driven by |
|---|---|---|---|
| `sales_order_360_view` | `GET /api/sales-orders/{id}/360` | Sales Order detail (`/sales-orders/:id`) | sales + manufacturing + inventory + finance events |
| `purchase_order_tracking_view` | `GET /api/purchase-orders/{id}/tracking` | Reporting → PO Tracking (`/purchase-orders/tracking`) | purchasing + inventory + finance events |
| `production_planning_board` | `GET /api/work-orders/{id}/board` | Manufacturing → Production Board (`/production-board`) | manufacturing + inventory events |
| `material_shortage_view` | `GET /api/material-shortages` (list) `/{productId}` | Reporting → Material Shortage (`/material-shortages`) | manufacturing + purchasing + inventory events |
| `available_to_promise_view` | `GET /api/atp` (list) `/{productId}` | Reporting → Available-to-Promise (`/atp`) | inventory + manufacturing + purchasing events |
| `financial_dashboard_daily` | `GET /api/financial-dashboard` (list, AUD default) `/{date}` | Reporting → Financial Dashboard (`/financial-dashboard`) | finance + sales + purchasing + manufacturing events |

The financial dashboard's `inventory_value`, `accounts_receivable`, and `accounts_payable` are populated via `GET /api/financial-dashboard/snapshot` (reporting projects `product_card` from `StandardCostChanged` and JOINs ATP × cost; AR/AP are SUM-windows over the SO360 + PO tracking projections). Only `wip_value` is still 0 — gated on a costing decision (LIFO / FIFO / weighted-avg) for `wip_balance.average_cost`.

> The Sales Order detail page reads the `sales_order_360_view` projection (the standalone "360 View" page was removed; the per-order roll-up now lives on the order's own detail screen).

---

## Demo 3 — Saga: sales-order fulfilment (happy path)

**Important framing (since §2.37):** a sales order ships straight from stock whenever the line is fully reservable. Manufacturing is involved only when a line is **short** — and even then it's indirect: inventory raises a make-to-stock replenishment and the sales order parks until on-hand is topped up. The happy path below has enough stock on hand, so it never touches manufacturing.

### 3.1 — Place an order; watch the saga drive it

Pre-state: 2 × FG-TABLE-001 on hand, 20 × RM-LEG-001 on hand (need 4 per table = 4 ok).

UI path (as Sarah): **Sales → Sales Orders → New**, pick CUST-001, add 1 × FG-TABLE-001, submit. Equivalent curl:

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
| `stock_reservation_requested` | inbound `inventory.StockReserved` — fully reserved → `ready_to_ship`; short → `stock_reservation_incomplete` |
| `stock_reservation_incomplete` | parked; inbound `inventory.ReplenishmentFulfilled` for all short lines → retry via `stock_reservation_requested`. Inbound `inventory.ReplenishmentCancelled` → `rejected` (terminal) |
| `ready_to_ship` | post a shipment via `POST /api/shipments` (inventory) |
| `goods_shipped` | inbound `finance.CustomerInvoiceCreated` (auto-created from `sales.SalesOrderShipped`) |
| `invoice_created` | inbound `finance.CustomerPaymentReceived` (full settlement) |
| `completed` | terminal |

Partial customer payment lands on `invoice_partially_paid` instead, parking until the next allocation. (The §2.37 flip removed the old `manufacturing_requested` / `manufacturing_in_progress` / `manufacturing_completed` / `purchasing_requested` states — sales no longer drives manufacturing; inventory owns make-vs-buy and the sales order simply parks at `stock_reservation_incomplete` until replenished.)

**To drive the saga to completion:**

1. Wait for the sales saga to reach `ready_to_ship` (auto, single tick — the line is stock-covered, so it reserves fully and never touches manufacturing). For the shortage variant, see Demo 5 / Demo 7.
2. Post a shipment. UI (as Mike): **Inventory → Shipments → New**, pick the ready order, confirm. Equivalent curl:
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
3. Customer invoice is auto-created from the shipment event. Pay it. UI (as Olivia): **Finance → Payments → New → Customer**, pick the invoice, record. Equivalent curl:
   ```bash
   curl -X POST http://localhost:8086/api/payments/customer \
     -H 'content-type: application/json' \
     -d '{"paymentNumber":"PMT-DEMO-3-1",
          "customerInvoiceHeaderId":"...",
          "amount":320,
          "paymentMethod":"bank_transfer"}'
   ```

`paymentMethod` must be one of `bank_transfer | cash | card | cheque` — the API rejects anything else.

**What the audience sees:** the **Sales Order detail** roll-up (`/sales-orders/:id`) progresses through reservation → shipment → invoice → payment after every event; the stock-covered happy path runs only the sales saga — no work order or PO appears.

### 3.2 — Prepayment / cash-with-order (the second AR pattern) ✅ §2.31

The same saga, the same outbox/inbox plumbing, the same `sales_order_fulfilment_saga` table — but the order's `payment_terms` flips a single branch at `started`. Money moves *before* goods. The point of the demo is to show the architecture isn't hardcoded to one trigger order: a per-order flag (snapshotted from the customer at placement, overridable per order) branches the saga and the GL chain rearranges itself accordingly.

Pre-state: 1 × FG-TABLE-001 on hand (a stock-only path keeps the demo crisp — no manufacturing detour). Pick any customer; the order's `paymentTerms` override is what matters.

```bash
curl -X POST http://localhost:8082/api/sales-orders \
  -H 'content-type: application/json' \
  -d '{"orderNumber":"SO-DEMO-3-2",
       "customerCode":"CUST-001",
       "currencyCode":"AUD",
       "paymentTerms":"prepayment",
       "lines":[{"productId":"00000000-0000-7000-8000-000000000001",
                 "productSku":"FG-TABLE-001",
                 "productName":"Wooden Dining Table",
                 "orderedQuantity":1,
                 "unitPrice":320}]}'
```

The order lands on the Sales Order detail page with an **"awaiting prepayment"** lozenge — saga is `awaiting_prepayment_invoice`. Within a tick finance creates a prepayment invoice (`invoice_type='prepayment'`) — **no GL post at creation** (Treatment A). Try posting a shipment now and the inventory service rejects with **HTTP 409 `UNPAID_PREPAYMENT_ORDER`** — the gate reads `inventory.sales_order_line_facts.prepayment_settled` (which is `false`), not sales' saga.

Pay the prepayment invoice (UI: **Finance → Payments → New → Customer**, or curl below). The customer payment must come from the same customer; full amount required for the saga to leave `invoice_created`:

```bash
curl -X POST http://localhost:8086/api/payments/customer \
  -H 'content-type: application/json' \
  -d '{"paymentNumber":"PMT-DEMO-3-2",
       "customerInvoiceHeaderId":"...",
       "amount":320,
       "paymentMethod":"bank_transfer"}'
```

What happens next, in one tick:
1. Finance posts **Dr 1000 Bank / Cr 2110 Customer Deposits** for $320 — the deposit is a liability until the goods land with the customer.
2. Sales saga: `invoice_created → prepaid` and emits `sales.SalesOrderPrepaymentSettled`.
3. Inventory consumes the settled event and flips `sales_order_line_facts.prepayment_settled = true` — the 409 gate is now unlocked.
4. Sales saga worker picks the row up from `prepaid` and emits `sales.StockReservationRequested`; the rest of the fulfilment flow walks the same path as Demo 3.1.

Post the shipment when the saga reaches `ready_to_ship` (single-line stock-cover order: it reserves fully and never touches manufacturing). Finance reacts to `inventory.ShipmentPosted` and posts **two** journal entries in the same tx:
- **Dr 2110 Customer Deposits / Cr 4000 Sales Revenue** for $320 — reclassify the deposit to revenue (the goods-delivered performance obligation).
- **Dr 5000 COGS / Cr 1220 Finished Goods Inventory** at standard cost (the existing on-shipment pair).

The saga walks `ready_to_ship → goods_shipped → completed` in one transaction (no invoice / payment events left to wait for).

**The point of the GL trail.** End-state ledger ties out identically to Demo 3.1's on-shipment flow:

| Account | Demo 3.1 (on_shipment) | Demo 3.2 (prepayment) |
|---|---|---|
| 1000 Bank | Dr $320 | Dr $320 |
| 1100 Accounts Receivable | Dr $320 → Cr $320 (net 0) | (untouched) |
| 2110 Customer Deposits | (untouched) | Cr $320 → Dr $320 (net 0) |
| 4000 Sales Revenue | Cr $320 | Cr $320 |
| 5000 COGS | Dr $150 | Dr $150 |
| 1220 Finished Goods Inventory | Cr $150 | Cr $150 |

What differs is the **path** through the chart of accounts — AR for credit terms, Customer Deposits for prepayment — and the **timing** of revenue recognition (at invoice creation vs at shipment). The audience point: the saga absorbs the two patterns without the rest of the system (inventory, manufacturing, reporting) caring which path was taken.

**What the audience sees:** the Sales Order detail lozenge transitions from "awaiting prepayment" → (paid; lozenge clears) → fulfilment progresses → completed. **Finance → Journal Entries** shows the deposit→revenue reclassification at shipment with a single click into the journal lines.

**Try the 409 gate:**
```bash
# attempt shipment before payment lands → HTTP 409 UNPAID_PREPAYMENT_ORDER
curl -i -X POST http://localhost:8083/api/shipments \
  -H 'content-type: application/json' \
  -d '{"shipmentNumber":"SH-PREMATURE","salesOrderHeaderId":"...","warehouseCode":"MAIN","lines":[...]}'
```

---

## Demo 4 — Saga: failure paths

### 4.1 — Cancel an order mid-fulfilment

Place an order **as Sarah (sales_clerk)** for a short quantity so it parks at `stock_reservation_incomplete` (e.g. follow Demo 3 partway: place order → wait for `stock_reservation_incomplete` while inventory's make-to-stock replenishment is still in flight). Cancel works from any pre-shipment state.

**Security demo moment.** With Sarah still signed in, open the Sales Order detail page and hover the **Cancel order** action — it's disabled with a tooltip "Requires role: sales_manager". Sarah doesn't have authority to cancel; only sam does. Sign out (top-right), then sign back in as **sam**. Now the Cancel action is enabled. Click it, supply a reason, confirm. Behind the scenes Spring Security's `@PreAuthorize("hasRole('sales_manager')")` accepts the call.

```bash
# Equivalent curl (with Bearer token from sam's session):
curl -X POST http://localhost:8082/api/sales-orders/{salesOrderId}/cancel \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer <sam-token>' \
  -d '{"reason": "Customer changed mind"}'
```

A 403 from sarah's token vs 200 from sam's is the simplest live demo of the role gate.

Returns 200 with the order body now showing `status='cancelled'` and `cancelledAt` set. Behind the scenes:

1. **Sales** — header flipped to `'cancelled'`, saga flipped to `'compensating'`, `sales.SalesOrderCancellationRequested` emitted.
2. **Inventory** consumes — releases the stock reservation (`stock_balance.reserved_quantity` decremented; reservation header status `'released'`), emits `inventory.SalesOrderCancellationApplied`.
3. **Sales** consumes that ack. While in `compensating`, the saga advances `compensating → compensated` and emits `sales.SalesOrderCompensated`. (**§2.40:** inventory is the sole compensation ack — post-§2.37 no work order is bound to a sales order, so the manufacturing sales-cancel leg, `WorkOrder.cancel`, and `manufacturing.WorkOrderCancelled` were deleted.)
4. **Reporting** consumes `sales.SalesOrderCompensated` and flips `sales_order_360_view.order_status` to `'cancelled'`.

Watch the Sales Order detail roll-up walk `stock_reservation_incomplete → compensating → compensated`; afterwards it shows `order_status='cancelled'`.

After `goods_shipped` the cancel is rejected with HTTP 409 — that path requires the credit-note / return-goods flow (out of scope, dev-todo §4.2). Hard-cancel by design: WIP from in-progress operations is written off rather than letting production finish (soft-cancel parked in dev-todo §3.7).

### 4.1.1 — Cancel a **paid** deposit order → automatic refund (§2.34)

The §4.1 cancel above was an *unpaid* order — nothing had moved through the GL, so the cancel just releases the reservation. Now cancel an order where the customer has already paid up front, and finance unwinds the money too.

Run Demo 3 (deposit/prepayment) partway: place a **deposit** order (e.g. `paymentTerms:"deposit"`, `depositPercent:50`), then pay the deposit invoice. The order now sits at saga `deposit_paid` with **Cr 2110 Customer Deposits** for the deposit amount — the customer's money is on the balance sheet.

Cancel it (as sam, before shipment). On top of the §4.1 sales↔inventory compensation, **finance** consumes `sales.SalesOrderCancellationRequested` and — seeing a paid prepayment/deposit invoice — posts the refund:

- **Dr 2110 Customer Deposits / Cr 1000 Bank** for the paid amount (the exact inverse of the original receipt), and stamps `customer_invoice_header.refunded_at` (idempotent — a redelivered cancel can't refund twice).

**What the audience sees:** the Sales Order detail page shows a green **"refunded"** lozenge once the order is cancelled, with a Refund section linking to the journal. Open **Finance → Journal Entries** and find the `customer_refund` posting; 2110 Customer Deposits nets to **zero** across the deposit receipt + the refund. On-shipment and COD orders have nothing in 2110 before shipment, so cancelling them refunds nothing (finance no-ops).

### 4.2 — Reservation comes back partial or failed

Pre-state: 0 × FG-TABLE-001 on hand. Place an order for 2 of them.

The `StockReservedHandler` stashes the short line-ids on saga.data and **advances to `stock_reservation_incomplete`** (parked). Inventory — in the same transaction as the partial reservation — raises a `ReplenishmentRequest(reason=sales_order_shortage)` and, because FG-TABLE-001 is makeable, routes it to manufacturing as a make-to-stock WO. When that WO completes it bumps FG on-hand and emits `inventory.ReplenishmentFulfilled`; the parked sales order retries its reservation and reaches `ready_to_ship`. End-state is the same as 3.1, just longer.

If the SKU has **no active BOM**, manufacturing emits `manufacturing.ReplenishmentUndispatchable`; inventory cancels the request and emits `inventory.ReplenishmentCancelled`, which flips the sales saga and the order header to `rejected`. (A purchased-only SKU with no vendor takes the same shape via `purchasing.ReplenishmentUndispatchable`.) To force the no-BOM branch in a demo, deactivate the BOM via the **Manufacturing → BOMs** page (or `POST /api/boms/{id}/activate` with a different BOM), or use a finished-good seeded without a BOM.

---

## Demo 5 — Saga: make-to-stock work order

The `manufacturing.work_order_saga` row (renamed from `make_to_order_saga` in §2.39) appears the moment inventory dispatches a manufacturing-routed `inventory.ReplenishmentRequested` — i.e. a make-to-stock work order, **not** a sales-order line. Since §2.37 the saga is always entered directly at `work_order_created` (the old `started` state was removed); the WO carries the originating `replenishment_request_id` (and the triggering `source_sales_order_header_id`, for the reporting board's SO↔WO link) rather than being bound to a sales order. One saga row per work order — sub-assembly children get their own saga at `work_order_created`.

### 5.1 — Order with sufficient raw materials

The make-to-stock state machine:

| State | Trigger |
|---|---|
| `work_order_created` | saga is entered here (WO created, BOM + routing snapshotted); worker emits `manufacturing.RawMaterialReservationRequested` |
| `raw_material_reservation_requested` | inbound `inventory.RawMaterialsReserved` |
| `raw_materials_reserved` | worker releases production |
| (worker drives ops to completion) | `POST /api/work-orders/{id}/operations/{seq}/complete` per op (UI: Production Board → **Complete operation**) |
| `completed` | terminal — set in the same txn as the last operation lands |

Sub-assembly recursion: if a BOM line is `sub_assembly`, the release service recurses, creating a child WO with `parent_work_order_id` set, and pre-attaches a child saga at `work_order_created`. Parent holds at `in_progress` until all children finish; the operation service cascades up the parent chain in the same transaction.

### 5.2 — Raw material shortage

Pre-state: only 2 × RM-LEG-001 on hand; ordered quantity needs 4.

`RawMaterialsReservedHandler` sees status `partially_reserved` / `failed`, transitions saga to `raw_material_shortage`, stashes the per-product shortage on saga.data, and emits `manufacturing.RawMaterialShortageDetected`. Since §2.35 this is consumed by **inventory** (not purchasing directly): inventory raises a `ReplenishmentRequest(reason=work_order_shortage)` and routes it by make-vs-buy — raw materials are buy-only, so it goes to purchasing, which creates a `purchase_requisition` with `source_type='stock_replenishment'` (which auto-converts to a PO in the same transaction — see Demo 6). When goods arrive, `GoodsReceivedHandler` (manufacturing-side) decrements the stash; once fully covered, the saga returns to `work_order_created` and re-emits the reservation request. Manufacturing and purchasing never signal each other directly — the coupling is mediated by `inventory.ReplenishmentRequest`.

---

## Demo 6 — Saga: purchase-to-pay

`purchasing.purchase_to_pay_saga` row is inserted at `started` in the same txn as the PO is created. **PO draft/approve flow:** manual PRs land their PO at `'draft'` and require a human to call `POST /api/purchase-orders/{id}/approve`; replenishment-driven PRs (Demo 5.2 — `source_type='stock_replenishment'`) auto-approve when `northwood.purchasing.shortagePoAutoApprove=true` (default), so the make-to-stock saga still flows automatically. Saga walks `started → purchase_order_approved → waiting_for_goods`. `three_way_match_*` saga states stay reserved for future variance-handling workflows.

### 6.1 — Manual requisition → approve → goods receipt → invoice → payment

1. Tom raises a requisition. UI (as Tom): **Purchasing → Purchase Requisitions** (New). Equivalent curl:
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

2. Tom approves the PO. UI: **Purchasing → Purchase Orders** → open the draft PO → **Approve**. Equivalent curl:
   ```bash
   curl -X POST http://localhost:8085/api/purchase-orders/{poId}/approve \
     -H 'content-type: application/json' \
     -d '{"approver":"tom","reason":"Needed for Aug build"}'
   ```
   PO flips to `'sent'`, emits `purchasing.PurchaseOrderApproved`. Saga: `started → purchase_order_approved` (inline, in the approve txn). Next worker tick: `purchase_order_approved → waiting_for_goods`.

3. Tom posts a goods receipt. UI: **Inventory → Goods Receipts → New** (pick the PO line from the picker). Equivalent curl:
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

4. Olivia records the supplier invoice. UI (as Olivia): **Finance → Supplier Invoices → New**. Equivalent curl:
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
   Finance runs **quantity + price-variance 3-way match**. Per-line invoice unit price is compared against the PO line's snapshotted unit_price; relative variance > `northwood.finance.match.priceTolerancePercent` (default 2.0%) parks the invoice at `three_way_match_failed`. On match, emits `finance.SupplierInvoiceApproved`. Purchasing saga: `goods_received` → `supplier_invoice_approved`.

   To force a price-variance failure for the demo, post the same invoice with `unitPrice: 30` against a PO line whose unit_price is 25 — the invoice lands in `three_way_match_failed`. Review the queue at **Finance → Pending Review** (`/supplier-invoices/pending-review`); resolve via `POST /api/supplier-invoices/{id}/manual-approve` (with `{reviewer, reason}`) or `POST /{id}/reject`.

5. Olivia pays. UI: **Finance → Payments → New → Supplier**. Equivalent curl:
   ```bash
   curl -X POST http://localhost:8086/api/payments \
     -H 'content-type: application/json' \
     -d '{"paymentNumber":"PMT-DEMO-6-1",
          "supplierInvoiceHeaderId":"...",
          "amount":1000,
          "paymentMethod":"bank_transfer"}'
   ```
   On full settlement: `supplier_invoice_approved` → `completed`; PO header flips to `paid`. On partial: → `supplier_partially_paid`, parks.

   Multi-invoice supplier payment (one cheque settles multiple approved invoices):
   ```bash
   curl -X POST http://localhost:8086/api/payments/multi -H 'content-type: application/json' -d '...'
   ```

GL postings (six pairs) fire alongside each step — see Demo 7's GL section. Track the money flow on **Reporting → PO Tracking** (`/purchase-orders/tracking`).

---

## Demo 7 — End-to-end with all three sagas + GL posting

The showcase. One sales order touches every service.

### 7.1 — Big order with raw-material shortage

Pre-state: 0 × FG-TABLE-001 on hand; 5 × RM-LEG-001 on hand; need 40 (4 × 10 tables); plenty of every other RM; one customer (Sydney Home Living); one supplier.

UI path (as Sarah): **Sales → Sales Orders → New**, CUST-001, 10 × FG-TABLE-001, submit. Equivalent curl:

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

1. Sales fulfilment saga: `started` → `stock_reservation_requested` → `stock_reservation_incomplete` (the FG-TABLE line can't be reserved — 0 on hand). The saga parks here.
2. Inventory raises a `ReplenishmentRequest(reason=sales_order_shortage)` for the FG and, because it's makeable, routes it to manufacturing as a make-to-stock WO. The make-to-stock saga is entered at `work_order_created`; releasing it hits the RM-LEG-001 shortage (short by 35) and advances to `raw_material_shortage`.
3. `manufacturing.RawMaterialShortageDetected` fires → **inventory** raises a second `ReplenishmentRequest(reason=work_order_shortage)`, routes it to purchasing → PR + PO → P2P saga starts at `started` → `waiting_for_goods`.
4. **Time-skip:** Tom posts a goods receipt for 35 × RM-LEG-001 (Demo 6 step 3 with `receivedQuantity:35`).
5. Inventory bumps `stock_balance.on_hand_quantity`. P2P saga: `waiting_for_goods` → `goods_received`.
6. Manufacturing's `GoodsReceivedHandler` un-parks the make-to-stock saga: → `work_order_created` → re-emits reservation → `raw_materials_reserved`.
7. Operation completion (Linda, on the Production Board): the FG work order(s) complete. Each top-level completion bumps FG `stock_balance.on_hand_quantity` and fulfils the FG replenishment (`inventory.ReplenishmentFulfilled`).
8. Once the FG replenishment is fulfilled, the parked sales saga retries: `stock_reservation_incomplete` → `stock_reservation_requested` → (now coverable) `ready_to_ship`.
9. Post a shipment for 10 units (Mike). Sales: `ready_to_ship` → `goods_shipped`. Inventory decrements on-hand and releases the reservation.
10. `sales.SalesOrderShipped` triggers finance to auto-create a customer invoice. Sales: `goods_shipped` → `invoice_created`.
11. Olivia records the supplier invoice and pays it (Demo 6 steps 4 & 5). P2P saga: → `supplier_invoice_approved` → `completed`.
12. Olivia receives the customer's payment for the customer invoice (Demo 3 final step). Sales saga: `invoice_created` → `completed`.

### What the audience sees on screens

- **Sales Order detail** (`/sales-orders/:id`): every status column on the fulfilment roll-up updates in real time.
- **PO Tracking** (`/purchase-orders/tracking`): received/invoiced/paid amounts accumulate.
- **Production Board** (`/production-board`): each WO walks `released → in_progress → completed`.
- **Material Shortage** (`/material-shortages`): row appears at `open`, walks `purchase_requested → purchase_ordered → resolved`.
- **Available-to-Promise** (`/atp`): on-hand drops at shipment, incoming-from-purchase rises on PO creation, drops on receipt.
- **Financial Dashboard** (`/financial-dashboard`): sales_revenue, COGS, gross_profit, cash_received/paid, plus open SO/PO/WO counts per day.

### GL postings to watch in `finance.journal_entry_header` / `_line`

Balanced debit/credit pairs land in the same txn as their originating action — the buy→sell cycle plus the §2.42 make cycle (perpetual WIP, fires during manufacturing replenishment). Surface them on **Finance → Journal Entries**:

| Action | Dr | Cr |
|---|---|---|
| Goods receipt | 1200 Inventory | 1300 GRNI (goods received not invoiced) |
| Supplier invoice approval | 1300 GRNI | 2100 AP |
| Supplier payment | 2100 AP | 1000 Bank |
| Shipment | 5000 COGS | 1200 Inventory |
| Customer invoice | 1100 AR | 4000 Revenue |
| Customer payment | 1000 Bank | 1100 AR |
| Raw materials issued to WO (§2.42) | 1230 WIP | 1210 Raw Materials |
| Work order completion (§2.42) | 1220 Finished Goods | 1230 WIP |
| Sub-assemblies consumed (§2.42) | 1230 WIP | 1220 Finished Goods |

Reverse a journal entry from **Finance → Journal Entries** (as Daniel) or via `POST /api/journal-entries/{id}/reverse` (creates a debit/credit-flipped reversal in the same txn that flips the original from `posted` → `reversed`).

### Acceptance criteria for the demo

- Three sagas run concurrently; nothing gets stuck.
- Replaying any inbox event is a no-op (idempotency).
- Sum of debits = sum of credits in `finance.journal_entry_line` (enforced by the deferred trigger `enforce_journal_balance`).
- Stop and restart any service mid-flow: the polling outbox + inbox catch up; no events lost (transactional outbox guarantee).

---

## Demo 8 — Security: roles, audit, and persona-aware UI

3-4 minute walkthrough that fits inside Demo 7 once the order has placed and the saga is running. Tells the security story end-to-end: authentication, role-gated mutations, actor-stamped events, audit timeline.

### 8.1 — Login flow

Open `http://localhost:5174` cold (or sign out). The SPA redirects to Keycloak's login form (`http://localhost:8090/realms/northwood`). Type `sarah` / `sarah`. Land back on the SPA — the top-right user chip shows "Sarah · sales_clerk". The BFF runs the OIDC code flow and holds the access token in a server-side session; no token ever reaches the browser.

### 8.2 — Role-gated mutation (the cancel-order moment)

On a Sales Order detail page (any order before `goods_shipped`), hover the **Cancel order** action. Sarah's `sales_clerk` doesn't include `sales_manager`, so it is disabled with tooltip "Requires role: sales_manager".

Sign out (top-right), then sign back in on Keycloak as **sam** (password == `sam`). The Cancel action is now enabled. Click it. Saga walks `compensating → compensated`. Audit row stamps `actor_user_id = "sam"`.

If you want the loud version: switch to **Aaron** (auditor) first, try to click anything mutating — every mutation action is disabled with a tooltip. Read-only persona; reading works.

### 8.3 — Audit log timeline

On any Sales Order detail page, open **View audit** (or go to **System → Audit Log** with the order's id). Lands on `/system/audit-log?aggregateId=<id>`. The BFF fans out to all 7 services and merges the timeline by occurredAt desc. Every event is labelled with its source service, event type, and actor. The cancellation row shows `actor_user_id = "sam"`; the original placement row shows `actor_user_id = "sarah"`. Saga-driven rows (e.g., `manufacturing.WorkOrderCreated`) show actor as "system".

The same screen accessible directly via **System → Audit Log** with no filter — shows recent activity across the whole stack.

### 8.4 — finance reverses a journal

Switch to **Daniel** (finance_manager). Navigate to **Finance → Journal Entries**. Pick a posted entry. Click **Reverse**. New journal entry created with debits/credits flipped; original flips to `'reversed'`. Open the audit log for that journal — both rows have actor=`daniel`.

Try the same as Olivia (accountant) — Reverse is disabled, tooltip "Requires role: finance_manager".

### 8.5 — Acceptance criteria for the security demo

- Login redirect works on cold-cache load.
- Sarah cancel-order → 403 + disabled action + tooltip.
- sam cancel-order → 200 + saga compensates.
- Aaron (auditor) can read every screen but mutates nothing.
- `actor_user_id` is non-null on all user-driven outbox rows; null on saga/system rows.
- `/system/audit-log` shows a coherent cross-service timeline within a few seconds of the action.

---

## Demo 9 — Resilience: broker outage (auto-recovery, zero data loss)

The reliability claim made visible: **kill Kafka mid-flow and nothing is lost** — the outbox holds every event durably and replays it when the broker returns, with no operator action. This is the producer-side "broker down → back" row from `docs/messaging.md` → *Disaster recovery* (✅ auto-recovered fully).

**Setup:** full stack up, the Sales Order detail page open for an in-flight order, and a psql session on a side screen:

```bash
docker exec -it northwood-postgres psql -U postgres -d northwood_erp
```

### 9.1 — Kill the broker, then place an order

```bash
docker stop northwood-kafka
```

Place a normal order (same shape as Demo 3.1). Note it **still returns 200**: the command path writes the aggregate + its outbox row in one local PostgreSQL transaction and never touches Kafka.

```bash
curl -X POST http://localhost:8082/api/sales-orders \
  -H 'content-type: application/json' \
  -d '{"orderNumber":"SO-DEMO-9-1","customerCode":"CUST-001","currencyCode":"AUD",
       "lines":[{"productId":"00000000-0000-7000-8000-000000000001",
                 "productSku":"FG-TABLE-001","productName":"Wooden Dining Table",
                 "orderedQuantity":1,"unitPrice":320}]}'
```

### 9.2 — Watch the outbox absorb the backlog

The sales drainer tries to publish every ~1s, fails (broker down), and marks each row `failed` — visible both in the service log (`WARN [sales] outbox row … failed`) and in the table:

```sql
SELECT event_type, status, retry_count, left(last_error, 40) AS err
  FROM sales.outbox_message
 WHERE status IN ('pending','failed')
 ORDER BY sequence_number;
```

On the **Sales Order detail** page the order is stuck at `started` / `stock_reservation_requested` — its events can't reach inventory, so it can't advance. Nothing is lost; it's *parked in the outbox*.

### 9.3 — Bring the broker back, watch it self-heal

```bash
docker start northwood-kafka
```

Within a tick or two — no service restart, no manual replay:

- the `sales.outbox_message` rows flip `failed → published` (re-run the query above; the `pending`/`failed` set drains to empty);
- the backlog flows to inventory / manufacturing / finance, each draining *its* outbox in turn;
- the **Sales Order detail** roll-up marches the order through to `completed`, exactly as in the happy path — just delayed by the outage.

### 9.4 — What this proves

| Claim | What the audience saw |
|---|---|
| The command path doesn't depend on the broker | order placement returned 200 with Kafka down |
| No event is lost on broker failure | rows sat durably in `outbox_message` as `failed`, never dropped |
| Recovery is automatic | `docker start` alone drained the backlog and completed the saga — no operator action |
| Idempotent replay is safe | re-published events that were already partially consumed are deduped by the inbox |

The outbox pattern's whole point: **Kafka is the transport, not the system of record** — so a broker outage is a *delay*, not a *loss*. (Contrast: losing the PostgreSQL volume *without a backup* is the one unrecoverable case — `docs/messaging.md` → *Disaster recovery*.)

> **Variant (advanced):** instead of stopping Kafka, `Ctrl-C` a mid-flow service (e.g. manufacturing) and restart it — the saga's lease expires and a sibling/restarted worker reclaims it (`claimDue`, `lease_expires_at < now()`), resuming the flow. Same self-heals-on-restart story, pinned by `JdbcSalesOrderFulfilmentSagaAdapterIT.claimDue_reclaims_a_row_whose_lease_has_expired`.

### 9.5 — Not demoed (and why)

A *consumer-dependency* outage (e.g. PostgreSQL briefly down while Kafka keeps delivering) is now **also auto-recovering** (§2.28 Tier 1): the consumer error handler rides out the blip with an `ExponentialBackOff` (default 5-min budget) instead of dead-lettering in milliseconds — the old `FixedBackOff(0,3)` (finding 1) is fixed — and anything that *does* reach a `<topic>.dlt` is auto-redriven by each service's `DltRedriver` (re-applied once the dependency is back, or parked in `<topic>.dlt.parked` after the cap if genuinely unrecoverable). It isn't staged as a separate live beat because the timing (a sub-5-minute outage) is awkward to demo by hand — it's pinned by `KafkaInboxDispatcherDeliveryIT` + `DltRedriverIT` and the live end-to-end redrive recorded in `dev-done.md` (§2.28 Tier 1). See `docs/messaging.md` → *Disaster recovery*.

---

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| SPA redirect loops or `/api/*` calls fail with `ECONNREFUSED` | The ERP BFF (port 8089) isn't running. Start `mvn -pl erp-web-ui-bff spring-boot:run`. |
| Login redirect fails / Keycloak unreachable | Keycloak (port 8090) isn't up, or the realm didn't import. Check `docker compose logs keycloak` for the realm import, then retry. |
| Sales Orders list / Reporting pages empty | reporting-service (8087) or the BFF isn't running, or the seed override wasn't loaded. Start them; for fixtures re-up with the seed override. |
| A module page is empty even with the BFF up | The owning service isn't running. The BFF gracefully degrades and logs the upstream failure. |
| Place order returns 400 with `Customer not found` | `CUST-001` lives in the **seed** file, so the stack came up without the seed override. Re-up with `docker compose -f docker-compose.yml -f docker-compose.seed.yml up -d`; `docker compose logs postgres` should then show `02-northwood_erp_seed.sql ... DONE`. |
| Goods receipt 400s | The PO id in the form must be a real PO header UUID. Get it from **Reporting → PO Tracking** or the Purchase Orders page. |
| Goods receipt 400 with `Unknown purchase_order_line_id` | The receipt line names a `purchaseOrderLineId` that inventory hasn't projected yet — usually because the PO hasn't been created at all, or `PurchaseOrderCreated` hasn't reached inventory's inbox yet (cold start). Use the picker in **Inventory → Goods Receipts → New** rather than hand-typed line ids. |
| Goods receipt 400 with `Product mismatch on purchase_order_line_id` | The receipt line's `productId` doesn't match the `purchase_order_line.product_id`. Defence-in-depth check against client-side picker bypass. Re-pick the line from the picker. |
| Shipment 400 with `Unknown sales_order_line_id` / `Product mismatch on sales_order_line_id` | Same shape on the SO side. Inventory projects `sales_order_line_facts` from `SalesOrderPlaced`; the validation runs in `ShipmentService.post`. Lines without a `salesOrderLineId` (unlinked manual shipments) skip the check. |
| Make-to-stock saga never appears (and `manufacturing.work_order_header` stays empty) on a shortage order | Manufacturing-service didn't receive inventory's `inventory.ReplenishmentRequested` event. Either manufacturing-service isn't running, **or it was launched without `SPRING_PROFILES_ACTIVE=kafka`** so it's on the in-JVM bus and never sees cross-JVM events. Set the profile and restart. |
| Sales saga stuck on `stock_reservation_incomplete` | The replenishment that should top up on-hand isn't completing — manufacturing (or purchasing) isn't getting inventory's `ReplenishmentRequested`, or the make-to-stock WO / PO is itself stalled. Check the kafka profile is set on every service, then trace the replenishment via the `inventory.replenishment_request` table. |
| Service starts but logs `FIND_COORDINATOR` errors / consumer group never joins | Stale Kafka volume from before the single-broker replication-factor overrides were added. `docker compose down -v ; docker compose -f docker-compose.yml -f docker-compose.seed.yml up -d`. The volume gets wiped, internal topics (`__consumer_offsets`, `__transaction_state`) get recreated with replication-factor=1. |
| `kafka-console-consumer.sh --from-beginning` returns 0 messages even though publishes succeeded | Same root cause as above — the spawned consumer-group can't find its coordinator. Probe with `kafka-get-offsets.sh --topic <name>` first to confirm messages are actually there. |
| Customer payment 400 with CHECK constraint error | Out-of-date Postgres volume from before the `2026-05-05-extend-fulfilment-saga-states.sql` migration. Either let Liquibase apply it on next sales-service boot, or wipe the volume: `docker compose down -v ; docker compose -f docker-compose.yml -f docker-compose.seed.yml up -d`. |
| Manufacturing-service fails to boot with `Unexpected formatting in formatted changelog ... line N` | A `--` comment line accidentally starts with a Liquibase keyword (`changeset`, `rollback`, etc.). See CLAUDE.md § Liquibase gotchas. |

To reset and start fresh: `docker compose down -v ; docker compose -f docker-compose.yml -f docker-compose.seed.yml up -d` then re-bring up the services (with `SPRING_PROFILES_ACTIVE=kafka` per terminal) + the ERP BFF + the SPA.

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
mvn test                                       # all backend unit tests
mvn -pl inventory-service verify               # Testcontainers IT (~50s)
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

---

## Where to next

- `dev-todo.md` — implementation backlog (slice-level).
- `CLAUDE.md` — architecture invariants (read first if you're modifying the backend).
</content>
</invoke>
