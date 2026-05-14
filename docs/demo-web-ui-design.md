# Northwood ERP — Demo Web UI design

A React layer purpose-built to make the **technical demo** watchable — Saga Console, event drawer, scenario runner. The original demo was `curl` commands + `psql` queries + seven Swagger tabs — fine for engineers, painful for an audience. This SPA replaces that surface with a single tab a presenter can drive end-to-end while the room *sees* what's happening.

This is the design doc for the technical demo SPA — code lives in `demo-web-ui/`. **The companion operational ERP SPA** (`erp-web-ui/`, port 5174, sibling BFF on 8089) targets a different audience entirely (business-user personas clicking through real ERP screens) and has its own design doc at `erp-web-ui-design.md`.

---

## Goals

1. **One tab to drive the demo from.** Persistent layout; presenter never needs to alt-tab.
2. **Saga progress is the headline.** State machines are the showpiece — the UI animates state transitions in real time.
3. **One-click scenarios.** "Run Demo 7" is a button, not eight typed `curl` commands.
4. **Persona narrative.** The audience watches roles change: Emma sets pricing, Sarah places an order, Mike receives goods, etc. The UI puts the persona in the header so the narrator's framing matches what's on screen.
5. **Looks good projected.** High-contrast dark theme, large type, no fine print.

## Non-goals

- A production CRM/ERP UI. No multi-tenant layouts, no role-based access, no internationalisation, no responsive mobile breakpoints. **Optimised for a 16:9 projector.**
- Authoring every form-driven workflow. The UI exposes the demo-relevant happy paths and exposes the rest as raw JSON (Swagger remains available for tinkering).
- Authentication. Single-user demo box.

---

## Architecture

```
┌─────────────────┐         ┌────────────────────┐         ┌──────────────────┐
│  React SPA      │  HTTP   │  BFF gateway       │  HTTP   │  7 Spring Boot   │
│  (Vite + TS)    │ ──────▶ │  (Spring Boot 4)   │ ──────▶ │  services        │
│                 │ ◀─SSE── │  /api/*  /events   │ ◀────── │  (8081 – 8087)   │
└─────────────────┘         └────────────────────┘         └──────────────────┘
```

**Why a BFF (Backend-for-Frontend), not direct CORS to seven services?**

- Single origin → no CORS dance per service, single TLS cert in any future deploy.
- Composition: the SO 360 view is *already* a reporting projection, but the **saga console** needs to merge sales + manufacturing + purchasing saga rows in one response — easier in a BFF than orchestrated client-side.
- **Server-Sent Events (SSE):** the BFF tails the seven services' outbox tables (or subscribes to Kafka topics under the `kafka` profile) and re-emits to the SPA as a single SSE stream. The SPA never has to know which service emitted what.
- Scenario orchestration: "Run Demo 7" is a single BFF endpoint that calls services in sequence; if one step's idempotency key collides, the BFF maps it to a clear UI message.

**What the BFF is NOT:** a domain layer. It composes, fans out, and translates — it never holds state. Module: `demo-web-ui-bff/` (Java, no DDD layering, just controllers + a Kafka/JDBC tail). Lives next to the services in the same Maven reactor.

> **Status (2026-05-05): the BFF is implemented.** Lives in `demo-web-ui-bff/`, runs on port 8080, depends only on `spring-boot-starter-web` + `actuator` (deliberately no `shared` module / DB / Kafka). Generic HTTP reverse proxy via JDK 21 `HttpClient`; aggregated saga read API + SSE composes from the three saga services' `/api/sagas` endpoints by polling at 1 s. Per-service SSE streams remain reachable on each service port for debugging but aren't proxied — SSE doesn't fit the buffered proxy shape and the aggregated stream is what the SPA uses anyway. Scenario orchestration stayed in the browser (Phase 5 hook) since moving it server-side has no demo benefit yet; the BFF is read-side + transparent-write only.

## Tech stack

| Concern | Choice | Why |
|---|---|---|
| Bundler / dev server | **Vite 5** | Fast HMR, no webpack config archaeology |
| Language | **TypeScript 5** | Catches event-shape mismatches against generated DTOs |
| UI framework | **React 18** | Familiar, the SPA is shallow enough that anything more exotic is overkill |
| Styling | **Tailwind CSS 3** + **shadcn/ui** | shadcn components are copy-pasted into the repo (no opaque package), Tailwind keeps the dark theme cohesive |
| Routing | **React Router 6** | Persona-scoped routes mirror the menu |
| Data fetching | **TanStack Query 5** | Polling fallback when SSE is unavailable, dedupes parallel fetches across components |
| Live updates | **EventSource (SSE)** via the BFF | Simpler than WebSockets, demo doesn't need bi-directional |
| Charts | **Recharts** | Small bundle, declarative, fine for the financial dashboard sparklines |
| State machine viz | **xyflow (React Flow)** | The saga state diagram is the centerpiece; xyflow handles node/edge layout and animation |
| Forms | **React Hook Form** + **Zod** | Type-safe payloads for the create/place/post endpoints |
| Icons | **Lucide React** | Pairs with shadcn |
| Type generation | **openapi-typescript** | Each service publishes `/v3/api-docs`; CI generates DTOs into `demo-web-ui/src/api/` |

