# dev-todo.md

Implementation backlog ordered by **priority and dependency**, top-to-bottom. The first item is the next thing to pick up unless a new conversation surfaces something more pressing. Items inside each section are also priority-ordered.

When a slice ships:
1. Append an entry to `dev-done.md` under a new `## YYYY-MM-DD — <title>` heading. Keep enough detail to read cold (what shipped, smoke-test result, follow-ups noted at the time).
2. Remove (or trim) the corresponding entry here. If the slice surfaced new follow-ups, drop them into the appropriate section, ranked alongside existing items.

This rule is captured at the top so future-me sees it before opening either file.

---

## 1. Security + Operational UI demo ✅ COMPLETE 2026-05-08

All four slices shipped: **1.1** Keycloak realm + OAuth2 wiring; **1.2 B1+B2** per-endpoint authorization (`@PreAuthorize` on 32 mutating endpoints across 13 roles) + actor stamping (envelope/repository-level); **1.3 C0–C5 + placeholders** operational UI under `erp-web-ui/` + `erp-web-ui-bff/` (port 8089); **1.4** audit-log viewer + persona switcher + role-gated `ActionButton`. See dev-done.md for the role matrix, endpoint catalogue, wire shapes, and smoke checklists.

**Won't touch (no demand):** per-row authorization (e.g. "salespeople only see their own customers"). **Deferred to §3:** BOM editor UI (`/boms`); `/system/users` page.

---

## 1A. Events-jar split ✅ COMPLETE 2026-05-10

All 6 producer events jars shipped: `product-events`, `sales-events`, `inventory-events`, `manufacturing-events`, `purchasing-events`, `finance-events`. 44 cross-service events total. Every consumer handler imports its producer's record directly; no `*Payload` records remain in the codebase. See dev-done.md for the per-slice writeups + the locked architectural decisions (events jar contains events + transitively-referenced VOs at their natural package; consumer never depends on producer-service, only on producer-events). Pattern documented in CLAUDE.md → "Events jars" subsection.

---

## 1B. Demo Web UI — remaining screens ✅ COMPLETE 2026-05-12

All slices shipped 2026-05-12: **1B.1–1B.8** (Olivia / Daniel / Tom / Event-log screens + cleanup), **1B.9 UX hardening** (shipment+receipt product validation, scenario-runner verify-predicate, supplier-invoice goods-receipt picker), **1B.9 Phase 6 motion** (saga state pulse, event-row slide-in, reduced-motion fallback). Full end-to-end runthrough against the real stack 2026-05-12 drove scenarios 3.1 + 5.2 through to completion; both new validations rejected with 400 + descriptive messages exactly as designed. See dev-done.md for the per-slice writeups.

**Carried forward:** edge-following dot on saga stage tracker (cheap substitute is the persistent pulse on the current stage dot; literal moving-dot needs SVG path-follow primitives, marginal gain). Projector-specific tweaks (high-contrast on muted text, font-size up-tick on table cells) are unscheduled until an actual projector runthrough surfaces a concrete legibility issue.

---

## 1C. erp-web-ui — operational forms to reach user-stories.md parity ✅ COMPLETE 2026-05-12

All four primary forms shipped: **1C.1** `/shipments/new` (Mike), **1C.2** `/goods-receipts/new` (Mike), **1C.3** `/supplier-invoices/new` (Olivia), **1C.4** `/payments/new` two-tab AP+AR (Olivia). Plus the four earlier creation forms (`/customers/new`, `/products/new`, `/sales-orders/new`, `/purchase-requisitions`) — all shipped this session. Stories 3.1 / 5.2 / 6.1 / 7.1 are now drivable end-to-end through erp-web-ui without bouncing to demo-web-ui or curl. Pre-existing Toast / ConfirmDialog / Tone-literal TS errors fixed alongside §1C.1 so `npm run build` is green. See dev-done.md for the per-slice writeup + the shared picker-and-auto-fill pattern.

**§1C.5 deferred (secondary, not story-critical):** `/journal-entries` viewer + reverse, `/production-board` Kanban actions, `/suppliers` authoring, `/stock-items` detail. Each has explicit defer rationale; pull forward when a stakeholder asks for that specific screen.

---

## 1D. Observability — LGTM stack + SPA trace integration (PLANNED 2026-05-13, not started)

Northwood has Spring Boot Actuator on every service + both BFFs today, plus a persistent audit log and the SPA Saga Console / Event Log for cross-service visibility. What's missing: **distributed tracing** (the causal-chain view that complements the event drawer's *what happened* view), **metrics** (the *is it healthy* view), and **log aggregation** (one query surface across 7 services).

Design discussion + scope decisions captured 2026-05-13. Audience = **both** (Phase 1 demo storytelling, Phase 2 ops). Stack = **LGTM** (Loki / Grafana / Tempo / Prometheus — all run cheap in docker-compose, all under Grafana). SPA depth = **trace IDs surfaced in Event Log + Saga Console** with click-through to Grafana Tempo explore.

Two open decisions noted at plan time:
- Sampling rate: 100% under `dev` profile (rich demo data), 1-10% the typical prod posture — pin at implementation time.
- Custom-metrics scope: planned **technical-flavour** gauges (outbox lag, saga state distribution, event throughput) since "Business-domain metrics + custom dashboards" wasn't picked in scope. Revisit if a business-KPI panel becomes part of the demo narrative.

### 1D Phase 1 — showcase (sequential where noted; otherwise parallelizable)

