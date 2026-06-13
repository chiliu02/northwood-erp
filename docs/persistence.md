# Persistence — schema conventions, money, reference data, Liquibase rules

Detail companion to `CLAUDE.md`. Read when adding tables, seed data, Liquibase changesets, or touching the money/exchange-rate plumbing.

## Schema conventions

Quick-reference summary; the canonical exhaustive statement lives in `docs/conventions.md` → *PostgreSQL schema, table, and column naming*.

- **Singular table names.** `sales_order_line`, `product`, `customer`, `payment`, `outbox_message`. A row IS one of the named thing.
- **Master-detail parents take `_header` only when the child is `_line`.** `sales_order_header` + `sales_order_line`, `purchase_order_header` + `purchase_order_line`, `goods_receipt_header` + `goods_receipt_line`, `shipment_header` + `shipment_line`, `customer_invoice_header` + `customer_invoice_line`, `supplier_invoice_header` + `supplier_invoice_line`, `journal_entry_header` + `journal_entry_line`, `bom_header` + `bom_line`. When the child has a domain-specific name (no `_line` sibling) the parent stays bare singular: `work_order` + `work_order_material` / `work_order_operation`.
- **`finance.gl_account`** is the chart of accounts (one row per account: code, name, type ∈ {asset, liability, equity, revenue, expense}). `gl_` disambiguates from "customer account" / "bank account" usage.
- **Reporting views keep semantic singular names** — `sales_order_360_view`, `production_planning_board`, `material_shortage_view`, `available_to_promise_view`, `purchase_order_tracking_view`, `financial_dashboard_daily`.
- **Saga state tables singular.** `sales_order_fulfilment_saga`, `work_order_saga`, `purchase_to_pay_saga`.
- **FK columns end in `_id`** and reference the singular table. The line table's FK to its header is `<header>_id` (e.g. `sales_order_line.sales_order_header_id`).
- **PK on `_header` tables is `<header>_id`**; PK on bare-singular tables is `<table>_id`.

## Money & exchange rates