No Redux. TanStack Query + React context for persona/scenario state covers everything.

---

## Information architecture (menu)

Two-axis navigation: the **left sidebar** is by persona (story-aligned with `user-stories.md`); the **top right** is cross-cutting (sagas, events, scenarios). Persona items are reorderable so a presenter can mirror the demo run-order from `demo-script.md`.

### Sidebar (persona-grouped)

```
┌─ Northwood ERP ────────────────┐
│                                │
│  📊 Dashboard                  │  ← financial dashboard from reporting
│                                │
│  👤 EMMA · Catalog             │
│     Products                   │
│     Pricing changes            │
│     Reorder policy             │
│     BOMs                       │
│                                │
│  👤 SARAH · Sales              │
│     Sales orders               │
│     Place new order            │
│     Order 360                  │
│                                │
│  👤 MIKE · Inventory           │
│     Stock items                │
│     Goods receipts             │
│     Shipments                  │
│     Available-to-promise       │
│                                │
│  👤 LINDA · Production         │
│     Production board           │
│     Work orders                │
│     Material shortages         │
│                                │
│  👤 TOM · Purchasing           │
│     Purchase requisitions      │
│     Purchase orders            │
│     PO tracking                │
│     Supplier prices            │
│                                │
│  👤 OLIVIA · AR/AP             │
│     Customer invoices          │
│     Supplier invoices          │
│     Pending review (3-way)     │
│     Payments                   │
│                                │
│  👤 DANIEL · Finance           │
│     Journal entries            │
│     Reverse a journal          │
│                                │
└────────────────────────────────┘
```

### Top bar (always visible)

```
┌────────────────────────────────────────────────────────────────────────┐
│  [Northwood logo]    Persona: Sarah ▾    🎬 Scenarios ▾    🔔 Events  │
└────────────────────────────────────────────────────────────────────────┘
```

- **Persona switcher** — purely presentational; tints the active persona's nav group, swaps the avatar/name shown in story-style banners. Doesn't restrict access.
- **🎬 Scenarios** — dropdown of pre-baked demos: "3.1 — Place stock-only order", "5.2 — Force material shortage", "7.1 — Big order touches everything". Each is one BFF call that orchestrates the multi-service flow.
- **🔔 Events** — opens the bottom event drawer (see § Event drawer).

### Cross-cutting routes (no sidebar entry, accessible via top bar / breadcrumbs / scenario completion redirects)

- **Saga Console** — the centerpiece. Three columns side-by-side, one per saga type. Each saga row visualised as a state-machine diagram with the current state pulsing. Clicking a row opens its full event timeline.
- **Event Log** — chronological stream of every event flowing through the bus. Searchable, filterable by aggregate type / event type / time window.
- **Scenario runner** — a modal that fires when a scenario is launched: shows step-by-step what's happening, pauses on human-in-the-loop steps with a visible "advance" button, summarises at the end.

---

## Layout

```
┌──────────────────────────────────────────────────────────────────────────────┐
│  Northwood ERP    Persona: Sarah ▾    🎬 Scenarios ▾    🔔 Events           │  ← top bar (56px)
├──────────────┬───────────────────────────────────────────────────────────────┤
│              │                                                               │
│  Sidebar     │    Main content                                              │
│  (240px)     │                                                               │
│              │    Page header: breadcrumb + page-level actions              │
│              │                                                               │
│              │    ┌──────────────────────────────────────────────┐         │
│              │    │  Primary view                                 │         │
│              │    │  (table, form, dashboard, saga diagram, …)    │         │
│              │    │                                              │         │
│              │    │                                              │         │
│              │    └──────────────────────────────────────────────┘         │
│              │                                                               │
│              │    ┌──────────────────────────────────────────────┐         │
│              │    │  Secondary panel (collapsible)                │         │
│              │    │  e.g. "events related to this entity"         │         │
│              │    └──────────────────────────────────────────────┘         │
│              │                                                               │
├──────────────┴───────────────────────────────────────────────────────────────┤
│  Event drawer (slides up from bottom, default collapsed to 32px tab)         │
│  ▼ live event stream — newest first, persona-coloured, click for details     │
└──────────────────────────────────────────────────────────────────────────────┘
```