| Slice | Title | Depends on | Headline change |
|---|---|---|---|
| **1D.0** | Infrastructure | — | Add `tempo`, `prometheus`, `loki` + `promtail`, `grafana` to `docker-compose.yml`. Provisioned datasources + skeleton dashboard under `db/grafana/provisioning/`. |
| **1D.1** | Service instrumentation (shared module) | 1D.0 | `shared/pom.xml` adds `micrometer-registry-prometheus`, `micrometer-tracing-bridge-otel`, `opentelemetry-exporter-otlp`, `logstash-logback-encoder`, `loki-logback-appender`. Expose `prometheus` + `health` + `metrics` Actuator endpoints. OTLP exporter → `http://localhost:4317`. `logback-spring.xml` with JSON stdout + Loki appender + `%X{traceId}` / `%X{spanId}` from MDC. Each service confirms `spring.application.name`. |
| **1D.2** | Cross-service trace propagation | 1D.1 | Micrometer Tracing's Kafka instrumentation auto-handles `traceparent` headers once 1D.1 lands. OTel auto-instruments Spring's `RestClient` (saga aggregator polling, ERP BFF). `OutboxPublisher` also stamps `traceparent` into `EventEnvelope.headers` so the SPA can read it without a separate JOIN. Verify: drive place-order, one trace tree spans sales → inventory → manufacturing → finance. |
| **1D.3** | Schema: trace_id columns | — (independent) | One Liquibase changeset per table. `shared.audit_entry` gains `trace_id VARCHAR(32)` populated from current span. Each saga state table (`sales_order_fulfilment_saga`, `make_to_order_saga`, `purchase_to_pay_saga`) gains `trace_id` on row creation, preserved across transitions. Enables "give me the trace of this saga" via single column lookup. |
| **1D.4** | SPA integration | 1D.2 | `EventsAggregatorController.EventRow` adds `traceparent`. `demo-web-ui/EventStreamContext` types pick it up. `EventLog.tsx` + `SagaConsole.tsx` each render a small `↗ trace` affordance per row → opens Grafana Tempo Explore at that trace. Grafana itself stays off-BFF on `:3000` (no BFF route needed). |
| **1D.5** | Curated showcase dashboard | 1D.1 + 1D.2 + 1D.3 | One Grafana board, three rows. **Row 1** service health (up gauges, JVM heap, HTTP RPS). **Row 2** bus health — `outbox.pending` per service (custom Micrometer gauge), event throughput per Kafka topic, saga state distribution (counter per `state` per saga type). **Row 3** "a placed order's journey" — TraceQL panel `{name=~"POST /api/sales-orders"}`, click span → Loki logs for that span. |

### 1D Phase 2 — operational depth (deferred; resume after Phase 1 lands)

- **2.1 Alertmanager** wired to Slack/email. Sample rules: outbox stuck (pending > N for > M min), saga stuck in non-terminal state > N min, error-rate spike.
- **2.2 SLO panels** — 99% of events processed within 5s; 99% of saga transitions within 30s.
- **2.3 Health probes** — separate liveness / readiness / startup (Spring Boot Actuator groups) so future k8s deployment gets correct probes.
- **2.4 Exemplars** on latency histograms — click a slow bucket to jump to a trace from that bucket.
- **2.5 Loki labels** — promote `aggregateId`, `sagaId`, `eventType` from MDC into Loki labels so log queries like `{service="sales"} | json | sagaId="..."` work.

### Estimate

Phase 1: ~3-5 working sessions depending on dashboard polish. Phase 2 can be paused indefinitely — the Phase 1 foundation supports it but nothing in Phase 2 is story-critical.

### Why not now

Logged today (2026-05-13) but **not started** — capturing the design while it's fresh, picking up after the next demo-narrative beat. Pull forward when the audience question "how do you debug across 7 services?" lands in a real demo.

---

## 1E. GitHub publish prep — README polish, CI, repo metadata (PLANNED 2026-05-13, partial)

Prep work to make the repo presentable as a public GitHub project. Foundation pieces landed today (license, gitignore, secrets sweep); remaining items deferred so the user can decide tone/voice on the README themselves.

### Decisions locked 2026-05-13

- **License**: Apache 2.0 (Copyright 2026 Chi Liu). LICENSE file at repo root.
- **Repo name**: `northwood-erp`.
- **Default branch**: `main` (already).

### Already shipped 2026-05-13 (foundation)

