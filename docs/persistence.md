# Persistence — schema conventions, money, reference data, Liquibase rules

Detail companion to `CLAUDE.md`. Read when adding tables, seed data, Liquibase changesets, or touching the money/exchange-rate plumbing.

## Schema conventions

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
