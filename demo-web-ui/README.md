# Northwood Demo Web UI

Demo SPA for the Northwood ERP showcase — Saga Console, event drawer, scenario runner. The technical-storytelling SPA. Sibling: `erp-web-ui` is the operational ERP for business-user personas (see `docs/erp-web-ui-design.md`). See `docs/demo-web-ui-design.md` for this SPA's design rationale.

**Phase 1** shipped the read-only shell:
- App layout (top bar + persona-grouped sidebar + collapsible event drawer)
- Routing skeleton with placeholders for every persona view
- Dashboard reading from reporting-service's `/api/financial-dashboard`
- Stubbed event stream in the bottom drawer (real SSE arrives in Phase 3)

**Phase 6** lands the **BFF** — `demo-web-ui-bff/` Maven module on port 8080:
- Single Vite proxy entry (`/api → 8080`) replaces the previous ~15 path-prefix mappings.
- Generic HTTP reverse proxy via JDK 21 `HttpClient` honours the routing table in `RouteTable.java` (mirrors what the Vite proxy used to do).
- Aggregated saga SSE: `GET /api/sagas` returns the merged list across all three saga services, ordered newest-first; `GET /api/sagas/stream` is a single SSE stream that the SPA consumes (was three concurrent streams before). Internal poller hits each upstream `/api/sagas` at 1 s, diffs by `(sagaId, version)`, broadcasts deltas.
- BFF stays domain-agnostic: deliberately doesn't depend on the `shared` module, no DB, no Liquibase, no Kafka. Composes, fans out, translates only.
- The SPA contract didn't change — `src/api/fetchers.ts`, `commands.ts`, and `useSagaStream` keep their paths; only the proxy endpoint moved.

**Phase 5** ships the **scenario runner** — three baked playthroughs in the top-bar Scenarios dropdown:

- **3.1 — Sales fulfilment (happy path)**: places a small order, waits for each saga state in turn, prompts for human steps (complete operations / post shipment / record customer payment), then waits for the sales saga to reach `completed`.
- **5.2 — Raw material shortage**: places an order that exceeds raw materials, watches the make-to-order saga park at `raw_material_shortage`, waits for purchasing to auto-issue the PR/PO, then prompts for goods receipt to un-park.
- **7.1 — Big order touches every service**: combines 5.2 with the full settlement path through to all three sagas reaching `completed`.

The runner state machine lives in `src/scenarios/runner.ts` — it interleaves auto API calls with `wait-for-saga-state` polls (2 s polls, 60–90 s timeouts) and pauses for human steps until the user clicks **Run step**. Every step's status renders inline (✓ / ▶ / ◯ / failed); the captured context UUIDs surface in a collapsible panel so the narrator can paste them into manual forms if they pause and explore. Pause / skip / abort are always available.

**Phase 4** adds the **write paths** so the demo can be driven entirely from the SPA:
- **Place sales order** (`/sales-orders/new`) — Sarah's form. Customer code defaults to `CUST-001`; line dropdown bound to active products with auto-filled price; total recalculated live. Posts to sales-service via the `/api/sales-cmd` proxy alias and redirects to the saga console so the audience watches it advance.
- **Goods receipt** (`/goods-receipts`) — Mike posts a receipt against an open PO. Inventory bumps stock_balance and emits `inventory.GoodsReceived` (which un-parks any make-to-order saga in `raw_material_shortage`).
- **Shipment** (`/shipments`) — Mike posts a shipment against a fulfilment-ready SO. Inventory decrements on-hand and releases the reservation; finance auto-creates the customer invoice from `sales.SalesOrderShipped`.
- **Supplier invoice** (`/supplier-invoices`) — Olivia records a supplier invoice; finance runs the quantity-only 3-way match and emits `finance.SupplierInvoiceApproved` on success.
- **Payments** (`/payments`) — Olivia, with a tabbed switcher between supplier (AP) and customer (AR) payments. Both close the corresponding saga on full settlement.
- **Complete operation modal** — added to the production board's WO detail. Linda picks an op sequence + actual minutes; the make-to-order saga advances.
- **Product edit modal** — Emma's pricing / reorder / discontinue commands inline on the products list (small ✏ button per row).

