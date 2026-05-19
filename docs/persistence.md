# Persistence — schema conventions, money, reference data, Liquibase rules

Detail companion to `CLAUDE.md`. Read when adding tables, seed data, Liquibase changesets, or touching the money/exchange-rate plumbing.

## Schema conventions

Quick-reference summary; the canonical exhaustive statement lives in `docs/conventions.md` → *PostgreSQL schema, table, and column naming*.

- **Singular table names.** `sales_order_line`, `product`, `customer`, `payment`, `outbox_message`. A row IS one of the named thing.
- **Master-detail parents take `_header` only when the child is `_line`.** `sales_order_header` + `sales_order_line`, `purchase_order_header` + `purchase_order_line`, `goods_receipt_header` + `goods_receipt_line`, `shipment_header` + `shipment_line`, `customer_invoice_header` + `customer_invoice_line`, `supplier_invoice_header` + `supplier_invoice_line`, `journal_entry_header` + `journal_entry_line`, `bom_header` + `bom_line`. When the child has a domain-specific name (no `_line` sibling) the parent stays bare singular: `work_order` + `work_order_material` / `work_order_operation`.
- **`finance.gl_account`** is the chart of accounts (one row per account: code, name, type ∈ {asset, liability, equity, revenue, expense}). `gl_` disambiguates from "customer account" / "bank account" usage.
- **Reporting views keep semantic singular names** — `sales_order_360_view`, `production_planning_board`, `material_shortage_view`, `available_to_promise_view`, `purchase_order_tracking_view`, `financial_dashboard_daily`.
- **Saga state tables singular.** `sales_order_fulfilment_saga`, `make_to_order_saga`, `purchase_to_pay_saga`.
- **FK columns end in `_id`** and reference the singular table. The line table's FK to its header is `<header>_id` (e.g. `sales_order_line.sales_order_header_id`).
- **PK on `_header` tables is `<header>_id`**; PK on bare-singular tables is `<table>_id`.

## Money & exchange rates