- **`LICENSE`** at repo root — standard Apache-2.0 text + copyright line.
- **Root `.gitignore` extended** — adds `.claude/` (Claude Code's local session state, which was previously untracked-but-uncommitted), `.env` / `.env.*` / `*.local` (safety against committed env overrides), `**/node_modules/` + `**/dist/` + `**/dist-ssr/` + `.vite/` (workspace-level safety net even though the two SPAs each have their own `.gitignore`). `t3/` retained (historical doc snapshots, repo-private working notes).
- **Secrets sweep — clean.** Documented demo-grade defaults that are public-safe but must be flagged in the README:
  - Keycloak BFF client secret defaults to `northwood-bff-secret` (`erp-web-ui-bff/.../application.yml`); overridable via `KEYCLOAK_BFF_CLIENT_SECRET`.
  - 13 Keycloak user passwords in `db/keycloak/northwood-realm.json` (each user's password is their first name / role tier — `emma`/`emma`, `sales-mgr`/`sales-mgr`, etc.). Replace the realm file for any non-demo deployment.
  - 7 service DB passwords default to `postgres`; each overridable via `<SERVICE>_DB_PASSWORD` (e.g. `SALES_DB_PASSWORD`).
  - Demo BFF bypass token (`northwood-local-demo-bypass-2026`) in `BackendAuthHeader.java`; overridable via `NORTHWOOD_SECURITY_DEMOBYPASS_TOKEN` (set blank to disable the filter entirely). Auto-memory `project_demo_bff_security_bypass.md` carries the design rationale.
  - No private keys, certificates, real API keys, leaked personal paths, or personal email addresses.

### Remaining work (not started — pick up when ready to publish)

#### 1E.1 README polish for public audience

Current `README.md` is project-internal — assumes the reader is already inside the codebase. Public-facing rewrite needs:

- **One-line elevator pitch** at the top: something like *"Event-driven microservices ERP architecture showcase — Spring Boot 4, 7 services with sagas + outbox/inbox, BFFs, two React/Vite demo SPAs."*
- **Requirements** stated explicitly: JDK 21, Docker (Postgres 17 + Kafka 4.1.2 + Keycloak), Node 20+, Maven 3.9+, Windows / macOS / Linux supported.
- **Screenshot or short GIF** of the Saga Console + Event Log + a curated demo flow. Visual cue carries more than prose for an architecture demo. (Could be in `docs/screenshots/` referenced relatively, or hosted on a GitHub Pages branch.)
- **"Demo credentials" disclosure section** listing the four items from the secrets sweep above, with the env-var override for each. Single most important addition for any consumer who might try to deploy this for real.
- **"Where to read next" pointers** to `CLAUDE.md`, `docs/demo-script.md`, `docs/architecture.md`, `docs/conventions.md` — the existing internal docs are useful to outside readers too, just need a roadmap to them.
- Keep the existing repository-structure tree (`README.md:10-30`-ish); it's already concise and useful for orientation.

#### 1E.2 GitHub Actions CI workflow

`.github/workflows/build.yml` running on push + PR. ~30 lines. Two jobs in one file:

- **`backend`**: `actions/setup-java@v4` (Temurin 21), Maven cache, `mvn -B clean verify -DskipITs` (skip Testcontainers ITs by default since they need Docker-in-Docker; can be opt-in via workflow_dispatch input).
- **`frontend`**: `actions/setup-node@v4` (Node 20), npm cache, `npm ci && npm run build` for each of `demo-web-ui` and `erp-web-ui`.

Status badge in README header once the workflow lands. Skip Docker-based smoke for now — the docker-compose-up-then-boot path is hard to capture in standard GitHub-hosted runners without rearchitecture.

#### 1E.3 GitHub-side metadata (paste in via the GitHub UI when creating the repo)

Not in-repo, but worth capturing the wording:

- **Description**: `Event-driven microservices ERP architecture showcase — Spring Boot 4, sagas, outbox/inbox, BFFs, React/Vite demo SPAs.`
- **Topics / tags**: `spring-boot`, `microservices`, `saga-pattern`, `outbox-pattern`, `event-driven-architecture`, `kafka`, `postgresql`, `domain-driven-design`, `hexagonal-architecture`, `java-21`, `react`, `vite`.
- **Default branch**: `main` (already).
- **Branch protection** on `main`: require status checks (the CI workflow above) once it lands.
- **Discussions / Issues**: enable Issues; leave Discussions off unless you want a community channel.

### Out of scope (deferred indefinitely; signal "open to outside contributors", which may or may not be wanted)

- `CONTRIBUTING.md` — not needed for a personal showcase.
- `SECURITY.md` — overkill for a demo.
- `CODE_OF_CONDUCT.md` — not needed unless you want a community.
- Demo video / hosted GitHub Pages tour — nice if time permits, but optional.

### Why not now

User wants to drive the README's tone and voice themselves (it's a public-facing artifact, more like marketing copy than internal docs). CI is mechanical and can land any time. Logged today (2026-05-13) so the next session has full context without re-deriving the audit.

---

## 1F. Event-flow consumer coverage gaps (PLANNED 2026-05-14, partial)

`event-flow.html` (added 2026-05-14) audits every domain event and its consumer set; the **§ Coverage gaps** section there identified 9 events with no consumer plus 3 partial-coverage cases. Items below are the actionable backlog — full symptom → impact → suggested-handler analysis lives in `event-flow.html`; this section is the work list. Critical first (correctness gaps), then staleness, then explicit deferrals.

§1F.1 (`ProductDiscontinued` consumers across 5 services) shipped 2026-05-14 — see `dev-done.md` for the five per-service slice entries. Remaining items below.

### 1F Critical — business-incorrect decisions today

§1F.2 (`ProductCreated` inventory consumer) shipped 2026-05-14 — see `dev-done.md`.  
§1F.3 (`CustomerDeactivated` reporting + finance consumers) shipped 2026-05-14 — see `dev-done.md`.  
§1F.4 (`PurchaseOrderApproved` reporting consumer) shipped 2026-05-14 — see `dev-done.md`.  
§1F.5 (`ProductMaterialsCostComputed` product consumer — closes the cost loop) shipped 2026-05-14 — see `dev-done.md`.  
§1F.6a (`ProductCreated` sales consumer + lifecycle-closure refactor on `sales.product_pricing`) shipped 2026-05-15 — see `dev-done.md`.  
§1F.6b (consolidate `finance.product_standard_cost` + `finance.product_valuation_class` → `finance.product_accounting`) shipped 2026-05-15 — see `dev-done.md`.  

All §1F actionable items shipped. Remaining backlog is the deferred / inferred-only set documented below.

### 1F Deferred — captured so they're not re-discovered

Documented as "considered, not pulled forward unless a trigger surfaces":

- **Customer master events** (`CustomerRegistered`, `CustomerNameChanged`, `CustomerAddressChanged`, `CustomerContactChanged`) — downstream views snapshot customer name from event payloads at order time, which is *historically correct*. Only a real gap if a UI requirement says "show current customer name on past orders".
- **`ManufacturingDispatched` → production-planning** — rejected lines (no BOM, not manufactured) generate dispatch outcomes but no work orders, so the planning board has nothing to update. Pull forward only if a manufacturing-rejection-rate widget is requested.
- **`WorkOrderCancelled` → finance** — no WIP cost is posted to GL today; nothing to reverse. Becomes real if WIP capitalisation lands (currently §3-adjacent, low priority).
- **`OperationCompleted` → ATP** — could progressively release planned quantity per completed operation, but current ATP waits for full `WorkOrderManufacturingCompleted`. Precision tradeoff, not a correctness gap.
- **`SalesPriceChanged` → reporting** — could feed a price-trend dashboard, but the demo doesn't require it.

### Why not now

Logged 2026-05-14 after the event-flow audit. The Critical items (1F.1, 1F.2) genuinely affect correctness but aren't demo-blocking because the demo dataset never discontinues a product mid-script, the seed pre-creates `stock_item` rows for the 5 demo SKUs, and customers aren't deactivated mid-flow. Pull forward as soon as the demo script gains a "product retirement" or "customer churn" beat — or when a public-facing audience asks "what happens when we discontinue a product?". Staleness items (1F.4, 1F.5) are pure data-quality cleanup; pull forward alongside any reporting / costing slice that touches the same projections.

---

## 1G. SPA story-coverage gaps (audit 2026-05-15)

Cross-SPA audit against `docs/user-stories.md` found that every story (1.1–7.1) is *observable* in both SPAs. Four stories had command-driver gaps in `demo-web-ui` (now closed) and one has a UX gap in `erp-web-ui` (parked).

- **§1G.1 – §1G.4 ✅ shipped 2026-05-15** — demo-web-ui now has register-product, make-vs-buy, cancel-order, and PO-approve UI. See `dev-done.md` for the bundled writeup.
- **§1G.5 ⏳ parked** — erp-web-ui scenario runner. Pull forward only if a public-facing audience wants the orchestrated walkthrough from the operational SPA too; the per-persona forms already work manually.

The per-item scope and rationale below is kept for the parked §1G.5; §1G.1–§1G.4 are now historical context only.

### 1G.1 demo-web-ui — Story 1.1 register product form ✅ shipped 2026-05-15

`/products` is list + inline-edits only; no "Create SKU" button or form. `demo-web-ui/src/api/commands.ts` carries price / cost / reorder / discontinue wrappers but no `createProduct`. Emma can mutate existing seed SKUs but can't add a new one through this SPA. Backend: `POST /api/products` (Story 1.1 ✅ in user-stories).

Scope: new fetcher in `commands.ts` (`createProduct(req)` → POST `/api/products`); new form route `/products/new` or inline modal on `/products`; ProductType + UoM picker (UoMs are seeded; ProductType is the existing enum).

### 1G.2 demo-web-ui — Story 1.5 make-vs-buy toggle ✅ shipped 2026-05-15

Backend endpoint `PUT /api/products/{id}/make-vs-buy` exists; `api/commands.ts` is missing the wrapper, and `Products.tsx` only imports the four other product commands. Not drivable from demo-web-ui today.

Scope: one fetcher + one toggle/button on the product detail modal. Smallest of the four gaps.

### 1G.3 demo-web-ui — Story 4.1 cancel-order button ✅ shipped 2026-05-15

No `cancelSalesOrder` command, no cancel button in `SalesOrders.tsx` or any masterdetail. The cancellation saga compensation IS observable on `/saga-console` (catalog includes `compensating` / `compensated` states), but the trigger has to come from outside this SPA (curl, `erp-web-ui`, or Swagger). Backend: `POST /api/sales-orders/{id}/cancel` (Story 4.1 ✅ in user-stories).

Scope: cancel button on sales-order detail (with reason dialog) + fetcher + 409-handling for orders past `goods_shipped`. Pattern mirrors `erp-web-ui`'s `SalesOrderDetail` Cancel button.

### 1G.4 demo-web-ui — Story 6.1 PO-approve action ✅ shipped 2026-05-15

Tom can raise a PR (`/purchase-requisitions`) and Olivia can post goods receipts / supplier invoices / payments. But the **PO-approve step** (`POST /api/purchase-orders/{id}/approve`) has no UI. The demo's scenario 7.1 silently relies on `northwood.purchasing.shortagePoAutoApprove=true` (the default) for shortage-driven POs — works in the scripted run. A manually-raised PR's PO lands at `'draft'` and parks indefinitely from this SPA's view.

Scope: Approve button on `/purchase-orders` detail (with reviewer + reason dialog) + fetcher + state-aware button (only shows for `'draft'` POs). Pattern mirrors `erp-web-ui`'s `PurchaseOrderDetail` Approve dialog.

### 1G.5 erp-web-ui — Story 7.1 scenario runner

Every individual mutation exists across the persona pages, but there's no scripted-scenario runner like `demo-web-ui`'s `ScenarioRunnerModal`. An operator has to navigate 5–6 pages in order (Sarah places order → Linda completes ops → Mike posts shipment → Olivia processes payment, etc.). The "watch all three sagas march in lockstep" framing is weaker than demo-web-ui's because no orchestrator pauses on saga state.

Scope: this is a bigger one-day slice — port the scenario runner shell from `demo-web-ui/src/scenarios/` to `erp-web-ui/src/scenarios/`, redefine step definitions in terms of `erp-web-ui` routes (e.g., navigate to `/sales-orders/new` then `/work-orders/{id}` then `/shipments/new`), and add a scenarios menu entry. Not story-blocking — every step works manually today.

### Suggested order

Cheapest to most-substantial within demo-web-ui: **1G.2 → 1G.3 → 1G.4 → 1G.1**. Each is independent — pick up whichever the next demo narrative needs.

1G.5 (erp-web-ui scenario runner) stays parked unless a public-facing audience wants the orchestrated-walkthrough experience from the operational SPA too.

### Why not now

The 2026-05-15 audit was prompted by a "can we demo every story in both SPAs?" question. The answer is "yes for observation; mostly yes for driving." The five gaps above are real but none is blocking the showcase narrative (`demo-web-ui`'s scenarios 3.1 / 5.2 / 7.1 work end-to-end via the scripted runners; `erp-web-ui` is the operational SPA where all the persona-specific forms already exist). Pull forward when:

- A manual demo (no scripted runner) wants to register a product / toggle make-vs-buy / cancel an order / manually approve a PO from `demo-web-ui` → 1G.1 / 1G.2 / 1G.3 / 1G.4.
- An operational-SPA-only audience wants the orchestrated walkthrough → 1G.5.

---

## 2. Polish on shipped slices

_§3 Slices A–E shipped 2026-05-06. The remaining items below are deferred with explicit notes — none are demo-blocking. Pick up when a use case actually needs them._

### 2.0 Revisit status-field representation across aggregates

Today the codebase mixes two patterns for aggregate status/state fields:

- **String constants** (the dominant pattern): `SalesOrder.SHIPPED`, `PurchaseOrder.DRAFT`, `SupplierInvoice.APPROVED`, `WorkOrder.RELEASED`, `StockReservation.RESERVED`, etc. — aggregates store `String status`, application services compare via the constant. ~16 aggregates.
- **Enum with `dbValue()` / `fromDb()`** (the typed pattern): `ProductType` (always was), `Customer.Status` (converted 2026-05-12). Aggregates store the enum directly; lookups parse via `fromDb()`. 2 places.

Cross-service status values (referenced by consumer services from events) live on `<service>-events` event classes as `public static final String STATUS_*` constants regardless of the producer-side representation — that's the locked rule and doesn't change.

Decision to revisit: pick one canonical producer-side pattern and migrate everything to it. Options:

- **(a) Migrate everything to enum-with-`dbValue()` (ProductType / Customer.Status style).** Type-safe state-machine guards inside aggregates (`if (status != SHIPPED) throw` becomes a compile-time check, not a string compare). Cost: every aggregate's persistence reads/writes go through `fromDb()` / `dbValue()`, view records expose the enum, JSON serialisation uses Jackson's default enum-name serializer (which would write `SHIPPED` instead of `shipped` unless we annotate `@JsonValue` on `dbValue()` — needs care for wire compatibility).
- **(b) Migrate everything to String-constants-only.** Drop `ProductType` enum + `Customer.Status` enum, fall back to `public static final String` blocks. Loses type safety on state-machine guards. Uniform with the existing dominant pattern.
- **(c) Leave the mixed pattern.** Document the rule: "use enum where the aggregate has non-trivial state-machine guards; use String constants where the field is mostly carried through." Pragmatic but easy to drift on.

Recommend (a) once the demo dataset is stable enough to verify the Jackson serialisation behaviour end-to-end on a real run — the wire-compatibility risk is real but tractable with a `@JsonValue dbValue()` annotation. Pull forward only when there's a concrete trigger (e.g. a status typo bug, or a new aggregate where the team wants type safety on first write); not a critical cleanup today.

### 2.1 Reporting follow-ups ✅ COMPLETE 2026-05-15

Active items shipped:
- **Financial dashboard "currently open" counts + AR / AP / inventory_value** — shipped 2026-05-12 (snapshot endpoint + per-day rollup worker 2026-05-15).

Parked indefinitely (documented out-of-scope on the respective Stories, not active backlog):
- `wip_value` on the dashboard — gated on a costing-method decision (LIFO / FIFO / weighted-avg). Schema column + DTO field + SPA tile are wired through; only the value derivation is parked. See `docs/user-stories.md` Story 2.6 *Out-of-scope*.
- `expected_material_available_date` / `planned_start_date` / `planned_end_date` on `production_planning_board` — need a planning module that emits scheduling events. Out-of-scope for the Northwood showcase. See `docs/user-stories.md` Story 2.2 *Out-of-scope*.

### 2.2 Work order `material_status` projection (was §3.5) ✅ shipped 2026-05-12

`WorkOrder.applyReservationOutcome(String)` added; `RawMaterialsReservedHandler` now projects the reservation outcome onto the WO aggregate alongside advancing the saga. Live smoke confirmed `material_status` flips from `reservation_pending` to `reserved` / `partially_reserved` / `shortage` as expected. See dev-done.md.

### 2.3 Soft-cancel WIP path (was §3.7)

**From:** §1.1 cancel-order slice 2026-05-06 (which shipped hard-cancel). Today a cancel arriving during `manufacturing_in_progress` immediately flips the WO to `cancelled` regardless of operation progress — WIP is written off. A more realistic ERP would let in-progress WOs finish then scrap the produced finished goods to a write-off bucket.

Scope:
- Decision: hard vs soft per WO based on a configurable threshold (e.g. operations-completed % > 50%)? Or a runtime flag on the cancel command?
- New events: `manufacturing.WorkOrderScrappedAfterCancel` (post-completion variant), inventory's WIP write-off.
- Finance hookup: when scrapped, post a write-off journal (Dr 5500 Write-off / Cr 1220 FG Inventory).

Wire into demo only after a clear narrative emerges — the showcase value of soft-cancel over hard-cancel is small and adds significant complexity.

### 2.4 CurrencyConverter depth (was §3.8)

Today the converter handles same-currency pass-through, inverse-rate fallback, and now `GET /api/exchange-rate?from=…&to=…&date=…` (shipped §3 Slice E 2026-05-06). Remaining depth:

- **Scheduled rate importer** — today rates are inserted manually for a few currency pairs. `@Scheduled` task fetching from an external feed would let the demo simulate a daily rate close.
- **Triangulation through a base currency** — out of scope today (schema doesn't model a base currency); listed in §5 below.

### 2.5 Application-layer + harness test coverage ✅ COMPLETE 2026-05-15

All sub-phases shipped (see dev-done.md for per-slice writeups):

- **Phase A** ✅ 2026-05-09 — 108 tests across 8 `*Service` classes; application-layer total ~190.
- **Phase B** ✅ 2026-05-10 — 39 tests across 10 inbox handlers; application-layer total ~230.
- **Phase C** ✅ 2026-05-12 — selective `Jdbc*` Testcontainers ITs (20 cases across 3 adapters: `JdbcPurchaseOrderPaymentProjectionIT`, `JdbcStockBalanceWriterIT`, `JdbcWorkOrderRepositoryMaterialStatusIT`).
- **§2.5 follow-up** ✅ 2026-05-13 — full baseline rebase: every changeset folded into (the renamed) `db/northwood_erp.sql`, `db/changelog/changes/` cleared, manufacturing IT's manual `ALTER TABLE` workaround dropped.
- **§2.5.1 Phase D — in-memory end-to-end saga harness** ✅ Slices A–G shipped 2026-05-10; Slice D follow-up (drive `ShipmentService` + `PaymentService` through the kits without event injection) shipped 2026-05-15. Harness now exercises every saga state machine end-to-end through real workers + bus dispatch with no Postgres / Kafka / Spring context. E2E tests: `OrderToCashHappyPathTest`, `OrderToCashFirstLegTest`, `CancelCompensationTest`, `MakeToOrderShortagePathTest`, `SubAssemblyRecursionTest`, `PurchaseToPayHappyPathTest`, `PurchaseToPayRejectionPathTest`, `SetPriorityCascadeTest` — 8/8 green.

### 2.6 Smoke-test gaps that need a running stack

Most of the original §2.6 list moved into §2.5.1's harness test targets (cancel-order, multi-receipt, deeper sub-assembly recursion, setPriority — see the inline cross-references in §2.5.1 follow-up #4). Only items that genuinely need the running Kafka + Postgres + 7-service stack remain:

- **Sales fulfilment saga cross-partition race fix** (2026-05-05) — concurrent execution against partitioned Kafka; not reproducible in a synchronous in-memory harness. Existing happy-path multi-line test passes the regression by virtue of `expectedWorkOrderCount` being set correctly, so the gap is "no targeted assertion that the race specifically can't recur." Capture for the next end-to-end run; would need a deliberately-induced race (e.g. duplicate WorkOrderCreated emission with a 100ms delay).

### 2.7 Saga states as `public static final String` constants ✅ shipped 2026-05-10

All three saga aggregates (`SalesOrderFulfilmentSaga`, `MakeToOrderSaga`, `PurchaseToPaySaga`) declare `public static final String STATE_NAME = "state_name"` per state; `ALL_STATES` and `TERMINAL_STATES` rebuilt from the constants. Literal usage replaced at every call site (factories, JDBC manager impls, worker shells, inbox handlers, tests) via static import. Wire format unchanged — DB CHECK constraints and event payloads stay as the canonical source; constants are a Java-side ergonomic that catches typos at compile time. See dev-done.md.

### 2.8 Pricing split — `materialsCost` (auto-rolled) vs `standardCost` (human) ✅ Slices A–D shipped

- **Slice A** ✅ 2026-05-07 — split events + APIs (`SalesPriceChanged` + `StandardCostChanged` replace `ProductPricingChanged`); sales projection consumes price.
- **Slice B** ✅ 2026-05-08 — finance `product_standard_cost` projection + `ShipmentPostedCogsHandler` reads it for COGS.
- **Slice C** ✅ 2026-05-08 — manufacturing-owned `materialsCost` rollup engine for purchased items (lives on `manufacturing.product_materials_cost`, preserves product-service producer-only invariant).
- **Slice D** ✅ 2026-05-08 — rollup extended to manufactured items via BoM walk + recursive parent recompute.
- **Slice E** (deferred — pull forward only if the cross-currency throw fires in the demo dataset) — wire `CurrencyConverter` into the BoM rollup so multi-currency component prices roll up to a target currency.

See dev-done.md for the full per-slice writeups + the locked architectural decisions (manufacturing-owned, null-propagation-with-reason, cross-service write avoidance).

### 2.9 SagaManager — single class per saga for state-machine truth ✅ shipped 2026-05-10

All three sagas (sales fulfilment, make-to-order, purchase-to-pay) now follow the "Saga manager class shape" architectural pattern captured in CLAUDE.md: one `<Flow>SagaManager` interface per saga in `application/saga/` + one `Jdbc<Flow>SagaManager` impl in `infrastructure/saga/` extending the abstract `SagaManager<S, P>` base. Worker shells reduced to thin `@Scheduled` glue (`manager.drain(...)`); inbox handlers parse payloads + delegate to `manager.applyXxx(...)` + gate side effects on the returned state. `SagaCompensationCompletionService` and `SagaDataIO` deleted. New `Jdbc<Flow>SagaManagerTest` per saga consolidates saga-state coverage; handler tests rebalanced to shell-smoke shape.

- **Slice A** ✅ 2026-05-09 → 2026-05-10 — `SalesOrderFulfilmentSagaManager` (3 follow-up passes: workerId-to-worker, slim manager, generic port type).
- **Slice B** ✅ 2026-05-10 — `MakeToOrderSagaManager` (8 methods, 15-test manager test).
- **Slice C** ✅ 2026-05-10 — `PurchaseToPaySagaManager` (6 methods, 16-test manager test).

See dev-done.md for the full per-slice writeups + the iterative-refinement passes on Slice A.

### 2.11 CGLIB-proxy + final-method audit ✅ shipped 2026-05-10

`SagaManager.drain()` and `AbstractInboxHandler.handles()` / `consumerName()` were `final` before this audit. Spring CGLIB-proxies any class that has `@Transactional` (or other AOP advice) on any method; Objenesis instantiates the proxy without invoking the constructor, so the proxy's instance fields are null. CGLIB cannot override final methods — they execute on the proxy with null fields and NPE on the first field access. Symptom for handlers was "Kafka listener routes message to `<topic>.dlt`" (caught by `ReorderPolicyChangedSeamIT`); for saga managers it would have been "every saga-worker poll tick NPEs on `this.tx`" (latent — no IT exercised the path).

Fix: dropped `final` from both, plus a regression guard `CglibProxyContractTest` (asserts no final instance methods on `SagaManager` / `AbstractInboxHandler`) and a positive `SagaManagerProxyTest` (boots a Spring context with a proxied `TestSagaManager` subclass and verifies `drain()` doesn't NPE through the proxy). Also added unit tests for the @Transactional service classes that had been gaps: `CustomerServiceTest` (11 tests), `ProductServiceTest` (20 tests), `OutboxPublisherTest` (4 tests). See dev-done.md.

### 2.12 `shared-infrastructure` → `shared` module split ✅ shipped 2026-05-11

Renamed the shared module from `shared-infrastructure` to `shared`; split its internals into `com.northwood.shared.application.*` (ports + abstract orchestration bases — 13 files moved), `com.northwood.shared.infrastructure.*` (JDBC + Kafka adapters + Spring auto-config + Liquibase + security — 7 files stayed), and `com.northwood.shared.api.audit` (AuditController — 1 file moved). 390 imports rewritten across 169 files; 5 files gained new cross-package imports for now-cross-package references that were formerly same-package. `shared-kernel` stayed separate (framework-free guarantee load-bearing for the wire-contract `*-events` jars). META-INF auto-config registration unchanged. Smoke: `mvn clean install -DskipTests` green in 14.5s, `mvn test` green in 26.5s. See dev-done.md.

---

### 2.10 `AbstractInboxHandler<P>` — common boilerplate base for inbox handlers ✅ shipped 2026-05-10

After §2.9 stabilised the handler shell shape, every inbox handler across the codebase (~73 total) followed the same 5-step skeleton: handles-check → dedupe → deserialise → apply → recordProcessed. Reporting already had `AbstractProjectionHandler<P>`; this slice lifted it to `shared-infrastructure/.../messaging/AbstractInboxHandler<P>` and migrated every concrete handler in all 6 services. Follow-up pass pushed `eventType` + `consumerName` into the base via constructor args, so subclasses no longer override `handles()` / `consumerName()`. The CGLIB-non-final-handle invariant lives in one place where new handlers can't forget it. See dev-done.md.

### 2.11 Nested-type ordering audit (follow-up from 2026-05-13 statics-on-top sweep) ✅ shipped 2026-05-14

Swept 17 nested types across 14 service / repository / aggregate classes (exceptions, command records, view records, lifecycle enums) up to the top of their class body. Records as the enclosing type intentionally skipped — their fields live in the header, so the "nested types above all fields" rule doesn't apply cleanly; the event-jar pattern (`EVENT_TYPE` / status constants → `eventType()` → nested record) stays uniform. See `dev-done.md`.

### 2.12 Role meta-annotations for `warehouse_manager`, `auditor`, `sysadmin`

The 2026-05-13 `@PreAuthorize` → `@RequireXxx` sweep created annotations under `shared/api/security/` for the 10 realm roles that gate actual endpoints today. The other 3 realm roles defined in `db/keycloak/northwood-realm.json` — `warehouse_manager` (force-release reservations, post stock adjustments), `auditor` (read-only everywhere), `sysadmin` (Keycloak realm admin only) — don't have annotations because no endpoint gates on them today. Scaffold matching `@RequireWarehouseManager` / `@RequireAuditor` / `@RequireSysadmin` when the first endpoint needs them.

### 2.13 Rename `BomActivated` → `ActiveBomChanged` ✅ shipped 2026-05-14

Renamed Java class + wire-format `EVENT_TYPE` together (`"product.BomActivated"` → `"product.ActiveBomChanged"`); handler renamed `BomActivatedHandler` → `ActiveBomChangedHandler`; `CONSUMER_NAME` unchanged. No external subscribers, so the wire break is internal-only. See `dev-done.md`.

### 2.14 `StockReservationService.reserveOneLine` — loop with backoff on lost reservation race ✅ shipped 2026-05-14

Bounded retry loop (3 attempts, 10ms / 40ms / 160ms exponential backoff) on `tryReserveOnHand` race-loss; each retry re-reads available stock and clamps the request against the new value. Sales + manufacturing paths both go through the same private method so they get the loop for free. See `dev-done.md`.

### 2.15 Sales fulfilment saga: route fully-reserved orders past `manufacturing_requested` ✅ shipped 2026-05-14

`applyStockReserved` now branches on `reservationStatus`: `RESERVED` shortcuts to `READY_TO_SHIP` (skipping manufacturing); `PARTIALLY_RESERVED` / `FAILED` keep the existing `STOCK_RESERVED → MANUFACTURING_REQUESTED` flow. Worker's `hasShortage == false` guard kept as a defensive "shouldn't happen" check. See dev-done.md.

### 2.16 Promote `Bom` to a real aggregate (retire `BomEditRepository`) ✅ shipped 2026-05-16

New `Bom` aggregate root in `manufacturing.domain` with `AGGREGATE_TYPE`, `BomId` identity VO, `Status` enum (`DRAFT` / `ACTIVE` / `INACTIVE`), internal `BomLine` entity, optimistic-concurrency via `row_version`, `pendingEvents`, and intent-named mutators (`addLine`, `removeLine`, `activate`). `activate` emits a new `manufacturing.BomActivated` event. `BomRepository` + `JdbcBomRepository` replace the row-shaped `BomEditRepository` + `JdbcBomEditRepository` (deleted). `BomService` (then `BomEditService`) reduced to a thin orchestrator over the aggregate + post-save cycle detection + materials-cost rollup. `BomCycleDetector` is application-orchestrated (post-save graph walk) rather than passed into the aggregate — documented in `Bom`'s class Javadoc. All five `*Repository`-without-an-aggregate offenders surfaced by the 2026-05-15 audit are now resolved. See `dev-done.md`.

### 2.17 Promote `SupplierProductPrice` + `ApprovedVendor` to real aggregates ✅ shipped 2026-05-16

`SupplierProductPrice` promoted to a real DDD aggregate (`AGGREGATE_TYPE`, `SupplierProductPriceId`, intent-named `updatePrice` mutator with no-op suppression, `pendingEvents` drained at `save()`). `SupplierProductPriceChanged.AGGREGATE_TYPE` constant retired from the event class. `ApprovedVendorRepository` deleted entirely; the approved-vendor list folded into the `Product` aggregate as a child collection — preferred option (b) over the originally-recommended option (a) once the code revealed that `Product` already emitted `ApprovedVendorListChanged` (so it semantically owned the data). See `dev-done.md`.

### 2.18 Tier 2 — rename read-only "aggregates" to `*QueryPort` ✅ shipped 2026-05-16

`manufacturing.RoutingRepository` → `RoutingQueryPort` (interface moved from `domain/` to `application/`); same for `purchasing.SupplierRepository` → `SupplierQueryPort`. Concrete `Jdbc*` adapters renamed. The `Routing` / `Supplier` read-model classes stay in `domain/` as plain data shapes. Test-harness in-memory counterparts also renamed. See `dev-done.md`.

### 2.19 Tier 3 — relax convention's third clause to cover event-less aggregates ✅ shipped 2026-05-16

Convention amended in `docs/conventions.md`: the *event-less write-once aggregate* (factory-only, no mutators, no events) is now a permitted variant of the `*Repository` rule, with `finance.JournalEntry` as the named exemplar (balance invariant carried by the DB trigger `enforce_journal_balance`; reversal is a new posted entry rather than a mutation). `JournalEntry.AGGREGATE_TYPE = "JournalEntry"` added for completeness. See `dev-done.md`.

### 2.20 Centralize aggregate-type constants in `<Service>AggregateTypes` files ✅ shipped 2026-05-16

Six new files (one per events-producing service): `ProductAggregateTypes`, `SalesAggregateTypes`, `InventoryAggregateTypes`, `ManufacturingAggregateTypes`, `PurchasingAggregateTypes`, `FinanceAggregateTypes` — each hosting all `AGGREGATE_TYPE` constants for that service's aggregates and sagas. Aggregate classes re-export from these files (`Product.AGGREGATE_TYPE = ProductAggregateTypes.PRODUCT`). Cross-service event classes (`ManufacturingDispatched`, `ProductMaterialsCostComputed`) and ~30 cross-service consumer-test sites reference the events-jar constants directly. `manufacturing-events` POM gains `sales-events` + `product-events` deps to support cross-service stamping references. See `dev-done.md`.

### 2.21 `AGGREGATE_TYPE` on `MakeToOrderSaga` + `PurchaseToPaySaga` for symmetry ✅ shipped 2026-05-16

`ManufacturingAggregateTypes.MAKE_TO_ORDER_SAGA = "MakeToOrderSaga"` and `PurchasingAggregateTypes.PURCHASE_TO_PAY_SAGA = "PurchaseToPaySaga"` added; the two saga classes re-export as `AGGREGATE_TYPE` to match `SalesOrderFulfilmentSaga`. Constants are unused today (neither saga stamps outbox rows under its own identity — `MakeToOrderSagaWorker` stamps under `WorkOrder.AGGREGATE_TYPE`; `PurchaseToPaySagaWorker` emits nothing). Declared as stable call sites for any future self-originated commands; rationale captured in each saga's javadoc. See `dev-done.md`.

### 2.22 Demote `StockItem` from aggregate to projection ✅ shipped 2026-05-17

`inventory.StockItem` was aggregate-shaped but emitted zero events. Demoted to projection-shaped ports per the deltas-vs-totals rule — see `dev-done.md`.

### 2.23 Apply `_card` suffix convention to all consumer-side Product projection tables

Codify the new schema-naming rule (cardinality-based; `_card` suffix; one table per (schema, aggregate) for 1:1 facets) — see `docs/conventions.md` → *Consumer-side denormalized tables*. Five rename sub-slices plus one consolidation. Each sub-slice: Liquibase rename changeset + baseline edit + projection/lookup class renames + handler injection-site updates + tests + dev-done entry. Land them in this order — mechanical renames first to prove the pattern, consolidation last.

| Sub | Schema | Today | After |
|---|---|---|---|
| 2.23.1 | sales        | `sales.product_pricing`                                                    | `sales.product_card` ✅ shipped 2026-05-18 |
| 2.23.2 | purchasing   | `purchasing.product_discontinued`                                          | `purchasing.product_card` ✅ shipped 2026-05-18 |
| 2.23.3 | finance      | `finance.product_accounting`                                               | `finance.product_card` ✅ shipped 2026-05-18 |
| 2.23.4 | reporting    | `reporting.product_standard_cost`                                          | `reporting.product_card` ✅ shipped 2026-05-18 |
| 2.23.5 | manufacturing | `product_replenishment` + `product_active_bom` + `product_materials_cost` | `manufacturing.product_card` (consolidated) ✅ shipped 2026-05-18 |

1:N children (`purchasing.product_approved_vendor`, `manufacturing.product_approved_vendor`) keep their structure — name already disambiguates from source `product.approved_vendor`. Existing `*_line_facts` tables (per-child-row caches) keep `_facts` — different shape from `_card`. Final smoke-boot after 2.23.5: fresh-volume reset + boot each affected service.

### 2.24 BOM service rename + flat view + recursive-CTE replacement of N+1 walk

Three related cleanups, packageable as one slice or three sub-slices (in this order):

**2.24.1 Rename `BomEditService` → `BomService` ✅ shipped 2026-05-18.** The `Edit` suffix adds no information — every command service edits its aggregate. Convention elsewhere is bare `<Aggregate>Service` (`CustomerService`, `SalesOrderService`). See `dev-done.md`.

**2.24.2 Rename `BomTreeService` → `BomViewService`, add `findFlatComponentsByProductId` ✅ shipped 2026-05-18.** Both methods orchestrate over `BomLookup`; differ only in the Java accumulator (tree → hierarchical `BomNode`, flat → list with cumulative-quantity multiplication). New `BomFlatComponentView` record + `GET /api/boms/by-product/{id}/flat` endpoint. See `dev-done.md`.

**2.24.3 Replace N+1 SQL walk with single recursive-CTE query in `BomLookup`.** The current `findActiveByFinishedProductId` issues one SQL per BOM in the hierarchy — accepted at demo depth (FG → SA → RM, ~3 levels) but suboptimal. Add a new method like `findActiveTreeRows(rootProductId)` that returns the entire flattened component graph in one Postgres recursive CTE join (`bom_header` + `bom_line` + `product_card.active_bom_header_id` for the sub-assembly recursion). Both `BomViewService` methods then call the new method and shape the result client-side (tree: rebuild hierarchy from `parent_bom_header_id` column; flat: pass through with quantity multiplication). Net: O(1) SQL per request regardless of tree depth.

**Sequencing**: 2.24.1 and 2.24.2 are pure renames + addition, safe in either order. 2.24.3 is the perf optimisation — can ship independently of 2.24.2, but landing 2.24.2 first means both view methods are in place before the optimisation.

---

## 3. Low priority — explicitly deferred (skip unless asked)

Do not pull these forward unless explicitly asked.

### 3.1 Multi-currency GL consolidation

User direction 2026-05-04. The architecture is in place (`Money` + `CurrencyConverter` + per-header rate snapshots), and the showcase runs single-currency end-to-end. Pull forward only when the audience asks about FX consolidation specifically.

### 3.2 Customer credit notes / refunds

User direction 2026-05-04. Forward AR (invoice → payment → settled) covers the showcase happy path. Reversal flows aren't on the critical path. Infrastructure (journal reversal, multi-allocation payments) is in place — adding a `CreditNote` aggregate + reversal-of-customer-invoice flow would be additive.

### 3.3 GST tax-account split

User direction 2026-05-04. Current journals fold tax-inclusive totals into COGS/Revenue and post cleanly. Splitting GST-input (1300 GRNI exists) / GST-output to dedicated accounts is accounting-correctness, not demo-blocking.

### 3.4 BOM authoring UI

User direction 2026-05-06 — explicitly low-priority during the §1 Security + UI slice. **Read-only tree view shipped in both SPAs** — `erp-web-ui/src/routes/manufacturing/Boms.tsx` (Linda) and `demo-web-ui/src/routes/Boms.tsx` (Emma) since 2026-05-13. What's still deferred is the authoring half: create draft, add/remove lines, drag-reorder, run cycle detection on save, flip draft → active. Backend authoring path is fully wired (`BomService` + 4 REST endpoints on `BomController`); the demo can use REST + curl until the editor UI lands. Pull forward if a planning-tool angle becomes part of the showcase narrative.

---

## 4. Out of scope (captured for completeness only)

These were considered and explicitly rejected; here so a future reader doesn't re-discover them and propose them as new work.

- **Inventory revaluation, average-cost rolling, FIFO/LIFO** — out of scope for the showcase. The `unitCost` from each receipt is taken at face value and never reconciled.
- **Reversal-of-reversal** — schema rejects (reversed entries are immutable). If business needs to "undo a reversal" it would post a new entry that re-applies the original's effect, not modify the existing chain.
- **Triangulation in CurrencyConverter** — schema doesn't model a base currency explicitly; out of scope until that decision is made.
- **`product_planning` / `product_pricing` sub-tables on product master** — locked 2026-05-04: stay with single columns going forward. Revisit only if a future facet has a multi-row shape (e.g. an approved-vendor list, which is genuinely a child collection).
