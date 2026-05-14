# Northwood ERP — Operational Web UI design

The operational SPA for the seven business-user personas (Sarah / Mike / Linda / Tom / Olivia / Daniel / Emma). Targets a different audience and produces a different aesthetic than `demo-web-ui` — that one is the *technical* showcase (Saga Console, event drawer, scenario runner); this one is the *application* (forms, lists, action buttons).

The two SPAs ship in independent projects (`erp-web-ui/` + `erp-web-ui-bff/`, sibling to `demo-web-ui/` + `demo-web-ui-bff/`) so they can deploy independently and carry different security policies. Slice A's Keycloak / OAuth2 wiring lands only in `erp-web-ui-bff`; the technical demo stays anonymous.

This doc captures the design rationale + visual identity + layout templates. The implementation lives under `erp-web-ui/src/`.

---

## Goals

1. **Look like a real ERP.** Operators familiar with SAP Fiori, Oracle NetSuite, Microsoft Dynamics, or Odoo should recognise the page shapes within seconds. Conservative palette, dense lists, clear status colors, tabbed detail pages. Not a consumer SaaS aesthetic.
2. **One operational flow per persona.** Each of the seven personas has a primary screen they live in, plus the secondary screens their commands need. Decision: module-grouped sidebar (not persona-grouped) — operators in real ERPs think *"I'm working in Sales"*, not *"I am Sarah"*.
3. **Make the security demo legible.** Action buttons that need a manager role surface that requirement (tooltip in dev builds; gated visibility once Slice A/B land). The persona switcher (Slice D) flips between users mid-demo so the audience sees `403 — needs role: finance_manager` happen live.
4. **Keep the demo's architecture story visible.** Saga progress on the Sales Order detail (5 dots: reserved → made → shipped → invoiced → paid) and the projection-heavy lists are concrete CQRS demonstrations without surfacing implementation detail.
5. **Stay decoupled from the technical demo.** Component library copied (not shared); Tailwind config copied; visual identity deliberately distinct (light vs dark) so the family resemblance is in *typography and structure*, not color.

---

## Visual reference points

The closest commercial ERPs aesthetically:

- **SAP Fiori 3 / Horizon** — corporate blue, cool grays, dense information layout, modules in a left rail, breadcrumbs everywhere. Probably the closest match for "looks like a real ERP."
- **Oracle NetSuite / Redwood** — flatter, more whitespace, color-coded status pills, tabbed detail pages. A bit friendlier.
- **Odoo (Enterprise)** — cleanest of the three, trades information density for readability. Good middle ground.

**Anchor:** Fiori-with-Odoo-restraint — corporate-feeling but not 1990s SAP-grim. Conservative palette, dense tables, clear status colors, tabbed detail pages.

---

## Tech stack

| Concern | Choice | Why |
|---|---|---|
| Bundler / dev server | **Vite 6** | Fast HMR, no webpack config. Same as `demo-web-ui`. |
| Language | **TypeScript 5** | Catches event-shape mismatches against backend DTOs |
| UI framework | **React 18** | Familiar; this SPA is shallow enough that anything more exotic is overkill |
| Styling | **Tailwind 4** + **shadcn/ui patterns** | Copy-paste components, no runtime dep. Theme tokens in `index.css` under `@theme`. |
| Routing | **React Router 6** | Module-grouped routes mirror the sidebar |
| Data fetching | **TanStack Query 5** | Optimistic UI, automatic retry on 4xx/5xx, cache invalidation per mutation |
| Live updates | **SSE** via the BFF (`/api/events`) | Notification bell consumes; no other live-update needs in C0–C5. |
| Forms | Bare React + a thin `<TextInput>` / `<Select>` etc. layer | React Hook Form / Zod is overkill for the form depth this SPA needs (mostly 2–6 fields per dialog) |
| Icons | **Lucide React** | Outline style, 16px in buttons, 20px in nav. No filled / colorful icons. |

No Redux. TanStack Query + a small `<ToastProvider>` context covers everything.

---

## Visual system

### Palette

Tailwind 4 `@theme` tokens in `src/index.css`:

```
--color-bg-base:       #f5f7fa     page background
--color-bg-surface:    #ffffff     cards, tables, forms
--color-bg-subtle:     #f9fafb     alt rows, hover backgrounds
--color-bg-sidebar:    #1e293b     slate-800; left nav rail
--color-bg-sidebar-hover:  #334155
--color-bg-sidebar-active: #1e3a8a

--color-border-default: #e5e7eb    subtle 1px dividers
--color-border-strong:  #d1d5db    focused inputs, hovered table rows
--color-border-focus:   #1e3a8a    primary on focus ring

--color-brand-primary:        #1e3a8a   deep corporate blue
--color-brand-primary-hover:  #1e40af
--color-brand-primary-soft:   #dbeafe   tinted background for primary pills/banners

--color-text-primary:    #111827   gray-900
--color-text-secondary:  #4b5563   gray-600 — labels, secondary lines
--color-text-muted:      #6b7280   gray-500 — table meta, hints
--color-text-faint:      #9ca3af   gray-400 — placeholder, disabled

--color-status-info:     #2563eb   blue-600 — submitted, draft
--color-status-success:  #16a34a   green-600 — completed, paid, received
--color-status-warn:     #d97706   amber-600 — pending, partial
--color-status-error:    #dc2626   red-600 — cancelled, rejected, failed
--color-status-neutral:  #6b7280   gray-500 — closed, archived
```

Each status color has a paired `*-soft` background for pills/banners.

**Rule:** the same domain status in two different modules MUST render with the same tone. A `cancelled` order and a `cancelled` PO look identical. The `statusForOrder(status)` helper in `<StatusPill>` centralises the mapping.

### Typography

- **Inter** (system fallback) — clean, modern, well-suited to information density.
- **14px body, 13px in tables.** Tighter than consumer apps; ERP users want lots of info on screen.
- **`tabular-nums`** on every monetary column and ID column so vertical stacks of numbers align.
- **No display fonts**, no weight gymnastics. h1 = 20px semibold, h2 = 14px semibold, that's the whole hierarchy.

### Density

- Table rows ~36px tall.
- Form rows ~40px (input height 36px + 4px label gap).
- AppBar 56px.
- Sidebar 240px wide.
- Page header 80px (breadcrumb + title + action row).

### Borders, not shadows

A 1px border defines a card. Shadows only on overlays (modals, dropdowns, toasts). Looks corporate, not consumery.

### Iconography

Lucide outline icons, 16px in buttons, 20px in navigation, 14px in metadata badges.

---

## Layout: top-level shell

Three regions: AppBar (top, fixed), Sidebar (left, fixed), main content (right, scrolling).

```
┌──────────────────────────────────────────────────────────────────────┐
│ N Northwood ERP    [Search…]              🔔 3   Sarah Chen ▾        │  ← AppBar (h-14)
├────────────────┬─────────────────────────────────────────────────────┤
│ Home           │ Home › Sales › Sales Orders                         │  ← Breadcrumb
│ Sales       ▾  │                                                      │
│  · Customers   │ Sales Orders          [+ New Order] [⚙] [⤓ Export]  │  ← PageHeader
│  · Sales Orders│ ─────────────────────────────────────────────────   │
│  · 360 View    │ ▾ Status: All  ▾ Customer: Any  [🔍 Search]         │  ← Filter bar
│ Purchasing  ▸  │                                                      │
│ Inventory   ▸  │ ┌────────┬──────────┬──────────┬────────┬─────────┐ │
│ Manufactur. ▾  │ │ Order# │ Customer │ Status   │ Total  │ Updated │ │  ← DataGrid
│  · Work Orders │ ├────────┼──────────┼──────────┼────────┼─────────┤ │
│  · Production  │ │ SO-12  │ Acme Co  │ Shipped  │ $1,240 │ 12m ago │ │
│  · BOMs        │ │ SO-11  │ Beta Ltd │ Reserved │ $890   │ 1h ago  │ │
│ Finance     ▾  │ │ ...                                                │ │
│  · ...         │ └────────┴──────────┴──────────┴────────┴─────────┘ │
│ Reporting   ▸  │                                                      │
│ System      ▸  │ Showing 1–25 of 127        ‹ 1 2 3 4 5 ›            │
└────────────────┴─────────────────────────────────────────────────────┘
```