`shared.domain.Money` is **amount + currency** only — pure VO. Conversion is a service concern (rate, effective date, direction don't belong inside the value).

- **Rate snapshot lives on the *transaction header*.** `sales_order_header`, `purchase_order_header`, `customer_invoice_header`, `supplier_invoice_header`, `payment`, `journal_entry_header` each carry `(currency_code, exchange_rate, exchange_rate_captured_at)`. The `captured_at` is required for auditability.
- **Rates are sourced from `finance.exchange_rate(from_currency, to_currency, effective_date, rate)`** with UNIQUE on the trio. Transactions look up the row matching their date and stamp `(rate, exchange_rate_captured_at = now())` at posting time.
- **Don't sprinkle rate columns onto operational tables.** Catalogue monetary columns (`product.product.sales_price`, `product.product.standard_cost`) are amount-only; currency is the company's base. If a per-row currency is needed later, add `currency_code` next to the amount but **not** `exchange_rate` (that's an audit-bearing snapshot for the transaction that crystallises the conversion).
- **Cross-currency arithmetic on `Money` throws `IllegalArgumentException`.** Convert through a `CurrencyConverter` first.

## Reference data and seed UUIDs

The reference seed data lives in `db/northwood_erp.sql`, not in any Liquibase changeset. It uses well-known constant UUIDs (e.g. `00000000-0000-7000-8000-000000000001` for "Wooden Dining Table") so cross-context references work without joining across schemas. Follow the same convention when adding fixtures.

**Seed inserts in Liquibase changesets must spell out PK UUIDs explicitly** rather than relying on `DEFAULT shared.uuid_generate_v7()`. The default-expression path calls unqualified `gen_random_bytes()` (in pgcrypto, installed to `public`), but per-service connections use `search_path = <service>, shared` — `public` isn't on the path, so the default fails. The `psql` baseline load runs without that init-SQL so the default works there. Java repositories pass explicit UUIDs at runtime. Conclusion: explicit UUIDs in seed changesets.

**Don't copy the INSERT shape from `northwood_erp.sql` into a Liquibase changeset.** The baseline legitimately omits PK columns and relies on the `uuid_generate_v7()` default (it works under `psql`'s default search_path). The same INSERT in a Liquibase changeset breaks. The two execution paths have genuinely different default-expression behaviour — write Liquibase INSERTs from scratch with explicit PKs.

**Verify the PK column name against `northwood_erp.sql`** before writing a seed INSERT. Convention is `<table_name>_id` literally — `gl_account.gl_account_id`, not `account_id`. Don't truncate the table prefix on a guess; grep `CREATE TABLE finance.gl_account` in the baseline first.

**Smoke-boot at least one service against a fresh volume when shipping any new Liquibase changeset.** Unit tests don't run Liquibase, so neither defaulted-PK failures nor column-name typos surface until first boot of a fresh DB. `docker compose down -v ; docker compose up -d postgres ; mvn -pl <service> spring-boot:run` is a 30-second sanity check that catches both classes (and the other idempotency traps below).

**Liquibase changesets must be idempotent against the `northwood_erp.sql` baseline.** The baseline gets rebaked into the latest schema on every slice that ships a structural change; the corresponding changeset must no-op cleanly when applied to a fresh DB that already has the change via the baseline. This rule is invisible to unit tests and only surfaces on first boot of a fresh volume. Patterns that have bitten:

- **`CREATE TRIGGER` is not idempotent.** Use `CREATE OR REPLACE TRIGGER` (Postgres 14+) or `DO $$ BEGIN … EXCEPTION WHEN duplicate_object THEN NULL; END $$;`.
- **`ON CONFLICT` column lists must match a unique constraint exactly.** When the baseline extends a unique constraint, every seed `INSERT … ON CONFLICT` (in the baseline AND in any corresponding changeset) must be updated in the same slice. Postgres rejects subset-match at planning time.
- **`ADD CONSTRAINT` is not idempotent.** Prefix with `DROP CONSTRAINT IF EXISTS <name>`.
- **Use `IF [NOT] EXISTS` everywhere** — `CREATE TABLE`, `CREATE INDEX`, `ALTER TABLE … ADD COLUMN`, `ALTER TABLE IF EXISTS … RENAME`. No bare CREATE / ALTER / RENAME in changesets.

**`db/northwood_erp.sql` must be safe to run while already connected to the target database.** It's mounted into `/docker-entrypoint-initdb.d/`; postgres creates the DB from `POSTGRES_DB=northwood_erp` and runs init scripts while connected to it. Don't open with `DROP DATABASE / CREATE DATABASE / \connect` (Postgres rejects with "cannot drop the currently open database"). Standalone usage adapts: `createdb -U postgres northwood_erp; psql -U postgres -d northwood_erp -f northwood_erp.sql`.

**Don't start a `--` comment with a Liquibase keyword** (`changeset`, `rollback`, `precondition`, `liquibase`). Liquibase 5's formatted-SQL parser scans every `--` line for directive keywords and tries to parse `:author:id`, even with leading space. Rephrase to put the keyword anywhere except first-word-after-`--`.

## Aggregate status fields and schema CHECK constraints

Every aggregate root carries a `status` field even when only one value is actively produced today. The schema CHECK constraint on the corresponding column lists every value that *could* be written under a future workflow path, not just what Java writes now. This section catalogues the pattern, the three flavours of `'draft'` it uses, and the per-aggregate inventory of "what Java actually writes today vs what the schema allows."

### The schema-prep convention

The `status` column on every aggregate header table has a `CHECK (status IN (...))` constraint listing the full possibility space. The nested `Status` enum on the aggregate mirrors that list exactly — including values Java never writes. Those latent values get a one-line Javadoc tag:

```java
/** Schema-prep — not currently produced by Java. */
PENDING("pending"),
```

Three reasons the latent values exist:

1. **Trigger-written values.** The `maintain_allocation_totals` trigger on `customer_invoice_header` / `supplier_invoice_header` flips `status` to `partially_paid` or `paid` as payments allocate. Java never writes those — it only writes `posted` — but the enum has to know them so reads via `Status.fromDb(...)` don't throw.
2. **Future workflow paths.** `CustomerInvoice.Status.DRAFT` and `CANCELLED` are listed for the eventual "save draft invoice / cancel before posting" UI that doesn't exist yet. The schema CHECK is the forward-compat surface; Java will start producing them when the workflow lands.
3. **Hand-inserted DEFAULT rows.** `SupplierInvoice.MatchStatus.NOT_MATCHED` is the column's DB DEFAULT. Java's `record()` factory always writes a real outcome (`MATCHED` / `VARIANCE` / `FAILED`), but seed data or hand-INSERTed rows can land at `not_matched`. The enum has to read that back.

User direction 2026-05-19 (memory): *"don't propose dropping a single-valued status to tidy up; schema-prep for future cancel/reverse paths is deliberate. New aggregates get a status from day one."*

### The three flavours of `'draft'`

The `'draft'` literal appears in 9 separate aggregate CHECK constraints, and it means three substantially different things depending on aggregate:

- **(A) Load-bearing transient** — Java writes `draft` as step 1 of a two-phase save, then UPDATEs to the final state inside the same transaction. The row is never observed at `draft` from outside the transaction.

  Only case: **`finance.journal_entry_header`**. The `guard_journal_line_immutability` DB trigger rejects line INSERTs once the header is `posted`. So `JdbcJournalEntryRepository.save()` inserts the header at `draft`, INSERTs the lines while the trigger is happy, then UPDATEs the header to `posted`. The `draft` value is essential — without it the schema can't enforce "lines are immutable after posting."

- **(B) Schema-prep for future workflow** — Java never writes `draft` today; the value is reserved for a "save without committing" path that doesn't exist yet.

  Cases (all tagged `/** Schema-prep — not currently produced by Java [or trigger]. */`):
  - `sales.sales_order_header` — `SalesOrder.Status.DRAFT`
  - `inventory.goods_receipt_header` — `GoodsReceipt.Status.DRAFT`
  - `inventory.shipment_header` — `Shipment.Status.DRAFT`
  - `finance.customer_invoice_header` — `CustomerInvoice.Status.DRAFT`
  - `finance.supplier_invoice_header` — `SupplierInvoice.Status.DRAFT`
  - `finance.payment` — `Payment.Status.DRAFT`
  - `purchasing.purchase_requisition_header` — `PurchaseRequisition.Status.DRAFT`

  For these, the schema CHECK includes `draft` so a future UI can stage in-progress documents without breaking the constraint when production code starts writing it. The column DEFAULT is sometimes `draft` (e.g. `purchase_requisition_header`, `purchase_order_header`) and sometimes the active first state (e.g. `customer_invoice_header DEFAULT 'draft'` — but Java always writes `posted` via `create()`, bypassing the DEFAULT).

- **(C) Active first phase** — Java writes `draft` as the initial state on creation; an explicit user action transitions to the next state.

  Cases:
  - `manufacturing.bom_header` — `Bom.Status.DRAFT` → `ACTIVE` (BOM authoring: build the structure first, then activate it for production use).
  - `purchasing.purchase_order_header` — `PurchaseOrder.Status.DRAFT` → `SENT` (via `approve()` when `autoApprove=false`; the manual-PR path lands at `draft` and waits for a human approval. The shortage-driven auto-PR path skips `draft` and goes straight to `sent`.)

Implication: searching `WHERE status = 'draft'` in operational queries means different things in different schemas. The same literal isn't the same concept.

### Aggregate status inventory

Per-aggregate summary of what Java writes today (✓) vs what the schema CHECK allows but Java doesn't write (◯). Cross-reference for "is this safe to drop from the CHECK?" — almost always no, per the user direction above.

| Aggregate | Table | Java-written today (✓) | Schema-prep (◯) |
|---|---|---|---|
| `Product` | `product.product` | active, inactive, discontinued | — |
| `Customer` | `sales.customer` | active, inactive, blocked | — |
| `SalesOrder` | `sales.sales_order_header` | submitted, confirmed, in_fulfilment, ready_to_ship, partially_shipped, shipped, invoiced, cancelled, on_hold, completed | draft |
| `StockReservation` | `inventory.stock_reservation_header` + `_line` | reserved, partially_reserved, failed | pending, released, consumed |
| `GoodsReceipt` | `inventory.goods_receipt_header` | posted, reversed ‡ | draft |
| `Shipment` | `inventory.shipment_header` | posted, reversed ‡ | draft |
| `Bom` | `manufacturing.bom_header` | draft (C), active, inactive | — |
| `WorkOrder` | `manufacturing.work_order` | released, in_progress, completed, closed, cancelled | planned, material_check_pending, waiting_for_materials, partially_completed, blocked |
| `WorkOrder` (material_status) | `manufacturing.work_order` | reservation_pending, reserved, partially_reserved, shortage | not_checked, issued |
| `PurchaseRequisition` | `purchasing.purchase_requisition_header` | pending_approval, approved, rejected, converted | draft, cancelled |
| `PurchaseOrder` | `purchasing.purchase_order_header` | draft (C), sent, received | pending_approval, approved, partially_received, paid, closed, cancelled, rejected |
| `CustomerInvoice` | `finance.customer_invoice_header` | posted | draft, partially_paid †, paid †, cancelled |
| `SupplierInvoice` | `finance.supplier_invoice_header` | approved, three_way_match_failed, cancelled | draft, three_way_match_pending, three_way_match_passed, posted, partially_paid †, paid †, on_hold |
| `SupplierInvoice` (match_status) | `finance.supplier_invoice_header` | matched, variance, failed | not_matched |
| `Payment` | `finance.payment` | posted | draft, cancelled, reversed |
| `PaymentAllocation` | `finance.payment_allocation` | posted | reversed |
| `JournalEntry` | `finance.journal_entry_header` | draft (A), posted, reversed | — |

‡ — `reversed` reachable today only through a future reversal flow that doesn't have a UI; the value is allowed by CHECK and the enum can read it back, but no Java code path currently emits the transition.

† — written by the `maintain_allocation_totals` DB trigger as payments allocate, not by Java.

(A), (C) — `draft` flavour from the previous section; (A) = load-bearing transient, (C) = active first phase. All other `draft` entries in the table are (B) schema-prep.

### Read path

Every aggregate's persistence layer reads back via `Status.fromDb(rs.getString("status"))`. Because `fromDb` throws `IllegalArgumentException` on unknown values, *all* CHECK-allowed values must be enum members — including schema-prep — or the next time the trigger or a future workflow writes them, the read explodes. This is why we keep the schema-prep enum members rather than slimming down to "only what Java writes."

### Drop-status review checklist

If you find yourself wanting to drop a value from a status CHECK because "Java doesn't write it":

1. **Check the column DEFAULT** — if the DEFAULT is the value you want to drop, you need to update the DEFAULT in the same change.
2. **Check the triggers** — `maintain_allocation_totals`, `guard_journal_line_immutability`, and similar may write values Java doesn't.
3. **Check the seed in `db/northwood_erp.sql`** — hand-INSERTed seed rows can carry values that no factory produces.
4. **Confirm with the user before proposing the drop.** Per the standing memory, single-valued status fields are deliberate forward-compat surface.