`shared.domain.Money` is **amount + currency** only — pure VO. Conversion is a service concern (rate, effective date, direction don't belong inside the value).

- **Rate snapshot lives on the *transaction header*.** `sales_order_header`, `purchase_order_header`, `customer_invoice_header`, `supplier_invoice_header`, `payment`, `journal_entry_header` each carry `(currency_code, exchange_rate, exchange_rate_captured_at)`. The `captured_at` is required for auditability.
- **Rates are sourced from `finance.exchange_rate(from_currency, to_currency, effective_date, rate)`** with UNIQUE on the trio. Transactions look up the row matching their date and stamp `(rate, exchange_rate_captured_at = now())` at posting time.
- **Don't sprinkle rate columns onto operational tables.** Catalogue monetary columns (`product.product.sales_price`, `product.product.standard_cost`) are amount-only; currency is the company's base. If a per-row currency is needed later, add `currency_code` next to the amount but **not** `exchange_rate` (that's an audit-bearing snapshot for the transaction that crystallises the conversion).
- **Cross-currency arithmetic on `Money` throws `IllegalArgumentException`.** Convert through a `CurrencyConverter` first.

## Reference data and seed UUIDs

The reference seed data lives in `config/postgresql/northwood_erp_seed.sql`, the data-side companion to the `config/postgresql/northwood_erp.sql` schema baseline (split out 2026-05-20). It uses well-known constant UUIDs (e.g. `00000000-0000-7000-8000-000000000001` for "Wooden Dining Table") so cross-context references work without joining across schemas — the registry of those UUIDs is in the header block of `northwood_erp_seed.sql`. Follow the same convention when adding fixtures.

The split lets a developer pick at infra-up time:
- `docker compose up -d` — schema only, empty database (populate at runtime via events).
- `docker compose -f docker-compose.yml -f docker-compose.seed.yml up -d` — schema + the demo fixtures.

Both files are run under `/docker-entrypoint-initdb.d/` exactly once on first boot of a fresh volume (`01-…` then `02-…`). To re-seed an already-up database, `psql -U postgres -d northwood_erp -f config/postgresql/northwood_erp_seed.sql` is safe to re-run thanks to per-INSERT `ON CONFLICT DO NOTHING`.

**Seed inserts in Liquibase changesets must spell out PK UUIDs explicitly** rather than relying on `DEFAULT shared.uuid_generate_v7()`. The default-expression path calls unqualified `gen_random_bytes()` (in pgcrypto, installed to `public`), but per-service connections use `search_path = <service>, shared` — `public` isn't on the path, so the default fails. The `psql` baseline load runs without that init-SQL so the default works there. Java repositories pass explicit UUIDs at runtime. Conclusion: explicit UUIDs in seed changesets.

**Don't copy the INSERT shape from `northwood_erp_seed.sql` into a Liquibase changeset.** The seed file legitimately omits PK columns and relies on the `uuid_generate_v7()` default (it works under `psql`'s default search_path). The same INSERT in a Liquibase changeset breaks. The two execution paths have genuinely different default-expression behaviour — write Liquibase INSERTs from scratch with explicit PKs.

**Verify the PK column name against `northwood_erp.sql`** before writing a seed INSERT. Convention is `<table_name>_id` literally — `gl_account.gl_account_id`, not `account_id`. Don't truncate the table prefix on a guess; grep `CREATE TABLE finance.gl_account` in the schema baseline first.

**Smoke-boot at least one service against a fresh volume when shipping any new Liquibase changeset.** Unit tests don't run Liquibase, so neither defaulted-PK failures nor column-name typos surface until first boot of a fresh DB. `docker compose down -v ; docker compose up -d postgres ; mvn -pl <service> spring-boot:run` is a 30-second sanity check that catches both classes (and the other idempotency traps below).

**Liquibase changesets must be idempotent against the `northwood_erp.sql` baseline.** The baseline gets rebaked into the latest schema on every slice that ships a structural change; the corresponding changeset must no-op cleanly when applied to a fresh DB that already has the change via the baseline. This rule is invisible to unit tests and only surfaces on first boot of a fresh volume. Patterns that have bitten:

- **`CREATE TRIGGER` is not idempotent.** Use `CREATE OR REPLACE TRIGGER` (Postgres 14+) or `DO $$ BEGIN … EXCEPTION WHEN duplicate_object THEN NULL; END $$;`.
- **`ON CONFLICT` column lists must match a unique constraint exactly.** When the schema baseline extends a unique constraint, every seed `INSERT … ON CONFLICT` (in `config/postgresql/northwood_erp_seed.sql` AND in any corresponding changeset) must be updated in the same slice. Postgres rejects subset-match at planning time.
- **`ADD CONSTRAINT` is not idempotent.** Prefix with `DROP CONSTRAINT IF EXISTS <name>`.
- **Use `IF [NOT] EXISTS` everywhere** — `CREATE TABLE`, `CREATE INDEX`, `ALTER TABLE … ADD COLUMN`, `ALTER TABLE IF EXISTS … RENAME`. No bare CREATE / ALTER / RENAME in changesets.

**`config/postgresql/northwood_erp.sql` must be safe to run while already connected to the target database.** It's mounted into `/docker-entrypoint-initdb.d/`; postgres creates the DB from `POSTGRES_DB=northwood_erp` and runs init scripts while connected to it. Don't open with `DROP DATABASE / CREATE DATABASE / \connect` (Postgres rejects with "cannot drop the currently open database"). Standalone usage adapts: `createdb -U postgres northwood_erp; psql -U postgres -d northwood_erp -f northwood_erp.sql`.

**Don't start a `--` comment with a Liquibase keyword** (`changeset`, `rollback`, `precondition`, `liquibase`). Liquibase 5's formatted-SQL parser scans every `--` line for directive keywords and tries to parse `:author:id`, even with leading space. Rephrase to put the keyword anywhere except first-word-after-`--`.

## Aggregate status fields and schema CHECK constraints

Every aggregate root carries a `status` field even when only one value is actively produced today, but the nested `Status` enum and the column `CHECK (status IN (...))` track the **produced** set — what some Java path, DB trigger, or SQL-literal projection actually writes — not a forward-prep possibility space. This section catalogues that pattern, the values kept despite having no Java producer, and the three flavours of `'draft'` that survive.

### Enum mirrors the produced CHECK set

The enum on each aggregate mirrors its column CHECK exactly, and both list only what gets written. Forward-prep values that no path produced — intermediate approval states, partial-progress states, unbuilt cancel/reverse paths — were retired from both the enum and the CHECK, and column DEFAULTs were repointed to a produced value.

A value is **kept despite having no Java producer** only when a runtime path depends on it:

1. **Trigger / SQL-literal producers.** The `maintain_allocation_totals` trigger on `customer_invoice_header` / `supplier_invoice_header` flips `status` to `partially_paid` / `paid` as payments allocate; `JdbcPurchaseOrderPaymentProjection` writes PO `paid`; the same trigger's reversal branch reads `payment_allocation` `reversed`. Java never writes these, but the enum has to read them back so `Status.fromCode(...)` doesn't throw.
2. **Domain-guard terminals.** `PurchaseRequisition.Status.REJECTED` and `WorkOrder.Status.CLOSED` / `CANCELLED` are named by `Assert.state` guards (and tests) that block operating on a finished aggregate; `SupplierInvoice.MatchStatus.VARIANCE` is a 3-way-match outcome the aggregate + tests accept. No happy path emits them, but removing them would drop a real invariant or an accepted input.

(Earlier project direction kept *all* single-valued / forward-prep statuses as deliberate schema-prep; that was reversed for this showcase — values with no producer are retired unless they fall under one of the two cases above.)

### The three flavours of `'draft'`

After the schema-prep retirement `'draft'` survives in two aggregates, meaning something different in each:

- **(A) Load-bearing transient** — Java writes `draft` as step 1 of a two-phase save, then UPDATEs to the final state inside the same transaction. The row is never observed at `draft` from outside the transaction.

  Only case: **`finance.journal_entry_header`**. The `guard_journal_line_immutability` DB trigger rejects line INSERTs once the header is `posted`. So `JdbcJournalEntryRepository.save()` inserts the header at `draft`, INSERTs the lines while the trigger is happy, then UPDATEs the header to `posted`. The `draft` value is essential — without it the schema can't enforce "lines are immutable after posting."

- **(C) Active first phase** — Java writes `draft` as the initial state on creation; an explicit user action transitions to the next state.

  - `manufacturing.bom_header` — `Bom.Status.DRAFT` → `ACTIVE` (BOM authoring: build the structure first, then activate it for production use).
  - `purchasing.purchase_order_header` — `PurchaseOrder.Status.DRAFT` → `SENT` (via `approve()` when `autoApprove=false`; the manual-PR path lands at `draft` and waits for a human approval. The shortage-driven auto-PR path skips `draft` and goes straight to `sent`.)

The former **(B) schema-prep `draft`** — on sales orders, goods receipts, shipments, customer/supplier invoices, payments, and purchase requisitions — was retired; those columns now DEFAULT to their produced first state (`posted` / `approved`).

Implication: searching `WHERE status = 'draft'` still means different things in (A) vs (C) — a transient pre-posting journal-entry header vs a real awaiting-action BOM or PO.

### Aggregate status inventory

Per-aggregate summary of what Java writes today (✓) vs what the schema CHECK allows but Java doesn't write (◯). Cross-reference for "is this safe to drop from the CHECK?" — almost always no, per the user direction above.

| Aggregate | Table | Status values |
|---|---|---|
| `Product` | `product.product` | active, inactive, discontinued |
| `Customer` | `sales.customer` | active, inactive, blocked |
| `SalesOrder` | `sales.sales_order_header` | submitted, in_fulfilment, partially_shipped, shipped, completed, cancelled, rejected |
| `StockReservation` | `inventory.stock_reservation_header` + `_line` | reserved, partially_reserved, failed, released |
| `GoodsReceipt` | `inventory.goods_receipt_header` | posted |
| `Shipment` | `inventory.shipment_header` | posted |
| `StockAdjustment` | `inventory.stock_adjustment` | posted |
| `Bom` | `manufacturing.bom_header` | draft (C), active, inactive |
| `WorkOrder` | `manufacturing.work_order` | released, in_progress, completed, closed §, cancelled § |
| `WorkOrder` (material_status) | `manufacturing.work_order` | reservation_pending, reserved, partially_reserved, shortage |
| `PurchaseRequisition` | `purchasing.purchase_requisition_header` | approved, rejected §, converted |
| `PurchaseOrder` | `purchasing.purchase_order_header` | draft (C), sent, partially_received, received, paid †, cancelled |
| `CustomerInvoice` | `finance.customer_invoice_header` | posted, partially_paid †, paid † |
| `SupplierInvoice` | `finance.supplier_invoice_header` | three_way_match_failed, approved, partially_paid †, paid †, cancelled |
| `SupplierInvoice` (match_status) | `finance.supplier_invoice_header` | matched, variance §, failed |
| `Payment` | `finance.payment` | posted |
| `PaymentAllocation` | `finance.payment_allocation` | posted, reversed § |
| `JournalEntry` | `finance.journal_entry_header` | draft (A), posted, reversed |

† — written by a DB trigger or a SQL-literal projection, not by aggregate Java: invoice `partially_paid` / `paid` come from the `maintain_allocation_totals` trigger; PO `paid` is written by `JdbcPurchaseOrderPaymentProjection`.

§ — no Java producer, but retained because a runtime path depends on the value: the `WorkOrder` / `PurchaseRequisition` terminals back `Assert.state` guards (and a test) that block operating on a finished aggregate; `match_status = variance` is a 3-way-match outcome the aggregate + tests accept; `payment_allocation.reversed` is the `maintain_allocation_totals` trigger's reversal target. `JournalEntry` `reversed` is genuinely produced (the reversal flow is shipped).

(A), (C) — `draft` flavour from the section above; (A) = load-bearing transient, (C) = active first phase.

### Read path

Every aggregate's persistence layer reads back via `Status.fromCode(rs.getString("status"))`, which throws `IllegalArgumentException` on an unknown value. The enum and the column CHECK now list the same produced set, so a fresh database never feeds `fromCode` a value it doesn't know. **Caveat:** the live AWS demo DB still carries the older, looser CHECKs (the baseline edit isn't ALTERed onto it), so a hand-inserted or pre-existing row at a now-removed value would throw on read — but new rows can't reach those values, so this only bites legacy data.

### Adding a value back to a status CHECK

Forward-prep values were retired (see the inventory above). When a future workflow starts producing one again, add it to **both** the enum and the column CHECK together, and:

1. **Set / repoint the column DEFAULT** if the new value should be the initial state.
2. **Check the triggers** — `maintain_allocation_totals`, `guard_journal_line_immutability`, and similar write values Java doesn't.
3. **Check the seed in `config/postgresql/northwood_erp_seed.sql`** — hand-INSERTed seed rows can carry values that no factory produces.