**Note:** Phase 4 originally shipped without a BFF, with the SPA orchestrating from the browser via per-path Vite proxies. Phase 6 added the BFF and collapsed the Vite proxy to a single entry; the SPA's fetcher contract stayed identical, so this paragraph is now historical.

**Phase 3** adds the **Saga Console** (`/saga-console`) — three-column live view of every saga across sales / manufacturing / purchasing. Each row renders the forward-path stages as inline dots; the current dot pulses; rows flash on update. SSE backed (1s server-side poll diffs version-changed rows and pushes); falls back to polling on disconnect. **Note:** the design doc named xyflow for the diagrams; in practice the inline step-dot rendering reads cleaner and uses the same idiom as Phase 2 — xyflow can be revisited in a polish pass.

To support Phase 3, sales-service / manufacturing-service / purchasing-service each gained a `SagaApiController` exposing `GET /api/sagas` (list) and `GET /api/sagas/stream` (SSE). Vite proxies `/api/sagas/{sales,manufacturing,purchasing}` → the matching service so all three streams ride the same SPA origin.

**Phase 2** adds read-only depth — seven persona-driven pages backed by reporting projections:
- Products (`/products`) — catalog list (product-service, :8081)
- Stock items (`/stock-items`) — projected master view (inventory-service, :8083)
- Sales orders (`/sales-orders`) — list + 6-stage timeline (reporting, :8087)
- Purchase orders (`/purchase-orders`) — list + money-flow tracking (reporting)
- Production board (`/production-board`) — list + per-WO progress + shortage info (reporting)
- ATP (`/atp`) — list + on-hand/reserved/incoming composition (reporting)
- Material shortages (`/material-shortages`) — active/all toggle + 4-stage status walk (reporting)

To support Phase 2, reporting-service gained three list endpoints (`GET /api/sales-orders`, `/api/purchase-orders`, `/api/work-orders`); product-service and inventory-service gained `findAll()` on their repositories and `GET /api/products` / `GET /api/stock-items` list endpoints.

## Run

Prereqs:
- Postgres up: `docker compose up -d postgres` (empty schema). For pre-loaded fixtures, layer in the seed override: `docker compose -f docker-compose.yml -f docker-compose.seed.yml up -d postgres`.
- Services: `mvn -pl reporting-service spring-boot:run` (must), plus product-service / inventory-service if you want those views populated.

```powershell
cd demo-web-ui
npm install
npm run dev          # http://localhost:5173
```

Other useful scripts:

- `npm run build` — type-check + production bundle into `dist/`
- `npm run typecheck` — `tsc -b --noEmit`
- `npm run preview` — serve the production bundle locally

## Layout

```
src/
├── api/                 fetchers per service
├── components/
│   ├── layout/          AppShell, TopBar, Sidebar, EventDrawer
│   └── ui/              small Card/Badge primitives (no shadcn CLI yet)
├── lib/                 cn(), formatMoney, truncateUuid, formatTime
├── routes/              one file per top-level page
├── personas.ts          persona metadata (name, role, accent colour)
├── App.tsx              router
├── main.tsx             entry
└── index.css            Tailwind v4 + theme tokens
```

## Notes

- **Tailwind v4.** Theme tokens live in `index.css` under `@theme` — no `tailwind.config.ts`.
- **Dev proxy.** `vite.config.ts` proxies `/api/*` to the right Spring service per path. Phase 4 collapses these to a single `/api` → BFF on 8080.
- **No shadcn CLI** yet. The handful of primitives we need (Card, Badge) are tiny Tailwind compositions in `components/ui/`. We can adopt the CLI later if the component count grows.