**Responsive note:** target is 1920×1080 / 16:9 projector. Below 1280px the sidebar collapses to icons; below 1024px the layout is unsupported (banner: "open on a wider screen for the demo").

---

## Style guide

### Colour

Dark base. The app is going to be projected; high contrast wins.

```
--bg-base:       #0b0d12   /* near-black, warm tint */
--bg-elevated:   #151820   /* cards, sidebar */
--bg-hover:      #1d2230
--border:        #262a36
--text-primary:  #e8eaf0
--text-muted:    #8a93a6
--text-faint:    #565d6f
```

**Status palette** — used in saga state badges, table row tinting, event icons:

```
--state-pending:    #5a6480   /* slate, muted */
--state-active:     #f5b800   /* amber, the work-is-happening colour */
--state-success:    #36c08b   /* green */
--state-warn:       #ff9d4a   /* orange — partial / shortage / parked */
--state-error:      #ef5b6a   /* red — failed / rejected / cancelled */
--state-terminal:   #a78bfa   /* violet — completed (distinct from success-in-progress) */
```

**Persona accents** — each persona has a 1-colour accent used in the sidebar group header and the persona switcher chip; the accent never tints status, only identity.

| Persona | Accent | Hex |
|---|---|---|
| Emma | Pink | `#ec4899` |
| Sarah | Blue | `#3b82f6` |
| Mike | Teal | `#14b8a6` |
| Linda | Indigo | `#6366f1` |
| Tom | Amber | `#f59e0b` |
| Olivia | Emerald | `#10b981` |
| Daniel | Violet | `#8b5cf6` |

### Typography

```
font-family: "Inter", "Geist Sans", system-ui, sans-serif;
font-feature-settings: "ss01", "cv11";  /* Inter's tabular figures */
```

| Use | Size | Weight |
|---|---|---|
| Page title | 28px | 600 |
| Card / section heading | 18px | 600 |
| Body | 14px | 400 |
| Table cell | 13px | 400 |
| Status badge | 11px uppercase | 600 |
| Code / UUIDs / event types | 12px | 400 — `JetBrains Mono` |

UUIDs render as `00000000…000001` (truncated middle, mono font) with hover-tooltip showing the full value. Event types render as `inventory.GoodsReceived` in mono with the dotted prefix at 60% opacity so the type name pops.

### Motion