**`<AppBar>`** — Brand mark + global search (cross-module, future) + notifications bell with unread badge + user menu (anonymous "Sarah Chen / Sales Clerk" today; real once Slice A's auth lands). 1px bottom border, no shadow.

**`<Sidebar>`** — Module-grouped navigation rail. Modules are collapsible; the active route's module auto-expands. Active sub-page gets a brand-blue background. Icon + label per row. Slate-800 dark sidebar contrasts the white-on-light-gray content area.

**`<Breadcrumb>`** — Every page has a Home › Module › Page › Detail trail at the top of the content area. Earlier crumbs are clickable; the current page is plain text.

**`<PageHeader>`** — Title + optional description + right-aligned action button group. Primary action is filled blue; secondary actions are outlined.

---

## Layout: detail page (tabbed)

```
┌──────────────────────────────────────────────────────────────────────┐
│ AppBar                                                                │
├──────────────────────────────────────────────────────────────────────┤
│ Sidebar │ Home › Sales › Sales Orders › SO-12                         │
│         │                                                             │
│         │ SO-12  [Submitted]                  [Cancel Order]          │  ← title + status + actions
│         │ Acme Corp Pty Ltd — 2026-05-06                              │
│         │                                                             │
│         │ ┌─Overview─┬─Lines (3)─┬─Shipments─┬─Invoices─┬─Audit─────┐│
│         │ │                                                          ││
│         │ │  Customer       Acme Corp        Order Date 2026-05-06   ││
│         │ │  Currency       AUD              Total      $1,240.00    ││
│         │ │                                                          ││
│         │ │  Saga progress  ●─●─●─○─○                                ││
│         │ │                Reserved Made Shipped Invoiced Paid       ││
│         │ │                                                          ││
│         │ │  Cancel Order ← only visible if user has sales_manager   ││
│         │ └──────────────────────────────────────────────────────────┘│
└─────────┴──────────────────────────────────────────────────────────────┘
```

**`<DetailLayout>`** — Tabbed shell for any aggregate root. Tabs (Overview / Lines / Shipments / Invoices / Audit etc.) are kept stable across detail pages so users learn the pattern once. Optional badge on each tab (e.g. line count). Active tab gets brand-blue underline + text.

**Saga progress dots** on a Sales Order detail — opt-in CQRS storytelling. Real ERPs hide that complexity; we surface it because the architecture *is* the demo. Acceptable to remove later if it feels too "demo-y" for production.

**Audit tab** — placeholder today, lit up by Slice D (audit log endpoint reading from the outbox + actor stamping from Slice B).

---

## Layout: form page

```
┌──────────────────────────────────────────────────────────────────────┐
│ New Sales Order                                  [Cancel] [Save] [💾]│
│ ──────────────────────────────────────────────────────────────────── │
│ ┌─ Header ─────────────────────────────────────────────────────────┐ │
│ │  Customer *      [Acme Corp Pty Ltd ▾]                            │ │
│ │  Order Date *    [2026-05-06]    Currency *   [AUD ▾]             │ │
│ │  Reference                                                        │ │
│ └──────────────────────────────────────────────────────────────────┘ │
│ ┌─ Lines ──────────────────────────────────────────────────────────┐ │
│ │  # │ Product     │ Qty │ UoM │ Unit Price │ Line Total │  ⋮      │ │
│ │  1 │ FG-TBL-001 ▾│ 2   │ ea  │ $620.00    │ $1,240.00  │  ✕      │ │
│ │  2 │ ...                                                           │ │
│ │                                                  [+ Add line]    │ │
│ └──────────────────────────────────────────────────────────────────┘ │
│                                       Subtotal $1,240.00             │
│                                       Total    $1,240.00             │
└──────────────────────────────────────────────────────────────────────┘
```

**`<FormSection>`** — labeled card grouping related form fields. Optional title + description. Children laid out in a CSS grid (default 2 columns on md+). The `fullWidth` prop on `<Field>` spans across all columns (textareas, line tables).

**`<Field>`** — wraps an input with label, required marker, hint or error text. Children pass-through.

**Validation** — field-level error text below the input (red), red border on the field itself; form-level errors in a banner above the action buttons. No toast for form validation; the toast system is for backend responses (success on save, error on 4xx/5xx).

---

## Foundation primitives

The full set ships in `src/components/ui/`:

| Component | Purpose |
|---|---|
| `<DataGrid>` | Generic table — column descriptors, numeric-right-align, fixed-width cols, click-to-navigate rows, skeleton loading state, empty state slot. Used on every list page. |
| `<DetailLayout>` | Detail-page shell with tabs (described above). |
| `<FormSection>` + `<Field>` + `<ReadOnlyField>` | Card-grouped form fields. |
| `<PageHeader>` | Title + breadcrumb + action button group. |
| `<ActionButton>` | Primary / secondary / danger / ghost variants. `requiresRole?: string` prop is plumbed through as a tooltip today; Slice B will hook it into role-aware visibility. |
| `<StatusPill>` | Pill with 1px border + tinted bg + tone-colored text. Includes `statusForOrder(status)` helper that maps domain statuses → tones consistently across modules. |
| `<ConfirmDialog>` | Modal for destructive / sensitive actions. Has an inline `body` slot so callers can add reason inputs (e.g. cancel reason, approver name + reason) without building separate dialogs. |
| `<Toast>` + `<ToastProvider>` + `useToast()` | Lightweight transient notifications, bottom-right. 3 tones (success / warn / error); success and warn auto-dismiss after 5s, errors persist until dismissed. |
| `<Breadcrumb>` | Clickable trail with chevron separators. |
| `<TextInput>` / `<NumberInput>` / `<DateInput>` / `<TextArea>` / `<Select>` | Form inputs with consistent height (h-9), border, focus ring. |

Cross-cutting principles every primitive observes:

- **Solid white surfaces** (`bg-bg-surface`).
- **1px borders** (`border-border-default`).
- **Brand-blue focus ring** on inputs + buttons.
- **Tabular-nums** on monetary + ID columns.
- **Disabled states** at 50% opacity with `cursor-not-allowed`.

---

## Navigation tree

The sidebar's complete module structure:

```
Home (dashboard / quick-action launcher)

Sales
  · Customers
  · Sales Orders          (+ New)
  · 360 View

Purchasing
  · Suppliers
  · Purchase Requisitions (+ New)
  · Purchase Orders       (+ New from PR / approve queue)
  · Supplier Prices

Inventory
  · Stock Balances
  · Reservations
  · Goods Receipts        (+ New)
  · Shipments             (+ New)
  · Stock Movements       (read-only audit)

Manufacturing
  · Work Orders           (+ list, detail with operation actions)
  · Production Board
  · BOMs                  (low priority — defer)

Finance
  · Supplier Invoices
  · Pending Review        (3-way-match queue)
  · Customer Invoices
  · Payments              (+ new, AP and AR)
  · Journal Entries       (read + reverse)
  · Exchange Rate
  · AR / AP Dashboard

Reporting
  · Available-to-Promise
  · Material Shortage
  · Purchase Order Tracking
  · Financial Dashboard

System (sysadmin only)
  · Users
  · Audit Log
```

**Naming consistency** (worth holding the line on): each menu label matches its page title. `Sales Orders` (not `Orders`), `Purchase Requisitions` (not `Requisitions`). The exception: `Inventory › Reservations` reads fine in-context and disambiguation is unambiguous within the module.

**Demo Saga Console / Event Drawer / Scenario Runner** stay in `demo-web-ui/`, deliberately. Operators in an ERP don't watch sagas; they place orders. Mixing the two surfaces would dilute both.

---

## Endpoint → role mapping (the demo-interesting bits)

The role table is duplicated in `dev-todo.md §1` as the source of truth for Slice B's `@PreAuthorize` annotations. Summarised here for SPA reference:

| Endpoint | Required role | Where it surfaces |
|---|---|---|
| `POST /api/sales-cmd/sales-orders/{id}/cancel` | `sales_manager` | SalesOrderDetail page header |
| `POST /api/work-orders-cmd/{id}/operations/{seq}/skip` | `production_supervisor` | WorkOrderDetail operations tab |
| `POST /api/work-orders-cmd/{id}/priority` | `production_planner`+ | WorkOrderDetail page header |
| `POST /api/purchase-orders-cmd/{id}/approve` | `purchasing_manager` | PurchaseOrderDetail header (when status=draft) |
| `POST /api/supplier-product-prices` | `purchasing_manager` | SupplierPrices authoring form |
| `POST /api/supplier-invoices/{id}/manual-approve` | `finance_manager` | PendingReview queue + SupplierInvoiceDetail header |
| `POST /api/supplier-invoices/{id}/reject` | `finance_manager` | PendingReview queue + SupplierInvoiceDetail header |
| `POST /api/journal-entries/{id}/reverse` | `finance_manager` | JournalEntries lookup-by-id section |
| `POST /api/journal-entries/reverse-by-source` | `finance_manager` | JournalEntries reverse-by-source form |
| `PUT /api/products-cmd/{id}/pricing` etc. | `catalog_manager` | ProductDetail header dialogs |
| All `GET /api/...` | any authenticated (incl. `auditor`) | n/a |

Each `<ActionButton>` for a manager-gated action has `requiresRole={...}` set today; the prop is a tooltip until Slice B turns it into actual hide-or-disable behaviour.

---

## Concrete divergence from the demo SPA

For reference — what changes vs the technical demo's aesthetic:

| `demo-web-ui` (technical) | `erp-web-ui` (operational) |
|---|---|
| Dark theme — slate-900 surface | Light theme — white surface, slate sidebar |
| Vibrant cards, gradients | Flat surfaces, 1px borders |
| Saga console + event drawer prominent | Hidden — not operator-facing |
| Tech persona labels ("Sarah", "Linda") | User menu shows real name + role |
| Sidebar grouped by persona | Sidebar grouped by module |
| Read-only, demo-y | Action-heavy, form-heavy |
| Routes mix dashboards + forms | Detail pages tabbed; lists separate from forms |
| Generous whitespace | Dense, information-rich |
| Scenario runner for end-to-end stories | No scenario runner; the operator drives manually |

---

## Phasing

Slice C — operational ERP UI — was split into six sub-slices. All shipped 2026-05-06.

| Slice | Scope | Status |
|---|---|---|
| **C0** | Scaffolding (Vite project + BFF module) + ERP shell + foundation primitives + Sales Orders list as look-and-feel reference | ✅ |
| **C1** | Manufacturing — Work Orders list, detail with operation Complete/Skip + Set Priority, Production Board kanban. Backend gained `GET /api/work-orders/{id}` (was POST-only). | ✅ |
| **C2** | Finance — Pending Review queue with approve/reject, Supplier Invoice detail, Journal Entries (lookup-by-id with reverse + reverse-by-source). Foundation primitives `<DetailLayout>`, `<FormSection>`, `<ConfirmDialog>` shipped here. | ✅ |
| **C3** | Purchasing + Product authoring — Products list + detail with pricing/reorder/make-vs-buy/discontinue, Purchase Orders list + approve, Supplier Prices authoring. | ✅ |
| **C4** | Sales cancel + cross-cutting polish — SalesOrderDetail with cancel button, `apiPut<T>` helper. | ✅ |
| **C5** | Polish picks — Exchange Rate lookup form, generic `<Toast>` system, Customer Invoices list, Payments list. Backend: new `findAll()` on `CustomerInvoiceRepository` + `PaymentRepository` + new list endpoints. | ✅ |

**Open ERP-UI placeholders** (each a standalone follow-up if needed):
- Mike's inventory screens — backend has the endpoints; forms not built.
- Purchase requisition creation form — non-trivial (lines, suggested suppliers, source-WO link).
- Standalone dashboards (`/sales-orders/360`, `/ar-ap`).
- BOM editor — explicitly low-priority per dev-todo §3.4.
- System screens — gated on Slice A (auth) and Slice D (audit log).

---

## Run locally

The fetch chain is `5174 → 8089 → backend service → postgres`. Bring up the layers from the bottom:

```powershell
docker compose up -d postgres                    # 5432
mvn -pl reporting-service spring-boot:run        # 8087 — minimum for most pages
mvn -pl erp-web-ui-bff spring-boot:run           # 8089 — easy to forget
cd erp-web-ui ; npm install ; npm run dev        # 5174
```

For the full demo flow (cancel cascades, saga progression, GL posting), bring up all 7 services + Kafka under `SPRING_PROFILES_ACTIVE=kafka` per service. See `demo-script.md` for the runbook.

The two SPAs and their two BFFs are independent — running both at once is fine. Demo runs on 5173/8080, ERP on 5174/8089.

---

## Open design questions worth revisiting

These didn't gate the C0–C5 ship but are worth picking up if/when relevant:

- **Saga progress dots on detail pages** — included today as architecture storytelling. Acceptable to remove once the security demo carries the storyline (post-Slice D).
- **Persona switcher** — Slice D will add a dev-only header dropdown to flip between seeded personas without retyping Keycloak credentials. Not a production feature.
- **Optimistic-locking conflict UI** — backend uses `version` columns but no SPA flow today triggers a concurrent-edit scenario. A toast (`"This record was modified by someone else — refresh"`) is the likely shape, but not built.
- **Bulk operations / mass-edit** — out of scope for the showcase; real ERPs have these.
