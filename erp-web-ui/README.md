# Northwood ERP Web UI

Operational ERP SPA for the business-user personas (Sarah / Mike / Linda / Tom / Olivia / Daniel / Emma). Sibling of `demo-web-ui/` — that one is the technical demo (Saga Console, event drawer, scenario runner); this one is the application.

## Running locally

The fetch chain is `5174 → 8089 → backend service → postgres`. Bring up the layers from the bottom:

```powershell
# 1. Database (empty schema). For pre-loaded demo fixtures, layer in the seed override:
#    docker compose -f docker-compose.yml -f docker-compose.seed.yml up -d postgres
docker compose up -d postgres

# 2. Backend services — minimum is reporting (read projections) for most pages.
#    For full operational flows + saga progress, run all 7 + Kafka.
mvn -pl reporting-service spring-boot:run        # 8087

# 3. The new ERP BFF (sibling of demo-web-ui-bff). Easy to forget — without
#    it, every /api/* call fails with ECONNREFUSED in the Vite proxy log.
mvn -pl erp-web-ui-bff spring-boot:run           # 8089

# 4. SPA dev server.
cd erp-web-ui
npm install                                      # first run only
npm run dev                                      # http://localhost:5174
```

Each backend command takes its own terminal. IntelliJ run configurations are the easier path for multi-service runs.

## Ports

| Component | Port |
|---|---|
| ERP SPA (Vite dev server) | 5174 |
| ERP BFF                    | 8089 |
| Demo SPA (separate, in `demo-web-ui/`) | 5173 |
| Demo BFF (separate, in `demo-web-ui-bff/`) | 8080 |
| product / sales / inventory / manufacturing / purchasing / finance / reporting | 8081–8087 |

The two SPAs and their two BFFs are independent — running both at once is fine.

## What's wired

C0 (shipped 2026-05-06) — shell + one real route:
- Module-grouped sidebar (Sales / Purchasing / Inventory / Manufacturing / Finance / Reporting / System).
- AppBar with logo + global search + notifications + user menu (anonymous placeholder until Slice A).
- Breadcrumb on every page.
- UI primitives (`<DataGrid>`, `<StatusPill>`, `<PageHeader>`, `<ActionButton>`, `<Breadcrumb>`).
- `/sales-orders` — full list reading from reporting's projection. Every other module sub-page renders `<Placeholder>`.

Sub-slices C1–C4 fill in the placeholders. See `docs/dev-todo.md` §1.3.

## Visual identity

Fiori-with-Odoo-restraint: corporate deep blue (`#1E3A8A`), cool grays, 1px borders over shadows, status pills with consistent tone-color mapping across modules. Tailwind 4 `@theme` tokens in `src/index.css`. The demo SPA uses a deliberately different dark palette — family resemblance through typography and structure, not color.

## Build

```powershell
npm run typecheck    # tsc -b --noEmit
npm run build        # tsc -b + vite build
```