- **Saga state transitions** — when a saga row's `state` changes, the new state node in the diagram pulses (scale 1.0→1.08→1.0 over 600ms, amber→success colour shift). The connecting edge animates a moving dot from old → new state.
- **Event arrival** — a new event in the drawer slides in from the right with a 200ms ease-out; the persona-coloured left border catches the eye.
- **Table row update** — when a row's data changes (paid amount accumulates, status flips), the row briefly tints with the appropriate status colour at 20% opacity and fades back over 800ms.
- **Avoid spinners.** Long-running fetches show a thin progress bar at the top of the page (TanStack Query's `isFetching`).

Motion is reduced to "fade only" when `prefers-reduced-motion: reduce`.

---

## Key views

### Dashboard (default landing)

The financial dashboard projection from reporting, presented as the demo's "is everything OK?" overview.

```
┌──────────────────────────────────────────────────────────────────────┐
│  Today · AUD                                                         │
│                                                                      │
│  ┌─Sales revenue──┐  ┌─Cash received─┐  ┌─Open SOs──┐  ┌─Open POs─┐ │
│  │                │  │                │  │           │  │           │ │
│  │  $1,240.00     │  │     $0.00      │  │     3     │  │     1     │ │
│  │  ▁▃▄▆▇         │  │  ▁▁▂▂▃         │  │           │  │           │ │
│  └────────────────┘  └────────────────┘  └───────────┘  └───────────┘ │
│                                                                      │
│  ┌─COGS───────────┐  ┌─Cash paid─────┐  ┌─Open WOs──┐  ┌─Gross %──┐ │
│  │  $450.00       │  │     $0.00      │  │     2     │  │   63.7%   │ │
│  └────────────────┘  └────────────────┘  └───────────┘  └───────────┘ │
│                                                                      │
│  Last 7 days                                                         │
│  [stacked bar chart: revenue vs COGS per day]                       │
└──────────────────────────────────────────────────────────────────────┘
```

`inventory_value` / `wip_value` / `AR` / `AP` are shown as `—` with a hover-tooltip "needs daily snapshots; see user-stories.md 2.6".

### Sales orders → Order 360

Two-pane: list on the left (compact rows: order #, customer, status badge, total), detail on the right.

The detail view *is* the SO 360 reporting projection rendered as a horizontal timeline:

```
●━━━━━━━━●━━━━━━━━●━━━━━━━━○━━━━━━━━○━━━━━━━━○
placed  reserved  in mfg   shipped  invoiced  paid

placed       2026-05-05 10:14:32   ✓ SalesOrderPlaced
reserved     2026-05-05 10:14:33   ✓ StockReserved (status: reserved)
in mfg       2026-05-05 10:14:34   ✓ ManufacturingDispatched (1 accepted, 0 rejected)
                                   ✓ WorkOrderCreated → wo-abc
                                   ⏳ awaiting WorkOrderManufacturingCompleted
```

Each event is hoverable for the full payload. The list auto-updates as inbox handlers run.

### Saga Console

The crown jewel. Three columns of saga rows, one per saga type. Each row renders inline as a small state-machine diagram (xyflow) with the current state highlighted and pulsing.

```
┌─ Sales Order Fulfilment ─────┬─ Make-to-Order ──────────────┬─ Purchase-to-Pay ────┐
│                              │                              │                      │
│  SO-001  Sydney Home Living  │  WO-abc  Wooden Dining Table │  PO-001  Pinewood    │
│  ●━●━●━●━○━○━○━○━○            │  ●━●━●━○━○                   │  ●━●━○━○             │
│  started → mfg requested     │  started → reserved          │  started → wait_for  │
│                              │                              │                      │
│  SO-002  …                   │  WO-def  …                   │  PO-002  …           │
│  ●━●━●━○━○                   │  ●━●━○                       │  ●━○                 │
└──────────────────────────────┴──────────────────────────────┴──────────────────────┘
```

Click a row → modal with the full state diagram, current `saga.data` JSON pretty-printed, and the inbox events that have advanced it.

When an SSE event arrives advancing a saga, the corresponding row's progress dot animates to the next position. The audience *sees* the events flow through.

### Event drawer (bottom, collapsible)

Persistent dock at the bottom of every page. Default collapsed to a 32px tab showing the most recent event type and a count badge. Click to expand to ~200px:

```
┌──────────────────────────────────────────────────────────────────────────────┐
│  ▼ EVENT STREAM                                              [pause] [clear] │
│  ──────────────────────────────────────────────────────────────────────────  │
│  10:14:33.105  inventory  inventory.StockReserved          aggr=…7c1a82      │
│  10:14:33.090  inventory  inventory.RawMaterialsReserved   aggr=…3f9b21      │
│  10:14:33.041  manufact.  manufacturing.WorkOrderCreated   aggr=…11ee57      │
│  10:14:32.998  sales      sales.ManufacturingRequested     aggr=…02bb40      │
│  10:14:32.842  sales      sales.SalesOrderPlaced           aggr=…02bb40      │
└──────────────────────────────────────────────────────────────────────────────┘
```

Each row is persona-coloured by the *emitting* service. Click → modal with full envelope JSON + payload.

Filters: time window, service, event type substring. State persists across navigation so a presenter can scroll back to a moment.

### Scenario runner (modal)

Triggered by 🎬 Scenarios → "7.1 — Big order touches everything". The modal opens, dims the rest of the screen, and walks through the scenario step by step:

```
┌─ Scenario: 7.1 — Big order touches everything ──────────────────────────────┐
│                                                                              │
│  ✓ Step 1: Place sales order for 10 × FG-TABLE-001  (auto)                   │
│  ✓ Step 2: Watch sales saga reach manufacturing_requested  (auto)            │
│  ✓ Step 3: Make-to-order saga lands on raw_material_shortage  (auto)         │
│  ✓ Step 4: Purchase requisition + PO created  (auto)                         │
│                                                                              │
│  ▶ Step 5: Tom posts goods receipt for 35 × RM-LEG-001                       │
│            [defaults pre-filled]   [run step]                                │
│                                                                              │
│  ◯ Step 6: Make-to-order un-parks; complete operations                       │
│  ◯ Step 7: Post shipment for 10 × FG-TABLE-001                               │
│  ◯ Step 8: Olivia records supplier invoice + pays                            │
│  ◯ Step 9: Olivia receives customer payment                                  │
│                                                                              │
│  [pause auto] [skip to end]                          step 5 of 9             │
└──────────────────────────────────────────────────────────────────────────────┘
```

The scenario runner is the **demo-script.md walkthrough turned into a button**. Audience members new to ERP can keep up; engineers can pause-step-and-explain at any auto step.

Auto-steps are BFF calls. Human-in-the-loop steps surface a form pre-filled with sane defaults; the presenter can tweak before clicking "run step".

### Forms (place order, post receipt, etc.)

Forms are minimal and pre-filled with seed-data defaults. Example: "Place new order" defaults customer to Sydney Home Living, exposes a single line with FG-TABLE-001 / qty 1 / unit price from the live product, and a "submit" button. Validation via Zod; errors surfaced inline beneath the field with a short message and the offending event type if applicable.

### Lookups (UUIDs)

Every UUID is rendered as a click-to-copy chip with hover-truncation. UUIDs that resolve to known entities (products, customers, suppliers, work orders) become clickable links to that entity's detail view. The BFF maintains a small in-memory id→type→name cache it populates from each service's `GET /api/<entity>/{id}` so the SPA never needs to know which service owns what.

---

## Real-time / event-stream design

Two SSE endpoints on the BFF:

| Endpoint | Payload |
|---|---|
| `GET /events` | every event from every service, normalised to `EventEnvelope` |
| `GET /events/saga` | saga-row updates; one message per `*_saga.UPDATE` |

The BFF tails events from one of two sources:

- **dev profile** — polls each service's `outbox_message` table (read-only, follower role) at 500ms intervals, emits new rows since last `sequence_number`.
- **kafka profile** — subscribes to all six `*.events` topics with a unique `group.id` per BFF instance (`bff-<random-suffix>`) so the BFF doesn't compete with services' real consumer groups.

The SPA opens both EventSource connections on mount; TanStack Query subscribes to the saga stream and invalidates the relevant query keys so saga-list / saga-detail views re-fetch automatically.

**Backpressure / catch-up:** SSE drops messages on slow consumers. To survive a paused presenter resuming a 30-minute-stale tab, both endpoints accept a `?since=<sequence_number>` query parameter; the SPA persists the last seen sequence in `sessionStorage` and resumes from there.

---

## Build / run

```powershell
# build the SPA into static assets
cd demo-web-ui && npm install && npm run build  # outputs demo-web-ui/dist/

# build the BFF
mvn -pl demo-web-ui-bff install -DskipTests

# run the BFF (serves the SPA from / and the API from /api/*)
mvn -pl demo-web-ui-bff spring-boot:run         # 8080
```

Open `http://localhost:8080`. The BFF needs the seven services running on their usual ports (8081–8087) — same prereq as the demo today.

Dev mode: `cd demo-web-ui && npm run dev` for HMR; the dev server proxies `/api/*` to the BFF on 8080 and `/events*` likewise.

---

## Out of scope (intentionally)

- **Edit screens for everything.** The UI exposes the demo-relevant create/post endpoints; bulk edit, filters beyond the basics, and admin tooling stay out. Swagger remains available.
- **Authentication.** Everyone is "the presenter".
- **Multi-tenancy.** One company, one customer, one supplier (per seed).
- **Offline mode / PWA.** Demo requires the services to be running anyway.
- **Tests beyond smoke.** The SPA gets a tiny Playwright suite that asserts the scenario runner can complete 3.1 and 7.1 end-to-end. No fine-grained component tests — the UI changes too freely during demo iteration.
- **Internationalisation.** English only.
- **Theming.** Dark mode only. (A "light" mode would need restyling that doesn't help the audience.)

---

## Phasing

A demo UI doesn't need to ship in one slice. Suggested split:

1. **Phase 1 — read-only shell** (1–2 days). Vite app, layout, sidebar, top bar, dashboard reading from the financial-dashboard projection. SSE events drawer reading from a stub. Validates the look.
2. **Phase 2 — read-only depth** (2 days). Sales orders list + 360, PO tracking, production board, ATP, material shortages. All read-only via existing reporting endpoints.
3. **Phase 3 — saga console** (2 days). Three-column live saga diagram with xyflow. SSE saga channel. The headline feature.
4. **Phase 4 — write paths** (2 days). Forms for place order, goods receipt, shipment, supplier invoice, payment. Form validation against generated DTOs.
5. **Phase 5 — scenario runner** (1 day). Modal + BFF orchestration endpoints for Demos 3.1, 5.2, 7.1.
6. **Phase 6 — polish** (1 day). Motion, persona accents, reduced-motion fallback, projector-friendly tweaks discovered when actually projecting.

~9 working days for a fully-featured demo UI; phases 1–3 alone (~5 days) deliver the bulk of the audience experience and could ship as v0.1 if time-boxed.
