# dev-done.md

Shipped slices, chronological. Lifted from `dev-todo.md` so it stays focused on what's still open.

When a slice ships: move its block from `dev-todo.md` to here, drop transient context (e.g. "trigger when X happens"), keep the outcome + smoke-test result + meaningful follow-ups (which themselves move to `dev-todo.md` if still relevant).

---

## 2026-05-18 — §2.24.2: `BomTreeService` → `BomViewService` + add `findFlatComponentsByProductId`

Generalised the read-side BOM service from one shape (tree) to a hub for multiple shapes, anticipating the flat-list view. Both methods now orchestrate the SAME recursive walk over `BomLookup` and differ only in the Java accumulator — tree mode assembles hierarchical `BomNode` children, flat mode appends to a list with cumulative-quantity multiplication.

- **`BomTreeService.java` → `BomViewService.java`** via `git mv`; class renamed inside. Constructor + `BomLookup` injection unchanged.
- **New method `findFlatComponentsByProductId(rootProductId)`** returns `List<BomFlatComponentView>`. Each entry carries the running `cumulativeQuantityPerFinishedUnit = ancestorCumulative × (quantityPerFinishedUnit × (1 + scrapFactorPercent/100))`, plus depth (1 for direct children) so UIs can indent without rebuilding the tree. Same component appearing on multiple paths surfaces as multiple entries — callers can group/sum by `componentProductId` if a deduped roll-up is needed (out-of-scope for the showcase).
- **New `application/dto/BomFlatComponentView.java`** record — wire format for the new endpoint.
- **`BomController`**: `treeService` field renamed to `viewService`; added `GET /api/boms/by-product/{finishedProductId}/flat` returning `List<BomFlatComponentView>`. Tree endpoint unchanged. No tree-endpoint behaviour drift.
- **Cycle protection unchanged** — the existing visited-set guard in the tree walk applies to the flat walk too.
- **N+1 perf note** — the new `findFlatComponentsByProductId` inherits the same per-BOM-issues-one-SQL pattern as the tree walk. Accepted at demo depth (~3 levels); scheduled for replacement under §2.24.3 (single recursive-CTE in `BomLookup`).
- **Doc cascade**: `docs/dev-todo.md` (§2.24.2 marked shipped). No other docs reference `BomTreeService` outside historical `dev-done.md` entries (left untouched).

**Smoke**: `mvn install -DskipTests` clean; `mvn -pl manufacturing-service test` → 138/138 green; `mvn -pl test-harness test` → 8/8 green.

**Follow-up**: §2.24.3 (recursive-CTE replacement of the N+1 walk) still queued.

## 2026-05-18 — §2.24.1: `BomEditService` → `BomService`

Drop the redundant `Edit` suffix to match the codebase's bare-`<Aggregate>Service` convention (`CustomerService`, `SalesOrderService`, `PurchaseOrderService`). Pure rename, no behavioural change.

- **`BomEditService.java` → `BomService.java`** + **`BomEditServiceTest.java` → `BomServiceTest.java`** via `git mv` (preserves history); class names updated inside.
- **`BomController`**: import + field type updated. Field `editService` → `service` to match the `WorkOrderController` pattern (single-word `service` for the controller's primary service when one is the obvious main one); `treeService` left for now (will rebrand to `viewService` under §2.24.2). All 4 call sites in the controller updated.
- **`ManufacturingTestKit`** (test-harness): import + field type + field name `bomEditService` → `bomService` + constructor call.
- **Javadoc refs refreshed** across `ProductDiscontinuedHandler`, `DiscontinuedProductLookup`, `JdbcBomLookup` (2 places), `Bom`'s class doc (rewritten to drop the specific class-name reference since the historical narrative was already changing).
- **Liquibase changeset comment** `2026-05-15-add-product-replenishment-discontinued-at.sql` updated (hash drift accepted per fresh-volume-reset posture).
- **Baseline `db/northwood_erp.sql`** comment updated.
- **Doc cascade**: `CLAUDE.md` (no change — doesn't name the class), `docs/conventions.md` (§2.16 summary), `docs/build-status.md`, `docs/design-notes.md`, `docs/domain-driven design.html`, `docs/user-stories.md` (3 spots), `docs/dev-todo.md` (§2.16 history + §3.4 deferred entry). Historical `dev-done.md` entries left as-is — they correctly reference `BomEditService` as the name at the time.

**Smoke**: `mvn install -DskipTests` clean; `mvn -pl manufacturing-service test` → 138/138 green; `mvn -pl test-harness test` → 8/8 green.

**Follow-ups**: §2.24.2 (`BomTreeService` → `BomViewService` + flat-view method) and §2.24.3 (recursive-CTE replacement of N+1 walk) still queued.

## 2026-05-18 — §2.23.5: manufacturing consolidation — `product_replenishment` + `product_active_bom` + `product_materials_cost` → `manufacturing.product_card`

Fifth and final `_card` migration, and the heaviest by code-touch: three 1:1 product-projection tables collapsed into one. The cardinality rule had been pointing at this since slice 1.

- **Baseline** `db/northwood_erp.sql`: dropped three table definitions + their triggers; replaced with one `manufacturing.product_card (product_id PK, is_purchased, is_manufactured, discontinued_at, active_bom_header_id, materials_cost, currency_code, materials_cost_reason, materials_cost_captured_at, updated_at)`. The `captured_at` and `reason` columns from the former `product_materials_cost` get prefixed (`materials_cost_captured_at`, `materials_cost_reason`) to disambiguate from any future column on a different facet. Seed INSERT retargeted; cross-reference comment in the sales section updated.
- **Historical Liquibase changeset** `2026-05-15-add-product-replenishment-discontinued-at.sql` guarded with `ALTER TABLE IF EXISTS manufacturing.product_replenishment ADD COLUMN IF NOT EXISTS discontinued_at` — runs against legacy DBs where `product_replenishment` still exists, no-ops on fresh boot where the table never existed.
- **New consolidation changeset** `2026-05-18-consolidate-product-card.sql` (5 sub-changesets): create `product_card` (idempotent; `CREATE TABLE IF NOT EXISTS` + trigger guarded with `duplicate_object` exception swallow); migrate data from each predecessor with `--preconditions onFail:MARK_RAN` so they skip on fresh-volume boots where the predecessors never existed; drop the three old tables. `splitStatements:false` on the create changeset for the DO-block trigger guard.
- **Java**: Kept all three projection interfaces (`ProductReplenishmentProjection`, `ProductActiveBomProjection`, `ProductMaterialsCostProjection`) and their Jdbc impls — pragmatic scope choice to keep this slice mechanical. The three projections now all target the consolidated `manufacturing.product_card` table on their respective column subsets. ON CONFLICT clauses preserve unrelated columns (replenishment write doesn't touch `active_bom_header_id`, etc.) so the per-event ownership of columns stays correct.
- **`JdbcProductMaterialsCostProjection.findByProductId`** filters on `materials_cost_captured_at IS NOT NULL` — future-proofs against rows seeded by other facets before the materials-cost rollup has ever fired (semantic: "this product has a computed materials cost"). Read shape unchanged at the Java level (`MaterialsCost` record carries the same 5 fields).
- **`JdbcDiscontinuedProductLookup` + `JdbcBomLookup`** SQL retargeted.
- **Javadocs** updated across 9 production files in manufacturing-service (the three projection interfaces, three handlers, `MaterialsCostRollupService`, `BomLookup`, `DiscontinuedProductLookup`). Plus event-class Javadocs in `ProductMaterialsCostComputed` and `ActiveBomChanged` (product-events jar).
- **`application-kafka.yml`** comment refreshed.
- **Doc cascade**: `CLAUDE.md` deltas-vs-totals example list, `docs/conventions.md` (3 spots — the historical example mentions are now framed as "no current offenders"), `docs/design-notes.md`, `docs/user-stories.md`, `docs/demo-script.md`, `docs/event-flow.html` (multiple including the materials-cost-rollup desc's column-name refresh to `materials_cost_reason` / `materials_cost_captured_at`), `docs/domain-driven design.html`, `demo-web-ui Products.tsx` (3 spots), `erp-web-ui ProductDetail.tsx`. Historical `dev-done.md` left untouched.

**Smoke**: `mvn install -DskipTests` clean; `mvn -pl manufacturing-service test` → 138/138 green; `mvn -pl test-harness test` → 8/8 green. **Fresh-volume Liquibase smoke (all 5 §2.23.x services)** ran on a `docker compose down -v && up -d` cycle 2026-05-18: each affected service (sales, purchasing, finance, reporting, manufacturing) booted clean against a baseline-provisioned DB — all rename + consolidation changesets applied idempotently, the 3 data-migration sub-changesets correctly mark-ran via preconditions, the `DROP TABLE IF EXISTS` for the 3 predecessor tables logged "does not exist, skipping" as designed.

**Follow-up not in scope**: the three projection ports (`ProductReplenishmentProjection`, `ProductActiveBomProjection`, `ProductMaterialsCostProjection`) could be consolidated into one `ProductCardProjection` (like finance's pattern from §2.23.3) for full convention compliance. Kept as separate ports here — that consolidation is a Java-side refactor independent of the table consolidation and worth its own focused slice.

## 2026-05-18 — §2.23.4: `reporting.product_standard_cost` → `reporting.product_card`

Fourth of five `_card` migrations. Single-attribute table for now; rename future-proofs reporting's product card so additional reporting-side product facets land as columns on this one table rather than separate single-column projections.

- **Baseline** `db/northwood_erp.sql`: table + trigger renamed; comment updated; seed-INSERT retargeted to the new name.
- **New rename changeset** `2026-05-18-rename-product-standard-cost-to-product-card.sql` (`splitStatements:false`) with `ALTER TABLE IF EXISTS` + DO-block trigger rename. Sequenced AFTER the earlier `rename-product-standard-cost-captured-at` changeset so the column-rename can complete against the legacy table name before the table renames; both no-op idempotently on fresh boot.
- **Java**: `ProductStandardCostProjection` → `ProductCardProjection` (interface, `application/inbox/`); `JdbcProductStandardCostProjection` → `JdbcProductCardProjection` (impl). Method signature unchanged.
- **SQL strings**: INSERT in the new `JdbcProductCardProjection`; the two financial-dashboard JOINs (`JdbcFinancialDashboardQueryPort.findSnapshot` for ad-hoc reads, `JdbcFinancialDashboardProjection.refreshDailyBalances` for the worker-driven materialised refresh); plus the inline comment on the query port.
- **`StandardCostChangedHandler.CONSUMER_NAME`** updated from `reporting.product-standard-cost-projector` → `reporting.product-card-projector` for consistency. Javadoc reference refreshed.
- **`application-kafka.yml`** comment refreshed.
- **Doc cascade**: `docs/event-flow.html` (2 spots — `StandardCostChanged` projection target + the COGS-cost-source description in `ShipmentPostedHandler`); `docs/projections.html` (catalog row); `docs/user-stories.md` (2 financial-dashboard mentions); `docs/build-status.md` (reporting bullet); `docs/demo-script.md`; `docs/design-notes.md` (two silent-fallback rows for COGS standard-cost + valuation-class — column references reshaped to `finance.product_card.*` since the predecessor tables are gone). Historical `dev-done.md` entries left untouched.

**Smoke**: `mvn install -DskipTests` clean; `mvn -pl reporting-service test` → 7/7 green.

## 2026-05-18 — §2.23.3: `finance.product_accounting` → `finance.product_card`

Third of five `_card` migrations. Largest rename so far — finance has the canonical consolidated-projection pattern (one write port + one read port + 4 inbox handlers + 2 service injection sites + 2 tests + in-memory test fake).

- **Baseline** `db/northwood_erp.sql`: `finance.product_accounting` → `finance.product_card`; trigger renamed; seed-INSERT comment + INSERT statement updated; cross-reference comment on `reporting.product_standard_cost` updated.
- **Historical Liquibase changeset** `2026-05-15-consolidate-product-accounting.sql` retargeted: all four sub-changesets now reference `finance.product_card` (CREATE TABLE IF NOT EXISTS becomes a no-op on fresh baseline; data-migration changesets with preconditions still mark-ran when the predecessor tables don't exist). Trigger name updated to `trg_product_card_updated_at`.
- **New rename changeset** `2026-05-18-rename-product-accounting-to-product-card.sql` (`splitStatements:false`) with `ALTER TABLE IF EXISTS` + DO-block trigger rename for legacy dev DBs.
- **Java consolidated projection ports renamed**: `ProductAccountingProjection` → `ProductCardProjection` (interface), `JdbcProductAccountingProjection` → `JdbcProductCardProjection` (impl), `ProductAccountingLookup` → `ProductCardLookup` (interface), `JdbcProductAccountingLookup` → `JdbcProductCardLookup` (impl). Method signatures (`seed`, `applyStandardCost`, `applyValuationClass`, `applyDiscontinued`, `findStandardCost`, `findValuationClass`) unchanged. Old files deleted.
- **Call-site updates**: `JournalEntryService` field `productAccounting` → `productCards` (plural per convention) and type swap. Same on `ShipmentPostedCogsHandler`. Four inbox handlers (`ProductCreatedHandler`, `StandardCostChangedHandler`, `ValuationClassChangedHandler`, `ProductDiscontinuedHandler`) switched from `ProductAccountingProjection` to `ProductCardProjection`.
- **SQL strings** in the new `JdbcProductCardProjection` (4 write methods × INSERT/UPDATE) + `JdbcProductCardLookup` (2 SELECT queries) all reference `finance.product_card`.
- **Test-harness**: `InMemoryProductAccounting` → `InMemoryProductCard` (one fake implements both ports); `FinanceTestKit.productAccounting` → `productCards`.
- **Service tests**: `JournalEntryServicePostingsTest`, `JournalEntryServiceReverseBySourceTest` mock-type swaps + field renames.
- **Javadocs + log strings**: `JournalEntryService` silent-fallback contract; `ShipmentPostedCogsHandler` cost-source description + cold-start log line; all 4 inbox handlers' header comments; `application-kafka.yml` Kafka-topic comment.
- **Doc cascade**: `docs/conventions.md` (3 spots, the example-list rows), `docs/build-status.md` (3 references), `docs/projections.html` (3 references), `docs/event-flow.html` (multiple), `docs/domain-driven design.html`, `docs/demo-script.md`, `docs/user-stories.md`, `demo-web-ui/src/routes/Products.tsx`. Historical `dev-done.md` left untouched.

**Smoke**: `mvn install -DskipTests` clean; `mvn -pl finance-service test` → 112/112 green; `mvn -pl test-harness test` → 8/8 green.

## 2026-05-18 — §2.23.2: `purchasing.product_discontinued` → `purchasing.product_card`

Second of five `_card` migrations. Mechanical rename, single-column table, no shape change beyond relaxing `discontinued_at` to nullable so future purchasing-side product facets can land as additional columns without splitting the table.

- **Baseline** `db/northwood_erp.sql`: added a fresh `CREATE TABLE purchasing.product_card (product_id UUID PRIMARY KEY, discontinued_at TIMESTAMPTZ)` near the existing `product_approved_vendor` table. Discontinued_at is now nullable (was NOT NULL on the old `product_discontinued`) — read query updated to filter on `discontinued_at IS NOT NULL` so semantics are unchanged today + future seed-on-Created handlers can insert without filling it.
- **Historical Liquibase changeset** `2026-05-14-add-product-discontinued-projection.sql` retargeted to create `purchasing.product_card` directly (with nullable `discontinued_at`); becomes a no-op on fresh baseline boot via `CREATE TABLE IF NOT EXISTS`. Hash drift accepted per posture.
- **New rename changeset** `2026-05-18-rename-product-discontinued-to-product-card.sql` does `ALTER TABLE IF EXISTS purchasing.product_discontinued RENAME TO product_card` for legacy dev DBs; no-op against fresh baseline.
- **Java**: `JdbcProductDiscontinuedProjection` SQL updated to INSERT into `purchasing.product_card`. `JdbcDiscontinuedProductLookup` SQL changed from `SELECT COUNT(*) FROM purchasing.product_discontinued WHERE product_id = ?` to `... WHERE product_id = ? AND discontinued_at IS NOT NULL` — future-proof against rows that exist for other reasons. Class names kept (`DiscontinuedProductLookup` is genuinely narrow, single-method; the `_card` table is fine to live behind it without renaming the lookup).
- **Javadocs** updated on `DiscontinuedProductLookup` interface, `ProductDiscontinuedProjection` interface, `ProductDiscontinuedHandler`. Test method `happy_path_stamps_purchasing_product_discontinued` → `happy_path_stamps_purchasing_product_card`.
- **Doc cascade**: `docs/projections.html` projection-shape table; `docs/event-flow.html` description text. Historical `dev-done.md` entries untouched.

**Smoke**: `mvn -pl purchasing-service test` → 79/79 green.

## 2026-05-18 — §2.23.1: `sales.product_pricing` → `sales.product_card`

First of five `_card`-suffix migrations per `docs/conventions.md` → *Consumer-side denormalized tables*. Mechanical rename — no shape change, no consolidation, just the table + class name move so sales aligns with the new convention.

- **Baseline** `db/northwood_erp.sql`: `sales.product_pricing` → `sales.product_card`; trigger `trg_product_pricing_updated_at` → `trg_product_card_updated_at`; seed-INSERT comment + reference comment in finance section updated.
- **New Liquibase changeset** `2026-05-18-rename-product-pricing-to-product-card.sql` (sales-service) with `splitStatements:false` for the inner DO block. Idempotent via `ALTER TABLE IF EXISTS` on the table rename and a `BEGIN ... EXCEPTION WHEN undefined_object OR undefined_table` wrap on the trigger rename — no-ops cleanly against a fresh baseline-provisioned DB (which already has the new names).
- **Historical Liquibase changesets retargeted** to the new table name: `2026-05-14-add-product-pricing-discontinued-at.sql` and `2026-05-15-product-pricing-nullable-price.sql` (filename kept; content updated to reference `sales.product_card`). They become no-ops on fresh boot since the column shape they assert is already in the baseline. Hash drift is accepted per the project's fresh-volume-reset posture.
- **Java renames**: `ProductPricingLookup` → `ProductCardLookup` (interface, `application/`); `JdbcProductPricingLookup` → `JdbcProductCardLookup` (impl, `infrastructure/persistence/`). Method signatures and `CatalogPrice` record unchanged. Old files deleted.
- **SQL strings updated** in `JdbcSalesPriceProjection`, `JdbcProductDiscontinuedProjection`, `JdbcProductCreatedProjection` (all write paths still per-event, target the renamed table). Javadoc references on the three per-event `*Projection` interfaces + `*Handler` classes refreshed.
- **`SalesPriceChangedHandler.CONSUMER_NAME`** updated from `sales.product-pricing-projector` to `sales.product-card-projector` for consistency. The other two handlers (`ProductDiscontinuedHandler`, `ProductCreatedHandler`) had neutral consumer names; unchanged.
- **Injection sites**: `SalesOrderService` field `productPricing` → `productCards` (plural, per the instance-field-naming convention's "full aggregate name in plural" rule); type swap.
- **Test-harness**: `InMemoryProductPricingLookup` → `InMemoryProductCardLookup`; `SalesTestKit.pricing` → `SalesTestKit.productCards`; three test seed sites in `o2c/*Test.java` updated.
- **Doc updates**: `CLAUDE.md` schema-naming summary + instance-field example; `docs/conventions.md` 3 historical references + the instance-field-naming example; `docs/user-stories.md`; `docs/demo-script.md`; `docs/projections.html`; `docs/event-flow.html`; `docs/domain-driven design.html`; `docs/design-notes.md`; `demo-web-ui/src/routes/Products.tsx`. Historical `dev-done.md` entries left untouched (append-only).

**Smoke**: `mvn install -DskipTests` clean; `mvn -pl sales-service test` → 133/133 green; `mvn -pl test-harness test` → 8/8 green. Fresh-volume Liquibase smoke deferred to final-slice §2.23.5 boot.

## 2026-05-18 — Convention text: cardinality-based projection-table rule + `_card` suffix (§2.23 prep)

Sharpened the consumer-side denormalized-table rule in `docs/conventions.md` after a design conversation:

- Replaced the "shared-lifecycle attribute group" framing (too aggressive a splitter — every 1:1 facet has *some* lifecycle skew) with **cardinality-based**: one table per (schema, source aggregate) for 1:1 facets; split only when cardinality genuinely differs (1:N children stay separate, keep `<source_aggregate>_<child>` shape).
- Introduced the **`_card` suffix** for consumer-side denormalized tables — `sales.product_card`, `manufacturing.product_card`, etc. The per-entity-record metaphor (inventory card, customer card — the institutional record on an external entity maintained by someone who tracks it without owning it) is category-agnostic, so mixed mirror + locally-computed columns coexist freely under one suffix.
- Documented the alternative suffixes considered + rejection reasons (`_projection` / `_view` claim "pure read-only" which fails for mixed mirror+computed tables; `_facts` is in-codebase but too generic; `_worksheet` is voice-fit but implies active computation and is verbose; `_card` won on per-entity-record semantic + brevity).
- Updated the bottom *Tables — shape families* table: split the single "Projection cache of upstream fact" row into four distinct shapes (1:1 `_card`, 1:N child no-suffix, per-child-row `_facts`, running-total `_balance`) so the cardinality distinction is visible at a glance.

Migration plan: `dev-todo.md` §2.23 enumerates five rename sub-slices (sales / purchasing / finance / reporting / manufacturing). First four are mechanical renames; the fifth consolidates manufacturing's three projection tables (`product_replenishment` + `product_active_bom` + `product_materials_cost`) into one `manufacturing.product_card`. Each sub-slice gets its own dev-done entry as it lands.

No code change in this slice — the §2.23 sub-slices carry the actual schema + code work. Doc-only commit so the convention is canonised before the migration commits start referring to it.

## 2026-05-18 — PostgreSQL schema-naming convention canonised + `reporting.product_standard_cost.captured_at` → `updated_at`

Writing up the schema-naming rules surfaced a latent bug: `reporting.product_standard_cost` had `captured_at`, but `trg_reporting_product_standard_cost_updated_at` is attached to that table and calls `shared.set_updated_at()`, which writes `NEW.updated_at`. The trigger has been silently broken since the table was added — every `INSERT … ON CONFLICT DO UPDATE` from `JdbcProductStandardCostProjection` would have errored with *"column updated_at does not exist"* on first re-projection. Rename fixes both the convention drift AND the bug.

- `db/northwood_erp.sql`: column renamed.
- New Liquibase changeset `2026-05-18-rename-product-standard-cost-captured-at.sql` (idempotent, mirroring slice 1's `information_schema.columns` guard + `splitStatements:false`).
- `JdbcProductStandardCostProjection.java` UPSERT no longer sets the timestamp explicitly — `DEFAULT now()` handles INSERT, the now-functional trigger handles UPDATE.

**Convention consolidated** into `docs/conventions.md` as a new bottom section *PostgreSQL schema, table, and column naming* (12 subsections: schemas; tables singular + `_header` rule + shape families; columns PK / FK / boolean / timestamp / audit / status / money / row_version; indexes; triggers; constraints; anti-patterns; 4 historical drifts; canaries). Single canonical exhaustive statement; `CLAUDE.md` *Pointers* line for conventions.md updated to mention schema naming; `docs/persistence.md` *Schema conventions* lead-in points to the canonical so the quick-reference summary doesn't drift.

Tolerated historical drifts documented (not migrated, net new follows the rule): `routing_header` ↔ `routing_operation`; `production_planning_board` / `financial_dashboard_daily` without `_view`; `finance.gl_account` / `finance.tax_code` without `updated_at`; `reporting.projection_checkpoint` without `created_at`.

**Liquibase gotcha caught during smoke.** First fresh-volume boot of reporting failed with `org.postgresql.util.PSQLException: Unterminated dollar quote started at position 3 in SQL DO $$` — Liquibase's formatted-SQL parser splits on `;` by default, chopping the PL/pgSQL `DO $$ … END $$;` block at the inner `END IF;`. Fix: declare `splitStatements:false` on the changeset header. Retroactively applied to BOTH this slice's reporting changeset AND slice 1's manufacturing changeset (same `DO`-block shape) before either was committed. Captured as a new section in user-level `~/.claude/notes/postgresql-liquibase.md` since it bites any Liquibase formatted-SQL changeset using idempotency guards — silent in unit tests, only surfaces on fresh-volume integration boot.

**Smoke:** `docker compose down -v ; docker compose up -d` → fresh volume → `mvn -pl reporting-service spring-boot:run` → all 4 reporting changesets ran clean (including the new rename); service started in 7.3 s.

## 2026-05-18 — `manufacturing.product_approved_vendor.preferred` → `is_preferred`

The two Shape-A approved-vendor projection tables (`purchasing.*` and `manufacturing.*`) cache the same upstream `product.ApprovedVendorListChanged` event but had divergent column names: `is_preferred` (purchasing, matching `product.approved_vendor.is_preferred` and the project-wide `is_` boolean-prefix convention) vs `preferred` (manufacturing, drift from §2.8 Slice C). Aligned manufacturing.

- `db/northwood_erp.sql`: column + partial-index `WHERE` clause renamed (Postgres auto-rewrites the predicate when the column is renamed, so no separate index DDL needed).
- New idempotent Liquibase changeset `2026-05-18-rename-product-approved-vendor-preferred.sql` — column-existence guard via `information_schema.columns`, declared `splitStatements:false` so Liquibase's formatted-SQL splitter doesn't chop the `DO $$ … END $$;` block at the inner `END IF;` (see follow-up slice for how this gotcha was caught).
- `JdbcProductApprovedVendorProjection.java` (manufacturing): INSERT column list + `findPreferredSupplierId` `WHERE` clause use `is_preferred`.

Trigger: question "why is `manufacturing.product_approved_vendor` different from `purchasing.product_approved_vendor`?" surfaced the historic drift. Investigating the broader codebase for similar drift led to the schema-naming consolidation slice that follows.

## 2026-05-17 — §2.22: Demote `StockItem` from aggregate to projection ports

The last `*Repository`-without-an-emitter offender from the 2026-05-17 audit. `inventory.StockItem` had the full aggregate skeleton (`AGGREGATE_TYPE`, `pendingEvents`, `pullPendingEvents()`, `StockItemRepository` with optimistic-concurrency on `version`) but emitted zero events — every mutation was `applyReorderPolicy` driven by an inbound product-master fact, so it was structurally a snapshot projection of upstream state, not a delta-emitting aggregate.

Demoted to the projection-shaped port pattern (`*Projection` + `*QueryPort`) matching the §2.18 Routing / Supplier template:

- **New writer port pair** — `application/inbox/StockItemProjection` (interface; was a concrete `@Service` class that delegated through `StockItemRepository`) + `infrastructure/persistence/JdbcStockItemProjection` (impl; direct UPDATE on `inventory.stock_item.reorder_point/reorder_quantity` with no-op-when-unchanged via `IS DISTINCT FROM`, WARN-and-no-op when the row is missing — same race tolerance as `JdbcProductDiscontinuedProjection`). Mirrors the sibling `ProductCreatedProjection` / `ProductDiscontinuedProjection` shape.
- **New reader port pair** — `application/StockItemQueryPort` (interface) + `infrastructure/persistence/JdbcStockItemQueryPort` (impl). Returns `StockItemView` directly — no intermediate domain row class, since the table is read-only from inventory's perspective and the wire shape is the natural payload.
- **`StockItemService` switched** from `StockItemRepository` to `StockItemQueryPort`. Public API unchanged; `findById` / `findByProductId` / `findAll` now pass through directly (no `.map(StockItemView::from)` since the port produces views).
- **`StockItemView`** drops the `from(StockItem)` static factory and the dependency on `StockItem`; becomes a plain wire record. All fields preserved (including `version`) for wire compatibility — the demo UI's `StockItemView` shape is unchanged.
- **Deleted four files**: `domain/StockItem.java`, `domain/StockItemId.java`, `domain/StockItemRepository.java`, `infrastructure/persistence/JdbcStockItemRepository.java`.
- **`STOCK_ITEM` constant removed** from `InventoryAggregateTypes`. The constant had no cross-service consumers (verified by grep across the whole repo); only `StockItem.java`'s `AGGREGATE_TYPE` re-export referenced it.
- **`version` column on `inventory.stock_item` kept defensively** — no inventory-side writer bumps it today (matching the prior `JdbcProductDiscontinuedProjection` behaviour for the same table). Surfaces through the wire as a frozen value; not a semantic regression since nothing on the SPA reads it as a meaningful concurrency token. Drop the column if/when a future cleanup wants to.

**Smoke:** `mvn -pl inventory-service test` → 78/78 green (existing handler tests mocked the projections directly, not the repository, so no test churn). `mvn -pl test-harness test` → 8/8 saga end-to-end paths green.

**Promotion path (reverse, when relevant):** the first inventory-originated stock-fact slice — manual stock-adjustment, stock-take correction, force-release, or any other intent-named command that emits a new inventory event — promotes `StockItem` back to a real aggregate, same shape as the §2.16 (Bom) / §2.17 (SupplierProductPrice) promotions.

The 2026-05-17 audit's *Repository*-without-an-aggregate roster is now empty. The deltas-vs-totals rule in `docs/conventions.md` is enforced across the whole codebase.

## 2026-05-17 — Convention formalised: *deltas get aggregates, totals and snapshot projections get projection ports*

Captured the load-bearing aggregate-qualification principle as a top-level section in `docs/conventions.md` (right after the existing port/suffix table): the four-category framework (Delta / Total / Snapshot projection / Reference data), promotion criteria, and the conceptual lineage (Pacioli 1494 → event sourcing → DDD+outbox). `CLAUDE.md` gains a one-line summary + pointer in the Naming-summary section so the rule is discoverable from the project entry point. The stale "Today's only legitimate hybrid is `inventory.stock_item`" claim mid-paragraph in `conventions.md` was rewritten to reflect §2.22 (flagged for demotion).

Trigger: the 2026-05-17 audit conversation surfaced one further `*Repository`-without-an-aggregate offender (`StockItem`) and, in the course of explaining why it's a violation, articulated the deltas-vs-totals principle as a project-wide rule. Documented while the framing was fresh. Auto-memory `project_deltas_vs_totals.md` updated to point at the canonical project doc.

No code change. New backlog item §2.22 added to `dev-todo.md` (demote `StockItem` aggregate → projection ports).

**Follow-up same day — ERP-as-accounting framing captured.** `docs/architecture.md` gains a new top-level section *Why this codebase looks the way it does — ERP as applied accounting epistemology* (placed before *Module layout*) — the Pacioli framing of the whole codebase, the six-row concept mapping (journal entry ↔ event, ledger ↔ projection, chart of accounts ↔ reference data, posting ↔ outbox-in-transaction, audit trail ↔ meta-journal, trial balance ↔ reconciliation invariant), where the accounting analogy stops (workflow / role-based UX / forward-looking computation), and how the existing architectural rules read more easily as accounting discipline generalised. `README.md` gains a one-sentence pointer in the opening section (kept light-touch per §1E.1's "user drives README voice"). Triggered by the deltas-vs-totals conversation crystallising into the broader observation that *ERP is an accounting-shaped system applied to all business facts, not just monetary ones*.

## 2026-05-16 — §2.21: `AGGREGATE_TYPE` on `MakeToOrderSaga` + `PurchaseToPaySaga` for symmetry

§2.20 left a small asymmetry: only `SalesOrderFulfilmentSaga` carried an `AGGREGATE_TYPE` constant (and the matching `SALES_ORDER_FULFILMENT_SAGA` entry in `SalesAggregateTypes`), because it's the only saga that today stamps outbox rows under its own identity. The other two sagas don't — `MakeToOrderSagaWorker` stamps its sole emission (`RawMaterialReservationRequested`) under `WorkOrder.AGGREGATE_TYPE`, and `PurchaseToPaySagaWorker` emits nothing (its single worker-driven transition is a park-and-wait state change; all other transitions are inbox-handler-driven against `PurchaseOrder` / `SupplierInvoice` / `Payment`). Per the §2.20 rule ("sagas that own their own emissions independently of any domain aggregate carry the constant"), neither was eligible.

This slice declares the constants anyway for symmetry — every saga aggregate now has the same shape, and the constants exist as stable call sites if either saga ever grows a self-originated command.

### Changes

- `manufacturing-events.ManufacturingAggregateTypes` — added `MAKE_TO_ORDER_SAGA = "MakeToOrderSaga"`.
- `purchasing-events.PurchasingAggregateTypes` — added `PURCHASE_TO_PAY_SAGA = "PurchaseToPaySaga"`.
- `manufacturing-service.domain.saga.MakeToOrderSaga` — `public static final String AGGREGATE_TYPE = ManufacturingAggregateTypes.MAKE_TO_ORDER_SAGA` + javadoc noting why nothing currently stamps under it (the WorkOrder stream owns the worker's sole emission).
- `purchasing-service.domain.saga.PurchaseToPaySaga` — `public static final String AGGREGATE_TYPE = PurchasingAggregateTypes.PURCHASE_TO_PAY_SAGA` + javadoc noting that the worker emits nothing and inbox-handler-driven transitions stamp under their target aggregates.

No outbox writer was changed. `MakeToOrderSagaWorker.appendOutbox(event, WorkOrder.AGGREGATE_TYPE)` deliberately keeps stamping under WorkOrder's stream because that's where `RawMaterialReservationRequested` naturally belongs.

### Convention updates

- `docs/sagas.md` § *Saga manager class shape* — the saga `AGGREGATE_TYPE` paragraph rewritten. Was: *"Sagas that own their own emissions independently of any domain aggregate carry the constant on the saga state-machine class"* (which described the rule for when the constant was *required*). Now: all three sagas declare the field for symmetry; the paragraph explicitly enumerates which saga stamps under its own aggregate-type (`SalesOrderFulfilmentSaga`) and which use the constant only as a reserved future call site (`MakeToOrderSaga`, `PurchaseToPaySaga`), with the reason (where their emissions naturally belong or that there aren't any).
- `docs/domain-driven design.html` § *Messaging convention rules table* — the "Every aggregate-type string is hosted on the aggregate root" row now reads *"(or saga state-machine class)"* and lists `SalesOrderFulfilmentSaga.AGGREGATE_TYPE` alongside `SalesOrder.AGGREGATE_TYPE` / `Product.AGGREGATE_TYPE` in the example column.

`docs/architecture.md:48` left untouched: the row already reads "`<AggregateRoot>.AGGREGATE_TYPE`" and the docs treat saga state-machine classes as aggregates, so the line is technically inclusive; the detail lives in `docs/sagas.md`. Saga state-transition diagrams (`docs/SalesOrderFulfilmentSaga.md`, `docs/MakeToOrderSaga.md`, `docs/PurchaseToPaySaga.md`) unaffected — no `transitionTo`, `ALL_STATES`, factory, or current-step label changed.

### Smoke

- `mvn -DskipTests -pl manufacturing-events,purchasing-events,manufacturing-service,purchasing-service -am compile` ✅ clean.
- `mvn -DskipTests compile` (full reactor) ✅ clean.
- Grep for the literal `"MakeToOrderSaga"` / `"PurchaseToPaySaga"` returns only the two new constants — no test fixture or schema depends on the wire string.

---

## 2026-05-16 — §2.16: Promote `Bom` to a real aggregate (retire `BomEditRepository`)

The last `*Repository`-without-an-aggregate offender from the 2026-05-15 audit is gone. `manufacturing.domain.BomEditRepository` and its JDBC adapter deleted; replaced by a proper `Bom` aggregate root with the standard shape (identity VO + status enum + internal entity + version + pendingEvents + intent-named mutators + outbox-draining save). Five offenders surfaced by the audit; all five resolved now (§2.16 + §2.17 + §2.18).

### New aggregate

- `manufacturing.domain.Bom` — aggregate root with `AGGREGATE_TYPE = ManufacturingAggregateTypes.BOM`, `BomId` identity VO, `Status` enum (`DRAFT` / `ACTIVE` / `INACTIVE`), internal `BomLine` entity (`BomLineId` identity, line number, component snapshot, qty + scrap), optimistic-concurrency `aggregateVersion` (mapped to DB `row_version`), `pendingEvents`.
- Static factories: `Bom.draft(...)` (validates non-null + non-blank inputs, creates DRAFT with empty lines, no event), `Bom.reconstitute(...)` (loads from DB, no event).
- Intent-named mutators:
  - `addLine(BomLine.Spec)` — rejects on non-draft + self-reference, allocates next line-number from current max, appends. Tracks new line in `addedLines` for repository INSERT.
  - `removeLine(BomLineId)` — rejects on non-draft. If the target line was added this session (in `addedLines`), removes from both lists without flagging for DB delete; otherwise records the id in `removedLineIds` for repository DELETE.
  - `activate()` — flips `DRAFT → ACTIVE`, emits `BomActivated`. No-op when already ACTIVE. Rejects on INACTIVE and on empty draft.
- New domain event `manufacturing.BomActivated` in `manufacturing-events` (distinct from the renamed `product.ActiveBomChanged` — different service, different semantics).

### Repository

- `BomRepository` interface in `manufacturing.domain` with `findById` / `findBomIdByLineId` / `save`. `JdbcBomRepository` in `manufacturing.infrastructure.persistence` handles INSERT vs UPDATE on `row_version == 0` sentinel, applies the line diff (INSERTs from `pullAddedLines()`, DELETEs from `pullRemovedLineIds()`), and drains `pullPendingEvents()` to the manufacturing outbox.
- Optimistic-concurrency check on UPDATE: `UPDATE ... WHERE bom_header_id = ? AND row_version = ?`; zero affected rows throws `OptimisticLockingFailureException`. Activation's "at most one active per finished product" is enforced by the existing partial unique index `uq_bom_active_per_product` — a competing active surfaces as `DataIntegrityViolationException` and the surrounding `@Transactional` rolls back cleanly.

### Application service: thin orchestrator

- `BomEditService` kept (not renamed). Now four steps per command: load the aggregate, mutate it, save, run post-save cross-aggregate checks.
- Cycle detection is application-orchestrated, not aggregate-internal — see `Bom`'s class Javadoc for the design rationale. The `BomCycleDetector` walks the DB graph; the application service runs it after `save()` so the detector sees the just-persisted state, and the surrounding `@Transactional` rolls back on a positive finding. This deviates from the §2.16 backlog's "pass `BomCycleDetector` into `Bom.activate(...)`" wording: routing the detector through the aggregate would require the aggregate to query other aggregates' data, which is exactly what domain services exist to externalise.
- Materials-cost rollup unchanged — still called from `BomEditService.activate` on the post-success path.
- Exception wrapping: domain `Bom.BomCycleException` / `Bom.BomNotEditableException` thrown by the aggregate are caught and rewrapped as the application-layer `BomEditService.BomCycleException` / `BomEditService.BomNotEditableException` for the controller (preserves the existing wire contract; controller code unchanged).

### Test-harness adapter

- New `InMemoryBomRepository` (with `OutboxPort` + `ObjectMapper` injected) replaces `InMemoryBomEditRepository`. Enforces "at most one active per finished product" at save-time so the harness reproduces the production partial-unique-index behaviour.
- `InMemoryBomLookup` (read-side, unchanged) collided with the new `boms` field name; renamed to `bomLookup` in `ManufacturingTestKit`. Two existing E2E tests (`SubAssemblyRecursionTest`, `MakeToOrderShortagePathTest`) seed BOMs via the lookup — updated to `mfg.bomLookup.put(...)`.

### Test coverage

- New `BomTest`: 22 cases covering null/blank/status guards on every factory + mutator, line-number monotonicity, add-then-remove diff tracking, no-op suppression on `activate(ACTIVE)`, happy-path `BomActivated` emission, reconstitute round-trip.
- `BomEditServiceTest` rewritten against the new aggregate shape (real `Bom` instances via `reconstitute`, mocked `BomRepository`). Preserved every original case (~22), plus added "rejects when component is discontinued" gate-test variant.

### Convention-compliance state

The `docs/conventions.md` known-exception list is now empty. The 2026-05-15 audit identified five offenders; all five are resolved:
- §2.16 — `BomEditRepository` deleted; `Bom` aggregate promoted (this slice).
- §2.17 — `SupplierProductPriceRepository` is now a real DDD Repository for `SupplierProductPrice`; `ApprovedVendorRepository` deleted, `ApprovedVendor` folded into `Product`.
- §2.18 — `RoutingRepository` / `SupplierRepository` renamed to `RoutingQueryPort` / `SupplierQueryPort` (false positives — read-only).

### Smoke

- `mvn -DskipTests clean compile` ✅ 19/19 modules green.
- `mvn -DskipTests test-compile` ✅ 19/19 modules green.
- `mvn test` ✅ full reactor green: every per-service test + 8/8 test-harness E2E (`OrderToCashHappyPathTest`, `OrderToCashFirstLegTest`, `CancelCompensationTest`, `MakeToOrderShortagePathTest`, `SubAssemblyRecursionTest`, `PurchaseToPayHappyPathTest`, `PurchaseToPayRejectionPathTest`, `SetPriorityCascadeTest`).
- `BomTest` 22 cases pass; `BomEditServiceTest` rewritten and pass.

---

## 2026-05-16 — §2.20: Centralize aggregate-type constants in `<Service>AggregateTypes` files

Final cleanup of the `AGGREGATE_TYPE`-without-a-source-of-truth situation. Previously each aggregate root inlined its own `public static final String AGGREGATE_TYPE = "Product"` literal, cross-service event classes inlined the literal again (`ManufacturingDispatched.AGGREGATE_TYPE = "SalesOrder"` as a duplicate of `SalesOrder.AGGREGATE_TYPE`), and ~30 cross-service consumer-test sites used bare string literals because consumers can't import the producer's `domain/` package. Three layers of duplication for one wire-format string.

After this slice: each service's events jar hosts a single `<Service>AggregateTypes` utility class with one constant per aggregate. Aggregate classes re-export from there; cross-service consumers and cross-service event classes import directly.

### Files created (6)

| Events jar | File | Constants |
|---|---|---|
| product-events | `ProductAggregateTypes` | `PRODUCT` |
| sales-events | `SalesAggregateTypes` | `SALES_ORDER`, `CUSTOMER`, `SALES_ORDER_FULFILMENT_SAGA` |
| inventory-events | `InventoryAggregateTypes` | `STOCK_RESERVATION`, `STOCK_ITEM`, `GOODS_RECEIPT`, `SHIPMENT` |
| manufacturing-events | `ManufacturingAggregateTypes` | `WORK_ORDER` |
| purchasing-events | `PurchasingAggregateTypes` | `PURCHASE_ORDER`, `PURCHASE_REQUISITION`, `SUPPLIER_PRODUCT_PRICE` |
| finance-events | `FinanceAggregateTypes` | `SUPPLIER_INVOICE`, `CUSTOMER_INVOICE`, `PAYMENT`, `JOURNAL_ENTRY` |

Each is a final utility class with private constructor. No `ReportingAggregateTypes` — reporting consumes events but emits none (no events jar).

### Naming convention

- Class name: `<Service>AggregateTypes` (PascalCase, plural).
- Constant name: `SCREAMING_SNAKE_CASE` of the aggregate / saga name.
- Constant value: PascalCase of the aggregate / saga name (the wire-format string, unchanged).
- Saga AGGREGATE_TYPE constants follow `*Saga` value naming: `SALES_ORDER_FULFILMENT_SAGA = "SalesOrderFulfilmentSaga"`.

### Aggregate-class side: re-export, don't duplicate

Each aggregate class keeps its `AGGREGATE_TYPE` field as a re-export from the events jar:

```java
public class Product {
    public static final String AGGREGATE_TYPE = ProductAggregateTypes.PRODUCT;
    // ...
}
```

This preserves every existing producer-side call site (`Product.AGGREGATE_TYPE`, `SalesOrder.AGGREGATE_TYPE`, etc.) — zero churn at outbox-write sites. The constant is one indirection away from the wire string.

15 aggregate classes + 1 saga updated: `Product`, `SalesOrder`, `Customer`, `SalesOrderFulfilmentSaga`, `StockReservation`, `StockItem`, `GoodsReceipt`, `Shipment`, `WorkOrder`, `PurchaseOrder`, `PurchaseRequisition`, `SupplierProductPrice`, `SupplierInvoice`, `CustomerInvoice`, `Payment`, `JournalEntry`.

### Cross-service stamping: events jars depend on events jars

Two events stamp aggregates owned by another service:
- `ManufacturingDispatched.AGGREGATE_TYPE = SalesAggregateTypes.SALES_ORDER` (event in manufacturing-events, aggregate in sales-service)
- `ProductMaterialsCostComputed.AGGREGATE_TYPE = ProductAggregateTypes.PRODUCT` (event in manufacturing-events, aggregate in product-service)

`manufacturing-events` POM gained two new deps: `sales-events` + `product-events`. Events-jar-to-events-jar deps are clean (both are wire-contract modules, no Spring / JDBC); kept narrow to actual cross-service stamping needs only. The producer-side previously-inlined `"SalesOrder"` / `"Product"` literals are gone — both now resolve through the events-jar constants.

### Cross-service consumer-test literals

~30 sites across `finance-service`, `inventory-service`, `manufacturing-service`, `purchasing-service`, `reporting-service`, `sales-service`, `product-service`, and `test-harness` switched from string literals to imported constants. Example:

```diff
-eventId, "PurchaseOrder", PO,
+eventId, PurchasingAggregateTypes.PURCHASE_ORDER, PO,
```

The `RawMaterialsReservedHandlerTest` assertion on captured `getAggregateType()` was also updated:

```diff
-assertThat(captor.getValue().getAggregateType()).isEqualTo("WorkOrder");
+assertThat(captor.getValue().getAggregateType()).isEqualTo(ManufacturingAggregateTypes.WORK_ORDER);
```

### Convention update

`docs/sagas.md` § *Saga manager class shape* — the AGGREGATE_TYPE rule paragraph rewritten to describe both layers (the `<Service>AggregateTypes` source-of-truth + the aggregate-class re-export) and to name the cross-service stamping deps direction. `SupplierProductPriceChanged.AGGREGATE_TYPE` reference removed (deleted in §2.17). The "no Java aggregate" exception language replaced with the cross-service-stamping shape, which is the current real case.

### Smoke

- `mvn -DskipTests clean compile` ✅ 19/19 modules green
- `mvn -DskipTests test-compile` ✅ 19/19 modules green
- `mvn test` ✅ full reactor green (all per-service tests + 8/8 test-harness E2E)
- Grep audit: `"public static final String AGGREGATE_TYPE = \""` returns zero hits in `**/*.java` — every aggregate-type field now resolves to an events-jar constant.

### Why this shape

Three options were considered:

- **A (chosen)** — `<Service>AggregateTypes` in each events jar with all aggregate types. High DRY, single source of truth per service, mild events-jar-to-events-jar deps for cross-service stamping.
- **B** — AGGREGATE_TYPE constant on each event class (the original §2.20 plan). Low DRY, ~10 events touched, no new files, no new deps.
- **C** — Constants in shared-kernel (single global file). Highest DRY but pollutes shared-kernel with service-specific constants; loses "this constant belongs to service X" semantics.

A wins on long-term DRY and clear ownership. The new events-jar-to-events-jar deps are honest: when manufacturing emits a fact about a SalesOrder, manufacturing-events is genuinely dependent on sales' wire contract.

---

## 2026-05-16 — §2.17: Promote `SupplierProductPrice` + fold `ApprovedVendor` into Product

Tier-1 cleanup of the two remaining `*Repository`-without-an-aggregate offenders. Different DDD shapes, different remedies.

### `SupplierProductPrice` — new standalone aggregate

Promoted from a row-level write port (`find` / `insert` / `updatePrice` / `listForSupplier`) to a real aggregate root keyed by `(supplier_id, product_id, currency_code)`:

- New `SupplierProductPrice` aggregate in `purchasing.domain`: identity VO (`SupplierProductPriceId`), `AGGREGATE_TYPE = "SupplierProductPrice"`, `version` for optimistic concurrency, `pendingEvents`. Static factories `register(...)` (emits `SupplierProductPriceChanged` with `oldUnitPrice = null`) and `reconstitute(...)`. Intent-named `updatePrice(BigDecimal newUnitPrice)` mutator with three guards (null, non-positive, no-op via `compareTo`); emits with `oldUnitPrice` carried.
- `SupplierProductPriceRepository` rewritten to the standard aggregate shape: `findByKey(supplier, product, currency)`, `save(...)` (drains `pendingEvents` to the outbox), `listForSupplier(...)`.
- `JdbcSupplierProductPriceRepository`: insert/update path on `version == 0`, optimistic-concurrency `UPDATE ... WHERE version = ?` for existing aggregates, outbox drain inline.
- `SupplierProductPriceService` reduced to a thin orchestrator: load → register-or-update → save. No more direct `OutboxPort` / `ObjectMapper` / `CurrentUserAccessor` dependencies (the repository owns those now).
- `SupplierProductPriceChanged.AGGREGATE_TYPE` constant deleted from the event class — producer-side outbox writes reference `SupplierProductPrice.AGGREGATE_TYPE` from the aggregate root. No cross-service consumer reads the event-class constant today; if one ever needs to, the constant can be re-added per the §2.20 plan.
- New `SupplierProductPriceTest` (14 cases: factory guards + update guards + no-op suppression + reconstitute round-trip).
- `SupplierProductPriceServiceTest` rewritten to the slim aggregate-orchestration shape (6 cases).
- `InMemorySupplierProductPriceRepository` (test-harness) rewritten to match the new interface; `PurchasingTestKit` wires `OutboxPort` + `ObjectMapper` into the in-memory repo and drops the `CurrentUserAccessor` no-longer-needed by the service.

### `ApprovedVendor` — folded into Product (option (b), not (a))

Originally backlogged with a recommendation for option (a) — a standalone `ApprovedVendorList` aggregate keyed by `productId`. Reading the code flipped the recommendation: `Product.emitApprovedVendorListChanged(...)` already existed and the event's `aggregateId` was `productId`, so the Product aggregate semantically already owned the data. Option (a) would have created two aggregates sharing the same id, which is semantically incoherent. Option (b) just finishes the half-implementation.

- `Product` aggregate now holds the approved-vendor list as a child collection (`List<ApprovedVendor> approvedVendors`, default empty). Old `emitApprovedVendorListChanged(List)` method renamed to `setApprovedVendors(List)` — owns the discontinued check (existing), no-op check (set comparison, moved from service), state mutation, dirty-flag flip, and event emission.
- New `pullApprovedVendorsDirty()` one-shot flag for the repository to know when to rewrite the `product.approved_vendor` table. Mirrors the `pullPendingEvents()` shape.
- `Product.reconstitute(...)` signature gained a trailing `List<ApprovedVendor> approvedVendors` parameter.
- `JdbcProductRepository` now loads approved vendors alongside the product on `findById` / `findBySku` / `findAll` (single extra query per call; `findAll` does one bulk vendor query keyed by the just-loaded product ids), and writes them inside `save()` when `pullApprovedVendorsDirty()` returns true. Unrelated saves (`changeName`, `changeSalesPrice`, …) don't touch the side table.
- `ApprovedVendorRepository` + `JdbcApprovedVendorRepository` deleted.
- `ProductService.setApprovedVendors` reduced to a four-liner: load → mutator → save. No more `approvedVendors` injection, no more service-side no-op check, no more separate `replaceFor` call.
- `ProductServiceTest` updated: drops the `ApprovedVendorRepository` mock; new helper `activeFinishedGoodWithVendors(List)` for the no-op-on-same-set test; updated `Product.reconstitute(...)` signatures.
- `ProductTest` `ApprovedVendorList` nested test: renamed cases from `emit_*` to `set_*`; added `no_op_on_same_set_emits_nothing_and_leaves_dirty_false` and `pull_dirty_resets_to_false`.
- `ApprovedVendor.java` Javadoc cross-ref updated (`ApprovedVendorRepository#replaceFor` → `Product.setApprovedVendors`).

### Why option (b) for ApprovedVendor

Three reasons:

1. **Event ownership says Product**. `ApprovedVendorListChanged.aggregateId = productId`. A consumer doing Find Usages on the event class arrives at Product. Splitting the data into a separate aggregate would force either an event migration or two aggregates sharing one id — both worse than the current arrangement.
2. **The half-implementation was already pointing this way**. `Product.emitApprovedVendorListChanged(...)` existed before this slice; the persistence was just lagging behind.
3. **The load cost is bounded**. Approved-vendor lists are typically 1–5 entries per SKU. Loading them alongside the product is one extra query, not N+1, even in `findAll`. The read-side `ProductQueryPort` (which most listing screens use) is separate from `ProductRepository.findById` and unaffected by this change.

### Convention-compliance state after §2.17

The known-exception list shrinks from three offenders to **one**: `BomEditRepository` (§2.16). The audit started with 5–6 candidates; §2.17 + §2.18 + §2.19 cleared four of them in different ways:

- §2.17 — promote (`SupplierProductPrice`) or fold (`ApprovedVendor`)
- §2.18 — rename to `*QueryPort` (`Routing`, `Supplier`) when there are no mutators
- §2.19 — relax the rule for event-less write-once aggregates (`JournalEntry`)

### Smoke

- `mvn -DskipTests clean compile` ✅ 19/19 modules green
- `mvn -DskipTests test-compile` ✅ 19/19 modules green
- `mvn -pl purchasing-service test` ✅ 79 tests pass (14 new `SupplierProductPriceTest` + 6 reshaped `SupplierProductPriceServiceTest` cases)
- `mvn -pl product-service test` ✅ 87 tests pass (5 reshaped `ApprovedVendorList` cases; ProductServiceTest's 3 SetApprovedVendors cases reshaped to use the aggregate directly)
- `mvn -pl test-harness test` ✅ 8/8 E2E tests pass

---

## 2026-05-16 — §2.18 + §2.19: rename read-only repos to `*QueryPort`, relax convention for event-less aggregates

Two follow-on slices off the 2026-05-15 *no-`*Repository`-without-an-aggregate* rule. Both are convention compliance: §2.18 retires two `*Repository` interfaces whose targets weren't real DDD aggregates; §2.19 relaxes the rule's third clause so it cleanly covers the one legitimate event-less aggregate in the codebase.

### §2.19 — convention amended to cover event-less aggregates (event-less write-once aggregates permitted)

`finance.JournalEntry` is a real aggregate root by every other measure — factories, identity VO (`JournalEntryId`), `version` for optimistic concurrency, balanced-line invariants enforced at construction — but never emits events through `pendingEvents`. Journal entries are append-only; the balance invariant is carried by the DB trigger `enforce_journal_balance`; "reversal" is itself a new posted entry rather than a mutation of the original. The previous rule wording (*"emits domain events into `pendingEvents` drained by the repository at `save()`"*) read as a hard requirement and would have flagged `JournalEntryRepository` as a false-positive offender.

Updated:
- `docs/conventions.md` → the `*Repository` rule now says *"Where the aggregate has post-creation behaviour, that behaviour is expressed as intent-named mutators on the root and the events they emit are drained from `pendingEvents` to the outbox by the repository at `save()` in the same transaction. **Event-less aggregates are permitted** when the aggregate is write-once (factory-only, no mutators, no events) and the invariant is enforced elsewhere — `finance.JournalEntry` is the exemplar."*
- `CLAUDE.md` → one-liner updated to match: mutating aggregates drain `pendingEvents` at `save()`; event-less write-once aggregates are permitted.
- `JournalEntry.AGGREGATE_TYPE = "JournalEntry"` added on the aggregate. No outbox writers currently reference it (the aggregate is event-less), but the constant exists because the aggregate root's identity remains the anchor for audit-log + reporting references.

No code outside `JournalEntry` touched. Cheap, clarifying change.

### §2.18 — `RoutingRepository` / `SupplierRepository` → `*QueryPort`

Two `*Repository` interfaces whose target classes (`Routing`, `Supplier`) had no mutators, no `pendingEvents`, no events. Their Javadocs admitted it (*"Manufacturing doesn't write through this class today"*, *"Supplier read model. Phase 1 only needs a lookup endpoint"*). They were `*QueryPort` in everything but name.

Files renamed (and moved per the QueryPort convention — interface in `application/`, impl in `infrastructure/persistence/`):

| Before | After |
|---|---|
| `manufacturing.domain.RoutingRepository` | `manufacturing.application.RoutingQueryPort` |
| `manufacturing.infrastructure.persistence.JdbcRoutingRepository` | `manufacturing.infrastructure.persistence.JdbcRoutingQueryPort` |
| `purchasing.domain.SupplierRepository` | `purchasing.application.SupplierQueryPort` |
| `purchasing.infrastructure.persistence.JdbcSupplierRepository` | `purchasing.infrastructure.persistence.JdbcSupplierQueryPort` |
| `testharness.inmemory.manufacturing.InMemoryRoutingRepository` | `testharness.inmemory.manufacturing.InMemoryRoutingQueryPort` |
| `testharness.inmemory.purchasing.InMemorySupplierRepository` | `testharness.inmemory.purchasing.InMemorySupplierQueryPort` |

The `Routing` and `Supplier` read-model classes stay in `domain/` as plain data shapes (could move to `application/dto/` as `*View` records — left for a future slice, not load-bearing).

Importer sites updated:
- `WorkOrderReleaseService` (manufacturing) — drops `domain.RoutingRepository` import; uses `RoutingQueryPort` directly (same package).
- `SupplierService`, `PurchaseOrderService`, `PurchaseRequisitionService` (purchasing) — drop `domain.SupplierRepository` imports; use `SupplierQueryPort` directly (same package). `SupplierService` Javadoc clarified: it's now described as a service over the supplier *read model*, not the supplier aggregate.
- `ManufacturingTestKit`, `PurchasingTestKit` (test-harness) — import the renamed in-memory class.

Field names (`routings`, `suppliers`) unchanged — they followed the full-aggregate-plural rule already and that rule is type-agnostic.

### Convention-compliance state after both slices

`docs/conventions.md` known-exception list shrinks from "potentially 5–6 offenders" to **three real offenders**: `BomEditRepository` (§2.16), `SupplierProductPriceRepository` + `ApprovedVendorRepository` (§2.17). All three are row-level write ports without backing aggregate roots — fundamentally different shape from the false-positive offenders cleared by §2.18 / §2.19.

### Smoke

- `mvn -DskipTests clean compile` ✅ 19/19 modules green in 12.3s.
- `mvn -DskipTests test-compile` ✅ 19/19 modules green in 10.1s — confirms the test-harness rename links cleanly.
- Grep audit: `**/domain/*Repository.java` returns 17 files; all 17 either back an aggregate root in the same package declaring `AGGREGATE_TYPE`, or are the 3 tracked offenders.

---

## 2026-05-16 — `AGGREGATE_TYPE` constants on all 9 missing aggregates (Tier 4 sweep)

Codebase-wide audit triggered by 2026-05-15's *no-`*Repository`-without-an-aggregate* rule found that only 4 of 13 aggregate roots declared `AGGREGATE_TYPE`. Outbox writers were stamping the column with inline string literals (`"Customer"`, `"PurchaseOrder"`, etc.) — exactly the string drift the constant rule exists to prevent. This slice closes the gap on the trivially-fixable subset.

### Aggregates with `AGGREGATE_TYPE` constant added (9)

| Service | Aggregate | Wire value |
|---|---|---|
| sales | `Customer` | `"Customer"` |
| inventory | `StockItem` | `"StockItem"` |
| inventory | `GoodsReceipt` | `"GoodsReceipt"` |
| inventory | `Shipment` | `"Shipment"` |
| purchasing | `PurchaseOrder` | `"PurchaseOrder"` |
| purchasing | `PurchaseRequisition` | `"PurchaseRequisition"` |
| finance | `SupplierInvoice` | `"SupplierInvoice"` |
| finance | `CustomerInvoice` | `"CustomerInvoice"` |
| finance | `Payment` | `"Payment"` |

Each constant placed per the class-member-ordering rule (after nested types, above other static fields). Javadoc consistent with the existing pattern on `Product` / `SalesOrder` / `StockReservation` / `WorkOrder`.

### Producer-side outbox writers updated (8 + 6 test-harness)

Producer-side `JdbcXxxRepository.save(...)` calls switched from literal to constant — `JdbcCustomerRepository`, `JdbcGoodsReceiptRepository`, `JdbcShipmentRepository`, `JdbcPurchaseOrderRepository`, `JdbcPurchaseRequisitionRepository`, `JdbcSupplierInvoiceRepository`, `JdbcCustomerInvoiceRepository`, `JdbcPaymentRepository`. Each file already imported its aggregate class; no new imports needed.

In-memory test-harness counterparts updated the same way: `InMemoryShipmentRepository`, `InMemorySupplierInvoiceRepository`, `InMemoryPaymentRepository`, `InMemoryCustomerInvoiceRepository`, `InMemoryPurchaseOrderRepository`, `InMemoryPurchaseRequisitionRepository`.

`StockItem` has no outbox call sites today (the JDBC adapter's Javadoc notes inventory doesn't yet emit projection events for it); the constant is declared so future inventory-originated events can reference it directly.

### Smoke

- `mvn -DskipTests clean compile` ✅ all 19 modules green in 10.7s.
- `mvn -DskipTests test-compile` ✅ all 19 modules green in 10.9s — confirms the test-harness in-memory repository changes link cleanly.
- Grep audit: `"(Customer|StockItem|GoodsReceipt|Shipment|PurchaseOrder|PurchaseRequisition|SupplierInvoice|CustomerInvoice|Payment)"` across `**/main/**/*.java` returns only the new `AGGREGATE_TYPE` constant declarations — no producer-side literals remain.

### Follow-ups carried forward

- **§2.17** — Tier 1 promotions: `SupplierProductPrice` + `ApprovedVendor` (similar shape to §2.16 `Bom`).
- **§2.18** — Tier 2 renames: `RoutingRepository` / `SupplierRepository` → `*QueryPort` while no mutators exist.
- **§2.19** — Tier 3 wording fix: relax the rule's third clause to cover legitimate event-less aggregates (`JournalEntry`).
- **§2.20** — Cross-service consumer-test literals: ~14 sites still use literal strings because they can't import the producer's aggregate; resolved by extending the AGGREGATE_TYPE-on-event-class convention to every cross-service-consumed event.

## 2026-05-15 — Sharper convention: no `*Repository` without an aggregate

Locked in the rule that every `*Repository` interface in `<service>.domain/` must have a sibling aggregate root in the same package declaring `public static final String AGGREGATE_TYPE`. Surfaced 2026-05-15 while drafting `docs/domain-driven design.html`: `manufacturing.BomEditRepository` is today's only offender — uses the `*Repository` suffix but is a row-level write port for `bom_header` / `bom_line` with invariants (acyclic graph, single-active-per-product, no-edit-on-active) sitting in `BomEditService` + `BomCycleDetector` + the DB partial unique index `uq_bom_active_per_product`, not on an aggregate.

For a showcase codebase, a `*Repository` that doesn't carry the DDD contract (aggregate root + intent-named mutators + `pendingEvents` drained by `save()`) misleads every reader who's internalised the convention from the rest of the codebase. The right answer is to promote (Option 1 in the rule) or pick a different suffix (Option 2). Re-using `*Repository` is forbidden.

### Shipped

- **`CLAUDE.md` → *Naming summary*.** Added the one-line rule + pointer to `docs/conventions.md` for the full version. Notes `BomEditRepository` as the known exception tracked in §2.16.
- **`docs/conventions.md` → new `*Repository` rule subsection.** Sits right above the existing `*Projection` rule paragraph. Spells out the contract (`AGGREGATE_TYPE` + intent-named mutators + `pendingEvents` drain), the two legitimate options (promote or pick a different suffix), the mechanical naming check (`find **/domain/*Repository.java` ↔ files declaring `AGGREGATE_TYPE` in the same package), and the BomEditRepository known-exception callout.
- **`docs/domain-driven design.html`.** Aggregate inventory table (§6) drops the `BomEdit` cell — `Routing` stays as the sole manufacturing aggregate alongside `WorkOrder`. New warn callout under the inventory table calls out the BOM situation. Anti-patterns table (§13) adds the row "`*Repository` without a backing aggregate" with the new convention as the avoidance mechanism.
- **`docs/dev-todo.md` → §2.16.** Backlog entry for the BOM aggregate promotion itself: new `Bom` root + `BomId` + `Status` enum + `BomLine` + `BomRepository`, `BomCycleDetector` passed into `activate(...)` as a domain-service method parameter, `ActiveBomChanged` emitted from inside `Bom.activate(...)`, `JdbcBomEditRepository` retired, `BomTest` covering guards.

No code touched in this slice — convention + docs only. The actual promotion (a real refactor with new aggregate + tests + JDBC repository) is §2.16 and ships as its own slice.

Closes the four `demo-web-ui` command-driver gaps surfaced by the 2026-05-15 cross-SPA audit (see `dev-todo.md` §1G). Each story already had its REST endpoint shipped; the gaps were SPA-only — no fetcher, no form, no button. Bundled into one slice because all four touch the same two files (`api/commands.ts` + a route component) and share the existing modal / FieldRow / FormStatus toolkit.

### Shipped (in order)

- **§1G.2 — Story 1.5 make-vs-buy toggle.** New `changeProductMakeVsBuy(productId, {isPurchased, isManufactured})` wrapper around `PUT /api/products/{id}/make-vs-buy`. New `MakeBuyPanel` as a fourth tab on `ProductEditButton`'s modal in `Products.tsx`, mirroring `erp-web-ui`'s `MakeVsBuyDialog` (three-option select: manufactured / purchased / both). Defaults to `manufactured` because `ProductView` doesn't expose the current flags.

- **§1G.3 — Story 4.1 cancel-order button.** New `cancelSalesOrder(orderId, {reason})` wrapper hitting `POST /api/sales-cmd/sales-orders/{id}/cancel`. `SalesOrders.tsx` detail panel gains a destructive **Cancel order** button shown only when the order's `orderStatus` isn't in `{shipped, completed, cancelled, rejected}` (server enforces the same set with 409). Modal collects the reason; success invalidates the `sales-orders` query so the status badge flips immediately.

- **§1G.4 — Story 6.1 PO-approve action.** New `approvePurchaseOrder(poId, {approver, reason})` wrapper hitting `POST /api/purchase-orders-cmd/{id}/approve` (the BFF's command-side alias for purchasing). `PurchaseOrders.tsx` detail panel gains an **Approve PO** button shown only when `poStatus === 'draft'`. Defaults the approver to `tom` for one-click happy-path demos; modal copy explains shortage-driven POs auto-approve (so the button matters mostly for manually-raised PRs).

- **§1G.1 — Story 1.1 register product form.** New `createProduct(request)` wrapper hitting `POST /api/products`. New `RegisterProductButton` at the top of `Products.tsx` next to the SKU count; opens a `RegisterPanel` modal with SKU / Name / Description / ProductType / BaseUoM / SalesPrice / StandardCost / Currency fields. UoMs are hardcoded from the seed (EA / L / KG) — mirrors `erp-web-ui/ProductNew.tsx`'s `UOMS` constant; no UoM admin page exists in either SPA.

### Side effect: refreshed `DiscontinuePanel` footer

While in `Products.tsx`, fixed the stale post-§1.4 comment on `DiscontinuePanel` that still claimed "Today no consumer reacts." The §1.4 closure shipped 2026-05-14/15 — six services now consume `ProductDiscontinued`. New footer enumerates the actual reactions (sales rejects new lines, manufacturing retires replenishment + cascade-clears parent BOMs, purchasing's PR gate, finance / inventory / reporting stamp `discontinued_at`).

### Files touched

- `demo-web-ui/src/api/types-commands.ts` — adds `ChangeMakeVsBuyRequest`, `CreateProductRequest`, `CancelSalesOrderRequest`, `ApprovePurchaseOrderRequest`.
- `demo-web-ui/src/api/commands.ts` — adds the four fetchers + their `import type` lines.
- `demo-web-ui/src/routes/Products.tsx` — adds `RegisterProductButton`, `RegisterPanel`, `MakeBuyPanel`, fourth tab on the edit modal, hardcoded `UOMS` + `PRODUCT_TYPES` (mirror of erp-web-ui). Stale Discontinue footer fixed.
- `demo-web-ui/src/routes/SalesOrders.tsx` — adds `CancelOrderButton` component + `NON_CANCELLABLE` set; detail header carries the button in the right-side slot when applicable.
- `demo-web-ui/src/routes/PurchaseOrders.tsx` — adds `ApprovePoButton` component; detail header carries the button when `poStatus === 'draft'`.

### Smoke

`npm run typecheck` clean. `npm run build` produces a ~413 kB JS bundle (was ~411 kB; +1.4 kB for the new components). Manual UI walkthrough deferred — backend paths are unchanged and the patterns mirror existing working forms in `erp-web-ui`.

### Out-of-scope at end of slice

- **§1G.5 erp-web-ui scenario runner** — bigger one-day slice, parked per `dev-todo.md` §1G's "pull forward only if an operational-SPA-only audience wants the orchestrated walkthrough."

---

## 2026-05-15 — Close §2.1 Reporting follow-ups + Story 2.2

Conversation-surfaced cleanup: dev-todo §2.1 carried two open bullets — `wip_value` on the dashboard and the three scheduling-date columns on `production_planning_board`. Both are explicitly parked-indefinitely items waiting on capabilities Northwood deliberately doesn't model (costing-method decision; scheduling module). They duplicate text already in user-stories.md's Out-of-scope blocks for Stories 2.6 and 2.2 respectively. Net effect of leaving them in §2.1: backlog readers think there's active work; closing them clarifies that there isn't.

### dev-todo §2.1

Section header flipped to ✅ COMPLETE 2026-05-15. Three shipped bullets collapsed to one line (single 2026-05-12 slice). The two parked items moved to a single block under "Parked indefinitely" with cross-refs to the Out-of-scope blocks on Stories 2.6 / 2.2 where the parking is already documented.

### Story 2.2 → ✅

Removed the stale ⏳ acceptance-criterion bullet for scheduling-date columns (the same information already lived in this Story's *Out-of-scope* block, so the ⏳ was a duplicate that left the Story incorrectly flagged as partial). Story now flips 🚧 → ✅. The *Out-of-scope* bullet's text expanded slightly to make the "no planning module = no honest data = column stays null" reasoning self-contained, so future readers don't need to follow the §2.1 cross-ref to understand the parking.

### Docs only

No code change. `docs/dev-todo.md` and `docs/user-stories.md` updated.

---

## 2026-05-15 — Close Story 4.2: partial-rejection rejects the whole order + saga terminal cleanup

Closes Story 4.2 (was 🚧 → ✅). The single ⏳ bullet (synchronous POST error) was minor compared to the silent-correctness gap underneath: when `manufacturing.ManufacturingDispatched` arrived with some lines accepted and some `rejected_no_bom` / `rejected_not_manufactured`, the saga stamped `expectedWorkOrderCount = acceptedCount` and **proceeded with only the accepted lines**. The order eventually completed with `total_amount` reflecting the originally-ordered total while only the accepted lines actually shipped — directly contradicting the story's stated goal ("fail clearly with no half-fulfilled state").

### Policy decision

**Any rejected line rejects the whole order.** Partial fulfilment is not a state the system represents. Sarah re-places the order with just the manufacturable SKUs if she still wants those. Aligns with the saga's "fail clearly" goal; matches the existing all-rejected behaviour but extends to all partial-rejection cases.

### Saga state machine

- `JdbcSalesOrderFulfilmentSagaManager.applyManufacturingDispatched` — condition flipped from `!anyAccepted` to `acceptedCount < totalLines`. On any rejection: `manufacturing_requested → stock_reservation_failed` with `current_step` distinguishing `no_manufacturable_lines` (all rejected) or `partial_dispatch_rejection` (mixed). Comment block above the method documents the policy.
- `SalesOrderFulfilmentSaga.TERMINAL_STATES` now includes `STOCK_RESERVATION_FAILED`. Was in `ALL_STATES` but not terminal — `transitionTo(STOCK_RESERVATION_FAILED, ...)` now properly sets `completed_at`.

### Compensation flow

- New method `SalesOrderCompensationEmitter.emitCancellationRequest(orderId, reason)`. Emits `sales.SalesOrderCancellationRequested` to release any partial stock reservation (inventory's cancel handler) and cancel the make-to-order sagas already started for the accepted lines (manufacturing's cancel handler). Reuses the same event as user-initiated cancel-order; downstream handlers are agnostic to the trigger. Reason field carries the per-line rejection summary, e.g. `"1 of 3 line(s) rejected: FG-X (rejected_no_bom); order cannot be partially fulfilled."`
- The existing cancel-ack handlers ignore acks when the saga isn't in `compensating`. Sales saga is already at terminal `stock_reservation_failed`, so compensation is **best-effort cleanup** — manufacturing and inventory release/cancel correctly; sales doesn't track the acks. No `SalesOrderCompensated` emission for this path. Matches the "rejection terminates immediately" intent.

### Handler wiring

- `ManufacturingDispatchedHandler` injects `SalesOrderCompensationEmitter`. On `stock_reservation_failed` return from the manager: stamps order header `'rejected'` (existing) + emits cancellation request (new). Builds human-readable rejection reason listing rejected SKUs and outcomes.
- `SalesTestKit` updated to thread the emitter through.

### Tests

- `JdbcSalesOrderFulfilmentSagaManagerTest.ApplyManufacturingDispatched`:
  - `all_rejected_flips_to_stock_reservation_failed` — extended to assert `current_step = "no_manufacturable_lines"`.
  - `partial_rejection_flips_to_stock_reservation_failed` (NEW) — asserts the policy change for 2-of-3-accepted.
  - `all_accepted_stamps_expected_count` (renamed from `any_accepted_...`) — happy path: 3/3 → saga proceeds with `expected_wo_count = 3`.
- `ManufacturingDispatchedHandlerTest`:
  - `happy_path_all_accepted_no_rejection_side_effects` (renamed) — 3/3 accepted → no markStatus, no cancellation emission.
  - `all_rejected_marks_status_and_emits_cancellation` — extended with cancellation emitter assertion.
  - `partial_rejection_marks_status_and_emits_cancellation` (NEW) — new policy: 2/3 accepted still triggers full rejection. Reason string contains `"1 of 3 line(s) rejected"`.
  - `already_processed_short_circuits` — extended with no-cancellation assertion on dedup.

### Docs

- `docs/user-stories.md` Story 4.2 rewritten: title broadened to "Order can't be fully fulfilled (reservation or dispatch failure)"; flipped 🚧 → ✅; three-branch narrative (partial-reservation, all-rejected, partial-rejected). New *Out-of-scope* block lists four documented deferrals (sync POST error, SO360 shortage projection, `'rejected'` line status, reporting `order_status = 'rejected'` flip).
- No demo-script changes needed — Demo 4.2's narrative is high-level ("fail clearly") which the new policy now actually delivers.

### Smoke

`mvn install -DskipTests` reactor green; `mvn test` 8/8 harness + every service green. The existing harness E2E tests don't exercise the partial-rejection path; the unit-level coverage above is the regression net.

### Out-of-scope at end of slice (captured under Story 4.2)

- Synchronous error to the original `POST /api/sales-orders` (fundamentally async — manufacturing dispatch round-trip is what reveals rejection).
- `sales_order_360_view.has_shortage` + `shortage_summary` projection (dead JSON today).
- `'rejected'` terminal on `sales_order_line.line_status` (order header carries the verdict; line-level redundant for this slice).
- Reporting SO360 `order_status` flip to `'rejected'` (today only flips to `'cancelled'` via `SalesOrderCompensatedHandler`; rejection path doesn't emit a compensated event).

---

## 2026-05-15 — Close Story 2.6: daily balance snapshots via rollup worker + snapshot DTO uniformity

Closes Demo 2 / Story 2.6 (was 🚧 → ✅). Conversation-surfaced gap audit identified four real issues hiding under the single `wip_value` ⏳ bullet:

1. **Daily AR / AP / inventory rows always 0.** The schema reserves four balance columns but no handler wrote them; only the as-of-now `/snapshot` endpoint computed AR / AP / inventory via SUM-window. Historical balance queries (`GET /api/financial-dashboard/{date}`) returned all-zero balance columns regardless of the date — Daniel couldn't answer "what was AR on 2026-05-08?"
2. **`open_*` counters were monotonic "placed-on-this-date" tallies, not balances.** Three handlers (`SalesOrderPlacedHandler`, `PurchaseOrderCreatedHandler`, `WorkOrderCreatedHandler` in `dashboard/`) did `COL = COL + 1` per event and never decremented. The column name `open_sales_orders_count` promised balance semantics; the handler delivered a "created on day X" tally.
3. **`FinancialDashboardSnapshot` DTO lacked `wipValue`.** Asymmetric with `FinancialDashboardView`; SPA's "As of now" row couldn't render a WIP tile.
4. **Currency mismatch in `findSnapshot`** between transaction-currency (AR / AP) and product-valuation-currency (`inventory_value`) was undocumented.

### Implementation

**Rollup worker (B.1 core fix):**
- New `FinancialDashboardProjection.refreshDailyBalances(LocalDate, String currency)` method. Single round-trip UPSERT with four scalar sub-selects feeding the six balance columns (AR + open-SO from SO360; AP + open-PO from PO tracking; inventory_value from ATP × `product_standard_cost`; open-WO count from `production_planning_board`). On conflict, only the balance columns are overwritten — flow columns owned by the event handlers stay intact.
- New `infrastructure/dashboard/FinancialDashboardBalanceWorker` — `@Scheduled(fixedRateString = "${northwood.reporting.dashboard.balanceRefreshIntervalMs:60000}")`. Refreshes today's row for every configured currency (default: AUD only). Failure-tolerant: catches `RuntimeException` per currency so a transient connectivity glitch doesn't kill the whole tick.
- `JdbcFinancialDashboardQueryPort.findSnapshot` retained as the as-of-now realtime view — same SQL logic the worker runs, but synchronous per request. Two paths intentional: snapshot endpoint = always-fresh on demand, daily row = balance as of last worker tick. The SPA hits both (snapshot tile + per-day grid) so the user sees real-time on top and historical-by-row underneath.

**Counter cleanup (B.2 fix via removal):**
- Deleted `reporting/.../inbox/dashboard/SalesOrderPlacedHandler.java`, `PurchaseOrderCreatedHandler.java`, `WorkOrderCreatedHandler.java`. These were the increment-only counter handlers; their semantics was wrong on day 2. The rollup worker now owns all balance columns.
- Removed `recordSalesOrderPlaced` / `recordPurchaseOrderCreated` / `recordWorkOrderCreated` from `FinancialDashboardProjection` + impl.

**DTO uniformity (B.3 fix):**
- `FinancialDashboardSnapshot` gains a `wipValue BigDecimal` field. Always returns `BigDecimal.ZERO` from `findSnapshot`; commented as parked pending the costing-method decision. Wire shape now uniform with `FinancialDashboardView`.

**Doc fix (B.4):**
- `JdbcFinancialDashboardQueryPort.findSnapshot` Javadoc now documents the dual currency semantics (transaction currency for AR / AP / open-SO / open-PO; product valuation currency for `inventory_value`; currency-blind for `openWorkOrdersCount`). AUD-only demo doesn't bite; documented for multi-currency rollouts.

**SPA update:**
- `erp-web-ui/.../FinancialDashboard.tsx` adds three new columns to the *Recent days* grid: `AR @ EOD`, `AP @ EOD`, `Inventory @ EOD`. Picks them up directly from the per-day rows the worker now populates.
- Footer rewritten: explains the rollup cadence + the parked `wip_value`. The TS interface adds `wipValue` to `FinancialDashboardSnapshot`.

### Tests

- `mvn test` full reactor green (no test changes — the existing E2E harness drives the flow-column writers, and the rollup worker is exercised on every real run).
- `npm run typecheck` in `erp-web-ui` clean.
- No new unit tests for `refreshDailyBalances` — the SQL is essentially the same logic as `findSnapshot` which was already in production, and a Testcontainers IT for this would duplicate the per-handler IT pattern from §2.5 Phase C without adding incremental signal. Worth a follow-up if a regression surfaces.

### Docs

- `docs/user-stories.md` Story 2.6 rewritten: flipped 🚧 → ✅; hybrid flow-vs-balance shape documented inline; the misleading "Per-day open-* counts" bullet replaced with the rollup-worker description; `wipValue` parking now narrowly scoped to derivation only (DTO + tile + column all present). New *Out-of-scope* block lists five documented deferrals: `wip_value` derivation, currency mismatch, multi-currency SPA selector, customer-collections widget, snapshot caching.
- Three slightly stale items in the demo-script Demo 2 section are unaffected (it doesn't talk about per-day vs as-of-now distinction); no demo-script change needed.

### Smoke

`mvn install -DskipTests` reactor green; `mvn test` 8/8 harness + every service green; SPA typecheck clean. Live verification on the running stack — `GET /api/financial-dashboard/snapshot` returns non-zero AR / AP / inventory after a full O2C / P2P flow; `GET /api/financial-dashboard` shows the same numbers persisted onto today's row within 60 s.

### Out-of-scope at end of slice (captured under Story 2.6)

- `wip_value` derivation (costing decision parked).
- Multi-currency SPA selector + per-column currency-context API.
- Customer-collections widget reading `reporting.customer_dashboard_status`.
- Snapshot caching / materialised-view backing.

---

## 2026-05-15 — Story 2.2 staleness pass + ProductionPlanningQueryPort Javadoc fix

Conversation-surfaced gap-audit on Story 2.2 (Production Planning Board, marked 🚧). The ⏳ bullet ("list endpoint to see all open work orders at once") turned out to be stale — `ProductionPlanningController.list()` exposes `GET /api/work-orders` and has done so since the §1B SPA-pages slice. The "5 events from manufacturing + inventory" claim was also an undercount: 10 events across 4 services actually feed the projection (manufacturing 6, inventory 2, purchasing 1, finance 1; the last three feed `setOpenPoCount` so the board shows live PO progress per WO).

### Behaviour change considered, then rejected

The audit initially flagged "list endpoint returns every status including `cancelled`" as a behaviour gap — its `findAll()` Javadoc claimed "All open work orders" but the SQL had no `WHERE` clause. Tracing the consumers found two:
- `erp-web-ui/.../ProductionBoard.tsx` (3-lane Kanban) filters client-side to `released` / `in_progress` / `completed`, so cancelled rows simply don't render.
- `erp-web-ui/.../WorkOrders.tsx` (table view) exposes a status dropdown that explicitly includes `cancelled` for cancellation audit; it depends on the endpoint surfacing cancelled rows.

Server-side filtering would silently break the table view. Correct fix is to update the **Javadoc** to match the (correct) behaviour: "All work orders across every status (including cancelled), newest activity first. Two SPA consumers slice client-side." Lesson captured in the comment so future readers don't try the same wrong simplification.

### Prose pass on Story 2.2

- Title intent updated: "see all work orders" (was "see all open work orders" — the codebase explicitly surfaces cancelled too).
- Trigger line lists both endpoints (per-WO + list) without the stale ⏳ parenthetical.
- Acceptance criteria expanded with the columns the schema actually populates: priority, `open_purchase_orders_count`, `material_status` (now includes `shortage`). Cancelled status added to the visible-statuses list.
- New ✅ bullet documenting the list endpoint + the two-consumer slicing pattern.
- New ⏳ bullet (the only remaining one) calls out scheduling-date columns staying null — points at `dev-todo.md` §2.1 for the parked planning-module slice.
- *Events consumed* section rewritten to enumerate all 10 events across 4 services.
- New *Out-of-scope* block lists three documented deferrals: scheduling dates, sub-assembly child grouping, no retention policy. Sub-assembly grouping noted as the bigger-slice follow-up that needs a `parent_work_order_id` column on the projection + SPA tree rendering.

Story stays 🚧 (scheduling-date ⏳ is real) but is now narrowly scoped to that one parked item rather than the misleading "list endpoint missing" gap that was already shipped.

### Files

- `reporting-service/.../application/ProductionPlanningQueryPort.java` — Javadoc on `findAll()` rewritten.
- `docs/user-stories.md` — Story 2.2 rewritten as described.

### Smoke

No behaviour change, no test impact. `mvn install -DskipTests` reactor green.

---

## 2026-05-15 — Close Story 1.4 gaps: BOM hygiene on component discontinue + editor gate + prose pass

Closes Demo 1 / Story 1.4 (was 🚧 → ✅) on two real behaviour gaps plus a prose pass on two stale ⏳ bullets. Cataloged in user-stories.md's gap-pass earlier today as B.2 (parent-BOM cascade) + B.3 (`BomEditService.addLine` gate). Before this slice:
- Discontinuing a raw material left every parent FG's active BOM intact; make-to-order would try to reserve the discontinued RM, succeed if stock remained, eventually shortage with no replenishment.
- `BomEditService.addLine` had cycle detection but no discontinued-component check, so a planner could author a draft BOM that named a retired SKU.

### Schema

- `manufacturing.product_replenishment` gains `discontinued_at TIMESTAMPTZ` (nullable). Distinct signal from the `(is_purchased=false, is_manufactured=false)` pair, which can also occur on a freshly-seeded never-classified row. Both the baseline `db/northwood_erp.sql` and a new Liquibase changeset `manufacturing/.../2026-05-15-add-product-replenishment-discontinued-at.sql` apply the column for fresh and existing dev DBs respectively.

### Behaviour changes

- `JdbcProductReplenishmentProjection.applyDiscontinued` now writes a single upsert: flips both flags to `false` and stamps `discontinued_at = now()`. `COALESCE(existing, now())` on the conflict path so a redelivered discontinue doesn't churn the timestamp.
- New `manufacturing.application.DiscontinuedProductLookup` interface + `JdbcDiscontinuedProductLookup` impl reading `product_replenishment.discontinued_at IS NOT NULL`. Twin of purchasing's existing pattern.
- `BomEditService.addLine` injects `DiscontinuedProductLookup`; throws new nested `BomComponentDiscontinuedException` when the component is discontinued. Cycle detection still runs after the gate so a same-product self-loop and a discontinued-component case both surface clean exceptions.
- `manufacturing.application.inbox.ProductDiscontinuedHandler` injects `BomLookup` and (after the existing `replenishment.applyDiscontinued` + `activeBom.apply(productId, null)` calls) walks `boms.findParentProductIdsByComponent(productId)` to cascade-clear `product_active_bom` on every parent FG whose active BOM references the discontinued product. WARN-logged per affected parent. The reverse-walk query was already present (added 2026-05-08 for the §2.8 Slice D cost rollup), so this is purely consumer-side wiring.

### Test wiring

- `manufacturing.application.inbox.ProductDiscontinuedHandlerTest` extended: new `cascades_clear_to_parent_active_boms` case + verifies the no-cascade happy path remains intact. Mock `BomLookup` injected.
- `manufacturing.application.BomEditServiceTest` adds `rejects_when_component_is_discontinued` case + injects mock `DiscontinuedProductLookup` to the existing constructor calls.
- `test-harness/.../kits/ManufacturingTestKit` updated for both new constructor signatures. `InMemoryProductReplenishmentProjection` now also implements `DiscontinuedProductLookup` (single fake serves both ports in the harness, while production splits them). Registers `ProductDiscontinuedHandler` on the bus (was unwired before — pre-existing gap closed alongside).

### Docs

- `docs/user-stories.md` Story 1.4 flipped 🚧 → ✅. Two stale ⏳ bullets dropped:
  - "Existing draft orders are not retroactively flagged" — `SalesOrder` has no `DRAFT` constant; `placeOrder` lands directly at `'submitted'`. The bullet described a state the codebase doesn't produce.
  - "Reorder rules don't yet stop generating purchase suggestions" — the gap was the absence of a reorder-suggestion *producer*, not the suppression. Restated as out-of-scope.
  
  New explicit bullet: **"In-flight is allowed to complete"** documents the deliberate design choice (gate runs at entry points only). New *Out-of-scope* block lists the four real deferrals (no reactivation path, no auto-reorder job, no activate-time discontinued-component check, SPA visual treatment).

### Smoke

`mvn install -DskipTests` reactor green; `mvn test` full reactor green including the Testcontainers end-to-end suites. Test-harness 8/8 (the `ManufacturingTestKit` change exercises the cascade implicitly through the saga lifecycle tests).

### Still ⏳ / out-of-scope at end of slice (captured under Story 1.4's *Out-of-scope*)

- Reactivation path (`Product.discontinue` is one-way; no command writes `Status.INACTIVE`).
- Auto-reorder-suggestion job (no producer reads `reorder_point` / `reorder_quantity`).
- Activate-time discontinued-component check in `BomEditService.activate` (one extra lookup per component; cheap follow-up).
- SPA UI greying / hiding of discontinued rows (frontend concern; the read-side flag is stamped and ready).

---

## 2026-05-15 — Close Story 6.1 gaps: `finance.SupplierInvoiceRejected` + P2P saga `failed` terminal + dead-state cleanup

Closes the two cheapest gaps under Demo 6 / Story 6.1's 🚧 flag (cataloged in user-stories.md as B.2 + B.3 in the gap pass earlier today). Before this slice, `SupplierInvoice.manualReject` flipped the invoice to `cancelled` but emitted no event, so the P2P saga parked at `goods_received` forever; the saga also carried two dead constants (`MANUAL_REVIEW_REQUIRED` not in `ALL_STATES`; `FAILED` written by nothing) plus a much wider schema CHECK list of states code never wrote (`supplier_invoice_received`, `three_way_match_pending`, `three_way_match_passed`, `three_way_match_failed`, `purchase_order_closed`, `manual_review_required`).

### Domain event + aggregate change

- New `finance-events/.../events/SupplierInvoiceRejected.java` with fields `eventId, aggregateId, internalInvoiceNumber, supplierInvoiceNumber, purchaseOrderHeaderId, supplierId, supplierName, reason, occurredAt`. Twin event of `SupplierInvoiceApproved`; both are emitted from the `SupplierInvoice` aggregate and persisted via the same outbox path (`JdbcSupplierInvoiceRepository.writeOutbox`, aggregate-type `"SupplierInvoice"` — no infra change needed).
- `SupplierInvoice.manualReject(reason)` now adds the event to `pendingEvents`. The preceding `status = CANCELLED` and the precondition guard are unchanged.
- `SupplierInvoiceApproved` Javadoc refreshed to mention the twin event and drop the stale "manual review tooling lands in a later slice" line.

### Saga manager + handler

- `PurchaseToPaySagaManager.applySupplierInvoiceRejected(UUID)` — `goods_received → failed` with `currentStep = "supplier_invoice_rejected"`. Ignored from any other state.
- `JdbcPurchaseToPaySagaManager` implements the new method, mirroring the existing `applySupplierInvoiceApproved` shape.
- New `purchasing-service/.../application/inbox/SupplierInvoiceRejectedHandler.java` consumer `purchasing.p2p.supplier-invoice-rejected` — minimal: delegates to the manager, logs the reason. No projection writeback (PO header stays in whatever state it's in; PO-cancellation is a separate deferred concern, captured on Story 6.1).
- `PurchasingTestKit` registers the new handler on the bus alongside the existing `SupplierInvoiceApprovedHandler` / `SupplierPaymentMadeHandler`.

### Dead-state cleanup

- `PurchaseToPaySaga.MANUAL_REVIEW_REQUIRED` constant + its entry in `TERMINAL_STATES` removed. `ALL_STATES` already contained `FAILED`; now it's actually written by code, closing the dead-state smell.
- Class Javadoc rewritten to enumerate the live happy + side-rail paths (was Phase 2/3/4 lineage that no longer matches the current reality).
- Schema CHECK on `purchasing.purchase_to_pay_saga.saga_state` trimmed from 14 literals to 8 — only the states `PurchaseToPaySaga.ALL_STATES` writes (`started`, `purchase_order_approved`, `waiting_for_goods`, `goods_received`, `supplier_invoice_approved`, `supplier_payment_made`, `completed`, `failed`). Updated in both `db/northwood_erp.sql` (baseline) and a new Liquibase changeset `purchasing/.../2026-05-15-trim-purchase-to-pay-saga-states.sql` for existing dev DBs (drop + re-add the CHECK).
- `demo-web-ui/src/sagas/catalog.ts` purchase-to-pay entry trimmed: `forwardStages` drops `supplier_invoice_received` / `three_way_match_passed` / `purchase_order_closed`; `sideRailStates` reduces to just `failed`; `terminalStates` drops `manual_review_required`. The Saga Console UI now reflects the real state space.

### Tests added

- `SupplierInvoiceTest.ManualReject.emits_rejected_event` — replaces the previous `emits_no_event` test (which was asserting the wrong behaviour). Confirms the event carries `purchaseOrderHeaderId` and `reason`.
- `JdbcPurchaseToPaySagaManagerTest.ApplySupplierInvoiceRejected` — three cases: `goods_received → failed` (saved + currentStep set), unrelated state returns unchanged (no save), no-saga throws `IllegalStateException`.
- New `test-harness/.../p2p/PurchaseToPayRejectionPathTest.java` — end-to-end: PR → PO approve → goods receipt → variance-failing invoice (unit price 50 vs PO 25, 100% variance, far outside 2% default tolerance) lands invoice at `three_way_match_failed` and saga stays at `goods_received`; reviewer rejects → `SupplierInvoiceRejected` flows → saga lands at `failed` with `currentStep = "supplier_invoice_rejected"`. Mirrors the existing `PurchaseToPayHappyPathTest` shape; harness now has both terminal paths through P2P covered.

### Docs

- `docs/PurchaseToPaySaga.md` — added a *Side rail — manual rejection of a parked supplier invoice* section with the inline state diagram. Replaced *States declared but never written* with *State coverage* (now: every state in `ALL_STATES` is written by code).
- `docs/user-stories.md` — Demo 6 and Story 6.1 both flipped 🚧 → ✅. Flow step 4 updated to call out the manual-reject closure. *Known gaps* section dropped (the two ⏳ items closed by this slice are gone); the remaining *Out-of-scope* block lists the deferrals (PO-cancellation, over-receipt rejection, supplier price-list authoring narrative, multi-currency P2P).
- `docs/event-flow.html` — adds the `SupplierInvoiceRejected` entry under `finance.SupplierInvoice` with the purchasing consumer.
- `purchasing-service/application-kafka.yml` comment now enumerates all three finance.events consumers (approved / rejected / payment).

### Smoke

- `mvn install -DskipTests` reactor green.
- `mvn -pl finance-service,purchasing-service,test-harness test` — finance 112/112, purchasing all green, test-harness 8/8 (was 7; +1 for `PurchaseToPayRejectionPathTest`). Full reactor `mvn test` also green.

### Still 🚧/⏳ at end of slice (now captured under Story 6.1's *Out-of-scope* block)

- PO-cancellation flow (no `compensating` / `compensated` states on the saga).
- Over-receipt rejection (no `received ≤ ordered` validation in `GoodsReceiptService`).
- Supplier price-list authoring not narrated as a Story step.
- Multi-currency P2P deferred per the project-level memo.

---

## 2026-05-15 — §2.5.1 Slice D follow-up: drive `ShipmentService` + `PaymentService` through the test kits (no event injection)

Closes the only open item carried in §2.5. The §2.5.1 Slice D `OrderToCashHappyPathTest` originally drove `placeOrder` through the real `SalesOrderService` but injected `inventory.ShipmentPosted` and `finance.CustomerPaymentReceived` directly onto outboxes to skip wiring `ShipmentRepository` and `SalesOrderLineFactsProjection` in `InventoryTestKit`. The follow-up wires both.

### New in-memory adapters

- `test-harness/.../inmemory/inventory/InMemoryShipmentRepository.java` — implements `ShipmentRepository`; mirrors `InMemoryCustomerInvoiceRepository`'s pattern (event drainage to outbox on save with `"Shipment"` aggregate-type).
- `test-harness/.../inmemory/inventory/InMemorySalesOrderLineFactsProjection.java` — implements `SalesOrderLineFactsProjection`; `Map<UUID, UUID>` keyed on `sales_order_line_id`. Idempotent on redelivery.

### `InventoryTestKit` changes

- Constructs and exposes `ShipmentService` (`shipmentService` field) wired with `InMemoryShipmentRepository`, the existing `InMemoryStockBalances` / `InMemoryStockMovementWriter` / `InMemoryWarehouseLookup`, and the new `InMemorySalesOrderLineFactsProjection`.
- Registers `SalesOrderPlacedHandler` on the bus so `sales.SalesOrderPlaced` seeds `sales_order_line_facts` before `ShipmentService.post` validates against it.
- Class Javadoc updated to drop the stale "Goods-receipt / shipment paths aren't registered yet" caveat — only goods-receipt remains unwired.

### Test rewrite

`OrderToCashHappyPathTest` Step 4 now calls `inventory.shipmentService.post(new PostShipmentCommand(...))` with the real `salesOrderLineId` pulled from the placed `SalesOrder` aggregate. Step 5 calls `finance.paymentService.recordCustomerPayment(new RecordCustomerPaymentCommand(...))` against the auto-created customer invoice id. The previous explicit `OutboxRow.pending(...)` injections are gone.

One incidental fix: the previous test paid `330.00 AUD` against an order whose total is `3 × 100 = 300.00 AUD` (event injection bypassed the amount > outstanding check). Now `recordCustomerPayment` enforces the bound, so the test pays the correct `300.00`.

### Smoke

`mvn -pl test-harness test` — 7/7 green (`OrderToCashHappyPathTest`, `OrderToCashFirstLegTest`, `CancelCompensationTest`, `MakeToOrderShortagePathTest`, `SubAssemblyRecursionTest`, `PurchaseToPayHappyPathTest`, `SetPriorityCascadeTest`).

### What didn't change

- The manufacturing-leg branch of O2C is intentionally still covered by `MakeToOrderShortagePathTest` + `SubAssemblyRecursionTest`. `OrderToCashHappyPathTest` exercises the *full-reservation shortcut* path (`applyStockReserved` → `ready_to_ship`, manufacturing skipped) — that's its design from the original Slice D delivery and remains the right scope for this test.
- Goods-receipt adapter not built; not needed by any O2C path. Still listed in `InventoryTestKit`'s class Javadoc as the one remaining unwired path, for whichever future slice surfaces a goods-receipt assertion in the harness.

---

## 2026-05-15 — Consolidate `finance.product_standard_cost` + `finance.product_valuation_class` into `finance.product_accounting`

Twin of the sales slice shipped earlier today. The finance schema held two narrow tables — `product_standard_cost` (cost + currency, projected from `StandardCostChanged`) and `product_valuation_class` (valuation class, projected from `ValuationClassChanged`) — each named after a single column rather than after the schema's view of the aggregate. Both columns are 1:1 with Product and share Product's lifecycle, so the smell-fix is to fold them into one table and seed it on `ProductCreated`, mirroring `sales.product_pricing`, `inventory.stock_item`, and `manufacturing.product_replenishment`.

### Schema changes (`db/northwood_erp.sql` + new Liquibase changeset)

- New table `finance.product_accounting (product_id PK, standard_cost, currency_code, valuation_class, discontinued_at, updated_at)` — all attribute columns nullable, populated by the four respective handlers. Triggered `set_updated_at`.
- Baseline `northwood_erp.sql` replaces the previous `finance.product_valuation_class` CREATE with the consolidated table. Seed populates one row per Product: standard cost from `product.standard_cost`, valuation_class derived from `product_type` (raw/semi → `raw_materials`, finished → `finished_goods`).
- Stale comment on `reporting.product_standard_cost` corrected to reference the new column path.
- New changeset `finance/.../2026-05-15-consolidate-product-accounting.sql` for existing dev DBs: creates the new table, migrates rows from both predecessors (if present) via `DO $$ ... $$` guarded blocks, drops the two old tables. **Side note:** `finance.product_standard_cost` turned out to never have been baselined — its Liquibase changeset existed in the 2026-05-08 entry but the table CREATE never made it into `northwood_erp.sql`. The current refactor incidentally closes that latent gap (real-DB COGS reads would have hit "relation does not exist" today; only the test-harness in-memory fakes hid it).

### New ports + handlers

- `finance.application.inbox.ProductAccountingProjection` — write port with `seed(UUID)`, `applyStandardCost`, `applyValuationClass`, `applyDiscontinued`. Consumed only by the four `*Handler` classes in the same package.
- `finance.application.ProductAccountingLookup` — read port with `findStandardCost`, `findValuationClass`. Consumed by `JournalEntryService` (GL-account picker) and `ShipmentPostedCogsHandler` (COGS cost source). Honours the `*Projection`-vs-`*Lookup` convention (non-handler readers go through a separate class).
- `JdbcProductAccountingProjection` + `JdbcProductAccountingLookup` in `infrastructure/persistence/`. Writes do plain `UPDATE` with WARN-and-insert fallback (matching the sales pattern).
- New `ProductCreatedHandler` (consumer name `finance.product-created`) — seeds the stub row.
- New `ProductDiscontinuedHandler` (consumer name `finance.product-discontinued`) — stamps `discontinued_at`.

### Existing handlers / services retargeted

- `StandardCostChangedHandler` and `ValuationClassChangedHandler` now inject `ProductAccountingProjection` and call `applyStandardCost` / `applyValuationClass` respectively.
- `JournalEntryService` injects `ProductAccountingLookup`; the field rename from `valuationClasses` → `productAccounting` follows the instance-field-naming rule (full aggregate name). Both `inventoryAccountForProduct` and `cogsAccountForProduct` updated; their silent-fallback Javadoc points at the new column path.
- `ShipmentPostedCogsHandler` injects `ProductAccountingLookup`. The cold-start fallback log line updated to reference `product_accounting.standard_cost` instead of the dropped `product_standard_cost`.

### Deletions

- `finance.application.inbox.ProductStandardCostProjection` + `ProductValuationClassProjection` (write-and-read mixed-purpose ports — replaced by the projection/lookup split).
- `finance.infrastructure.persistence.JdbcProductStandardCostProjection` + `JdbcProductValuationClassProjection`.
- `test-harness/.../inmemory/finance/InMemoryProductStandardCostProjection` + `InMemoryProductValuationClassProjection` replaced by one `InMemoryProductAccounting` (implements both ports, fine in a test fake where there's no JDBC boundary to split on).

### Tests updated

- `JournalEntryServicePostingsTest` and `JournalEntryServiceReverseBySourceTest` swap their `ProductValuationClassProjection` mock for `ProductAccountingLookup`.
- `FinanceTestKit` swaps the two fakes for `InMemoryProductAccounting`, registers two new handlers (`ProductCreatedHandler`, `ProductDiscontinuedHandler`) on the synchronous bus.

### Docs

- `event-flow.html` adds `finance.product-created` to `ProductCreated` consumers and `finance.product-discontinued` to `ProductDiscontinued` consumers. Existing `StandardCostChanged` / `ValuationClassChanged` consumer descriptions retargeted to the new column paths.
- `application-kafka.yml` finance comment updated to enumerate the four product-event consumers.
- `docs/conventions.md` gains a new *Consumer-side projection tables: one table per (schema, aggregate, shared-lifecycle attribute group)* section under the port-naming chapter. Locks in the smell rule (single-column-named tables are a smell), the standard seed-on-Created / UPDATE-on-attribute-change / stamp-on-Discontinued wiring, and the *Projection-vs-Lookup split for the read side.

### Smoke

`mvn -pl finance-service test` → 112/112 green. `mvn test` full reactor green including the Testcontainers end-to-end (`OrderToCashHappyPathTest`, `PurchaseToPayHappyPathTest`, `MakeToOrderShortagePathTest`, `SubAssemblyRecursionTest`, etc.) — those exercise the real persistence layer against Postgres, so the schema migration + new handlers are covered.

### Out of scope (intentional)

- **Reporting projections deliberately untouched.** Reporting projections snapshot history and have different lifecycle rules than per-service operational projections; `reporting.product_standard_cost` stays as-is.
- **Manufacturing projections stay split.** `manufacturing.product_replenishment` (replenishment policy) and `manufacturing.product_active_bom` (active BOM FK) don't share a lifecycle — active BOM changes over a Product's life as engineering revises BOMs, while replenishment policy is mostly static. The rule's grouping key is shared lifecycle, not 1:1 cardinality.

---

## 2026-05-15 — Sales seed-on-Created for `sales.product_pricing` (lifecycle closure parity with inventory + manufacturing)

Conversation-surfaced design refactor (no prior dev-todo entry). The lifecycle-closure audit of `ProductCreated`/`ProductDiscontinued` consumers revealed an asymmetry: inventory and manufacturing both seed a stub row on `ProductCreated` and mark `discontinued_at` on `ProductDiscontinued`, but sales' `product_pricing` row only appeared on first `SalesPriceChanged`. The `ProductDiscontinued` handler in sales worked around the absence with an upsert that fabricated a `sales_price = 0, currency_code = 'AUD'` stub — a smell that conflated "discontinued" with "never sellable."

### Refactor

Unified the three consumer schemas behind one rule: **every consumer schema that holds product facts seeds a stub on `ProductCreated` (`ON CONFLICT DO NOTHING`) and marks `discontinued_at` on `ProductDiscontinued`.** Each attribute-change event becomes a plain `UPDATE` (no upsert), with a WARN-and-fallback insert for the anomaly path (seed missed or out-of-order delivery).

### Schema changes (`db/northwood_erp.sql` + new Liquibase changeset)

- `sales.product_pricing.sales_price` and `currency_code` are now nullable; the `'AUD'` default on `currency_code` is dropped. NULL means "not yet priced." `discontinued_at` already nullable (added 2026-05-14).
- Baseline seed (`northwood_erp.sql`): raw materials and semi-finished goods get `NULL, NULL` instead of `0.00, 'AUD'`. The two finished goods (`FG-TABLE-001`, `FG-CABINET-001`) keep their real prices.
- New changeset `sales/.../2026-05-15-product-pricing-nullable-price.sql` for existing dev DBs: three `ALTER COLUMN` calls (drop NOT NULL × 2, drop default × 1).

### New handler

- `sales-service/.../application/inbox/ProductCreatedProjection.java` + `ProductCreatedHandler.java` + `infrastructure/persistence/JdbcProductCreatedProjection.java`. Consumer name `sales.product-created`. Inserts `(product_id, NULL, NULL)` with `ON CONFLICT DO NOTHING`. Mirrors `inventory.product-created` exactly.
- `sales-service/.../application-kafka.yml` comment updated to note the new event (no `subscribe-topics` change — `product.events` was already subscribed for `SalesPriceChanged`).

### Existing handlers simplified

- `JdbcSalesPriceProjection` and `JdbcProductDiscontinuedProjection` now do a plain `UPDATE` (row guaranteed to exist post-seed). Zero-rows-affected logs WARN and falls through to an `INSERT ... ON CONFLICT DO UPDATE` so the projection is resilient to out-of-order delivery without making upsert the normal path. Application-side projection Javadoc updated to document this.

### Read-side predicate change

- `ProductPricingLookup.CatalogPrice` Javadoc now documents that `salesPrice` / `currencyCode` are nullable until `SalesPriceChanged` fires. JDBC impl unchanged (`rs.getBigDecimal` / `rs.getString` already return null correctly for NULL columns).
- `SalesOrderService.resolveUnitPrice` updated: `catalog.isPresent()` no longer means "sellable" — sellability is `salesPrice IS NOT NULL`. The "caller supplied unitPrice + stub row with NULL currency" path skips the currency assertion (same race-tolerance the previous empty-catalog path provided).

### Tests added

- `SalesOrderServicePlaceOrderTest.placeOrder_rejects_unpriced_stub_when_no_unitPrice_supplied` — covers the "row exists but `sales_price IS NULL`" case → `UnknownPriceException`.
- `SalesOrderServicePlaceOrderTest.placeOrder_accepts_unpriced_stub_when_caller_supplies_unitPrice` — covers the override path with no currency assertion.

### Smoke

`mvn -pl sales-service test` → 131/131 green. `mvn test` full reactor green including the Testcontainers end-to-end (`OrderToCashHappyPathTest`, `PurchaseToPayHappyPathTest`, `MakeToOrderShortagePathTest`, etc.).

### Follow-up

- **Finance has the same shape today** — `finance.product_standard_cost` and `finance.product_valuation_class` only appear on first attribute-change event. Sequenced as the next slice (see dev-todo.md) — consolidate both into `finance.product_accounting` with the same seed-on-Created discipline.
- **Reporting deliberately untouched.** Reporting projections snapshot history and don't need to track a live row per Product; the asymmetry is intentional there.

### Rule locked in

When a projection's lifetime should match an aggregate's, seed on the aggregate's creation event with all attribute columns NULL, and treat the read-side as `<attr> IS NOT NULL AND discontinued_at IS NULL` for the sellability/usability predicate. Documented inline at `sales.product_pricing` table header in `northwood_erp.sql` and on `ProductCreatedProjection` Javadoc.

---

## 2026-05-14 — event-flow.html: data-driven tree (adding events stops being error-prone)

Tail-end of the §2.13 / §2.14 / §2.11 polish run. The hand-authored source-first + destination-first tables in `docs/event-flow.html` had become a steady source of layout-shift bugs: every event addition required bumping `merge-src` / `merge-agg` rowspans by hand, and a single stale rowspan slid every subsequent source's cells one column to the left — a bug that returned twice in this session despite multiple targeted fixes (see commits `30b6e3f` and `c4661b4`), then *again* after a data-driven table refactor that should have eliminated stale rowspans by computing them from data.

Root cause turned out to be deeper than stale rowspans: HTML `rowspan` counts DOM rows including `display:none` ones, while the collapse logic was either reducing ancestor rowspans (over-shrinking — the original bug) or leaving them alone (over-reaching when many inner rows hide — what reproduced after the data-driven table refactor). Neither approach works in all collapse states. **The table approach is structurally fighting against `rowspan` + `display:none`**; no further patching will fully fix it.

### Pivot: tree, not table

Replaced both views with native `<details>` / `<summary>` trees. Each source / aggregate is a `<details>` element with a single `<summary>` row; the consumers under an event are a small per-event `<table>` (no cross-event rowspan, so the layout-shift bug class is structurally impossible). Native `<details>` collapsibility removes the entire JS collapse subsystem (~400 lines deleted).

### Fix

Both views, the Coverage Gaps lead-in count, and an authoring consistency check derive from one inline `NORTHWOOD_EVENTS` array. The JS renderer at the bottom of `event-flow.html` builds the trees from that data on `DOMContentLoaded`. Adding an event = appending one object to the array.

### Structural changes

- **Inline `NORTHWOOD_EVENTS` array** — 44 events × their consumers, schema documented in a long comment block immediately above the array. Each event entry has `source`, `aggregate`, `event`, and a `consumers: [{dest, name, desc}]` list (`desc` allows HTML so existing `<code>` tags survive).
- **Two `<div class="tree-wrap">` placeholders** replace the ~700 lines of hand-authored table rows; the renderer populates them with nested `<details>` elements.
- **Source-first renderer** (`renderSourceFirstTree`) — groups events by source → aggregate → event → consumers. Each source / aggregate is a `<details>` with a count badge in the summary; each event is a small `<table class="consumers-table">` of leaf rows. No rowspans anywhere.
- **Destination-first renderer** (`renderDestinationFirstTree`) — re-keys to (dest, source, aggregate, event) tuples and renders as destination → source → aggregate → event-list. Destinations sorted alphabetically; source order within a destination follows the natural insertion order from the events array.
- **Coverage Gaps lead-in count** — a `<span id="no-consumer-count-word">` placeholder is filled with the right number-word from a `count(consumers === []) → "Zero"…"Ten"` mapping. No more drift when a gap closes.
- **Validation pass** runs over the data and writes findings to (a) browser console and (b) a red error banner at the top of the source-first table. Catches duplicate events, missing required fields, duplicate consumer names within an event.
- **Inlined the data** into `event-flow.html` rather than keeping a side `event-flow-data.js`. Chrome / Edge / Safari block `<script src="…">` from `file://` for cross-origin reasons; the page is typically opened off disk, so a side file would have rendered the tables empty for most viewers.

### Two old bugs the refactor permanently rules out

1. **Stale rowspan slides cells left.** The bug that surfaced twice this session (most recently in a screenshot from the user — purchasing / inventory / manufacturing / finance all rendering in the Aggregate Root column) was caused by hand-authored `rowspan="N"` values diverging from the actual row count in their group. With rowspans computed from the data, this is structurally impossible.
2. **Source-first and destination-first tables drift out of sync.** Both views render from the same array; adding an event automatically updates both.

### Smoke

I can't run a browser headlessly from here. The renderer's HTML output mirrors the original hand-authored structure (same class names, same rowspan placement, same content) so the existing collapse / expand-all JS works unchanged. Verifying live in a browser is a follow-up the next page-viewer pass naturally does.

### Follow-up suggestions (not done in this slice)

- The "no inbox handler yet" `<li>` in the Notes section still hand-curates which deferred events to list. Could be auto-rendered from `consumers: []` entries, but the current prose adds rationale per group that's hard to express in data. Leave manual for now.
- The Coverage Gaps section itself (numbered list of gap entries) is still hand-authored. Auto-numbering would save the renumber-on-closure step we did multiple times this session. A small follow-up.

---

## 2026-05-14 — §2.11 Nested-type ordering audit (services / repositories / aggregates)

Follow-up to the 2026-05-13 statics-on-top sweep, which explicitly deferred nested-type ordering. Per `docs/conventions.md` → *Class member ordering*, nested types (`static class`, `static enum`, `interface`, public records nested inside an enclosing class) belong at the **top** of the class body, above all fields. Moved 17 nested types across 14 files up to the top of their enclosing class.

### Files swept (each was a nested-type-at-bottom case)

**Repositories:**
- `JdbcProductRepository.DuplicateSkuException` (the dev-todo's named example).

**Application services (exception classes mostly, plus a few command records / view records):**
- `SalesOrderService` — 8 nested exceptions (`CustomerNotFoundException`, `CustomerInactiveException`, `OrderNotFoundException`, `SagaNotFoundException`, `OrderNotCancellableException`, `UnknownPriceException`, `CurrencyMismatchException`, `ProductDiscontinuedException`).
- `CustomerService` — 2 nested exceptions.
- `ProductService` — `ProductNotFoundException`.
- `PurchaseRequisitionService` — `ProductDiscontinuedException`.
- `PurchaseOrderService` — `PoNotApprovableException` (was *mid-class*, between methods, not at the bottom — same violation type).
- `ExchangeRateService` — `RateNotFoundException`.
- `ShipmentService` — `ShipmentLineProductMismatchException`.
- `GoodsReceiptService` — `GoodsReceiptLineProductMismatchException`.
- `WorkOrderReleaseService` — `BomNotFoundException`, `RoutingNotFoundException`.
- `WorkOrderOperationService` — `WorkOrderNotFoundException`.
- `BomEditService` — 2 command records (`CreateBomDraftCommand`, `AddLineCommand`) + 4 nested exceptions (`BomNotFoundException`, `BomLineNotFoundException`, `BomNotEditableException`, `BomCycleException`).
- `JournalEntryService` — `LineCost` view record.

**Aggregate roots:**
- `SalesOrder` — `ShippedLineInput` view record.
- `Payment` — `SupplierAllocationLine`, `CustomerAllocationLine` view records.
- `Product` — `Status` enum (was below all instance fields, including a `Status status` field that referenced it — a particularly clear violation of the convention's exemplar shape from `Customer.Status`).

### Scoping note: records as the enclosing type intentionally skipped

The convention's wording — *"nested types above all fields"* — doesn't apply cleanly when the enclosing type is itself a `record`, because a record's fields live in the header (not the body). All event records (`StockReserved`, `PurchaseOrderCreated`, …), command DTO records (`PlaceOrderCommand.OrderLine`, etc.), and view DTO records (`BomTreeView.BomNode`, etc.) currently keep their nested records *after* their `EVENT_TYPE` constants / methods. That layout is uniform across ~30+ event classes and reads cleanly because the visible record body starts with the wire-format identifier, not a structural sub-type. Pull forward only if a downstream reading-experience complaint surfaces; today it's a deliberate non-violation.

### Smoke

`mvn install -DskipTests` reactor green; `mvn test` full suite green.

---

## 2026-05-14 — §2.14 `StockReservationService.reserveOneLine` — bounded retry with backoff on lost race

Replaces the previous single-shot-then-fail behaviour of `StockReservationService#reserveOneLine` with a bounded retry loop. Before this slice, if the atomic `StockBalanceWriter.tryReserveOnHand` UPDATE returned 0 rows (lost a race to a concurrent reservation that consumed the same available stock), the line was classified `FAILED` immediately and a TODO comment noted *"a real implementation would loop with backoff"*. The unlucky caller was told the SKU was unavailable even though, on a re-read after the winner committed, residual stock could still have satisfied the request — fully or partially.

### Changes

- **`reserveOneLine` is now a retry loop** with `RESERVE_MAX_ATTEMPTS = 3` and `RESERVE_BACKOFF_MS = {10, 40, 160}` (package-private static so future load testing can tune in one spot; values sized for "modest effort to recover from a brief race" given the single-tenant demo workload, not for production contention — those would likely move to `@Value` config).
- Each retry **re-reads** `StockBalanceLookup.findAvailableQuantity` and recomputes `toReserve = min(requested, available)` — the winner of the previous race may have shrunk or zeroed what's left, and we accept whatever residual the retry can still secure.
- **Early termination** if `available` drops to `0` between retries (no point sleeping for stock that's already gone). Loop also exits early on `Thread.interrupt()` (interrupted thread re-asserted via `Thread.currentThread().interrupt()` before the loop break).
- **Sales + manufacturing paths both benefit** — `reserveForSalesOrder` and `reserveForWorkOrder` are both thin wrappers over `reserveOneLine`, so the retry loop is shared.

### Tests

- Existing `try_reserve_race_loss_falls_back_to_failed` renamed to `try_reserve_race_loss_exhausts_retries_then_falls_back_to_failed`. Still asserts the same end state (`status='failed'`, `reserved=0`, `shortage=10`), and now also verifies `tryReserveOnHand` was called `RESERVE_MAX_ATTEMPTS` times. ~210ms test latency from the real `Thread.sleep` backoff is acceptable.
- New `try_reserve_recovers_on_retry_after_one_race_loss` — first attempt returns false, second succeeds at the original quantity; status lands `reserved`.
- New `try_reserve_retry_clamps_to_shrunk_availability_partial_reserved` — first attempt loses; re-read shows availability dropped 10 → 7; retry succeeds at the clamped 7; status lands `partially_reserved` with `shortage=3`.
- New `try_reserve_retry_aborts_early_when_availability_drops_to_zero` — first attempt loses; re-read shows availability dropped to 0; loop short-circuits with status `failed` after exactly 1 `tryReserveOnHand` call (verifies the no-point-retrying-if-empty branch).

inventory-service suite 78/78 green; full reactor + harness flows green.

### Trade-off

`Thread.sleep` blocks the request thread for up to 210ms in the worst case (all 3 retries exhaust). For interactive sales-order placement this is fine — even the worst case is well under a typical HTTP timeout. For batch reservation paths (e.g. shortage-driven workflows fanning out) a non-blocking retry would be the next step; not warranted today since the racing case is rare and the timing parameters are an order of magnitude under any UX threshold.

---

## 2026-05-14 — §2.13 Rename `BomActivated` → `ActiveBomChanged`

The old name implied activation only, but `Product.activateBom(UUID)` accepts a `null` `newBomHeaderId` to drive deactivation (and the projection plus all consumers handle the null path correctly today). A reader seeing the event name in isolation reasonably assumed it fired only for activation — that misalignment is what this slice fixes.

### Renamed

- **Event class:** `product-events/.../BomActivated.java` → `ActiveBomChanged.java`. Updated record name, `EVENT_TYPE` value (`"product.BomActivated"` → `"product.ActiveBomChanged"`), and `eventType()` override.
- **Inbox handler:** `manufacturing-service/.../BomActivatedHandler.java` → `ActiveBomChangedHandler.java`. Class name + the `super(...)` call's `Class<?>` + `EVENT_TYPE` args updated.
- **`CONSUMER_NAME` unchanged** — `manufacturing.product-active-bom-projector` was already named after the read-model column it maintains, not the source event, so the inbox dedupe key survives the rename without any data migration.

### Wire-format break

Unlike the recent `SalesOrderCancellationApplied` rename (Java-only, kept the wire format to avoid coordinated replay), this rename is **wire-breaking by design**: there are no external subscribers, the topic / event_type rename is contained within Northwood's own services and JVM-local test-harness, and the goal is wire-format accuracy too. The dev-todo entry explicitly called this out before pulling the slice forward.

### Touched

Java: `Product.java` (import + emit), `ProductTest.java` + `ProductServiceTest.java` (import + class refs), `ProductActiveBomProjection.java` Javadoc, `MaterialsCostRollupService.java` Javadoc, `JdbcBomLookup.java` comments (2 references), `ProductDiscontinuedHandler.java` Javadoc, `ManufacturingTestKit.java` (import + bus.register).

Docs / SQL: `docs/event-flow.html` source-first + destination-first cells, `docs/design-notes.md` Slice D trigger surface, `db/northwood_erp.sql` comment on `manufacturing.product_active_bom`.

Historical entries in `docs/dev-done.md` referencing `BomActivated` are left as-is — append-only changelog, accurate to the time they were written.

### Tests

`mvn install -DskipTests` reactor green. `mvn test` full suite green (all 19 modules + test-harness flows pass).

---

## 2026-05-14 — §1F.5 product: ProductMaterialsCostComputed consumer (closes the cost loop)

Closes the cost-rollup feedback loop. Until this slice, manufacturing rolled up materials cost into `manufacturing.product_materials_cost` and emitted `ProductMaterialsCostComputed`, but **no service consumed it**: product master's `standard_cost` kept its old value, and so did finance/reporting projections of it. A supplier price drop that triggered a rebased materials cost left COGS posting at the stale standard cost until someone manually re-entered it.

The fix is small: a single product-service inbox handler that re-uses the existing `ProductService.changeStandardCost` mutator. That mutator emits `product.StandardCostChanged`, which the finance + reporting consumers were already wired to project — so closing the loop on the product side automatically propagates the cost change everywhere it's needed.

### Changes

- **`product-service/pom.xml`**: new `manufacturing-events` dependency. First time product-service depends on another service's events jar (product was a pure Open Host until now — publisher only, consumer-of-none).
- **`product.materials-cost-rebased`** inbox handler under `product/application/inbox/` — first inbox handler in product-service. Reads the event, null-guards `materialsCost` / `currencyCode` (the producer emits null when the rollup determines a product has no computable cost — `reason='inputs_missing'` etc.), calls `ProductService.changeStandardCost(productId, materialsCost, currencyCode)`. Idempotent: the mutator's existing `equalsByValue` guard makes the rollup → standard_cost path no-op on unchanged values.

### Cost-cascade behaviour

A `StandardCostChanged` for a child product (raw material whose supplier price moved, or a sub-assembly whose materials cost rebased) triggers manufacturing's BOM walk to recompute every parent that lists the child in its BOM. Each recompute emits a fresh `ProductMaterialsCostComputed` for the parent, which this handler then applies — propagating the cost change up the BOM in one event-driven cascade. Termination: the no-op-on-unchanged-value guard inside `ProductService.changeStandardCost` (idempotent on the same `(cost, currency)` tuple) and manufacturing's own visited-set + walk-depth cap in `MaterialsCostRollupService`.

### Docs

- **`docs/event-flow.html`**: source-first table — `ProductMaterialsCostComputed` row gets its first consumer. Destination-first table — new `product` destination block added (alphabetically between manufacturing and purchasing); this is the first time `product` appears as a destination service. Notes — `ProductMaterialsCostComputed` removed from the "no inbox handler yet" list. Coverage Gaps — with `ProductMaterialsCostComputed` consumed, the Staleness section has no remaining entries; the section heading is removed entirely. Remaining gaps all sit in the Deferred bucket. Lead-in count `Five` → `Four`; staleness category dropped from the lead-in classification sentence.
- **`docs/dev-todo.md`**: §1F.5 added to the shipped-pointer list. With §1F.5 shipped, the actionable §1F bucket is empty — note added that all five sub-slices are closed.

### Tests

`ProductMaterialsCostComputedHandlerTest` (4 cases: happy + null-materials-cost-skips + null-currency-skips + already-processed). product-service suite 85/85 green.

### Smoke

`mvn -pl product-service -am test` green.

### §1F status

With §1F.5 shipped, **all five actionable items in §1F are closed**:
- §1F.1 — `ProductDiscontinued` across 5 services (sales / manufacturing / purchasing / reporting / inventory)
- §1F.2 — `ProductCreated` inventory consumer
- §1F.3 — `CustomerDeactivated` reporting + finance consumers
- §1F.4 — `PurchaseOrderApproved` reporting consumer
- §1F.5 — `ProductMaterialsCostComputed` product consumer (this slice)

Remaining `event-flow.html` "Coverage gaps" entries are all in the Deferred / Inferred-only buckets — design-intent gaps documented for completeness, not bugs.

---

## 2026-05-14 — §1F.4 reporting: PurchaseOrderApproved consumer (manual-approval staleness)

Closes the staleness gap where a draft PO manually approved via `POST /api/purchase-orders/{id}/approve` left `reporting.purchase_order_tracking_view.po_status` stuck at `'draft'` indefinitely. The shortage-driven auto-approve path masked it: the auto-approve fires in the same transaction as PO creation, so the existing `po-created` handler already stored the post-approval status.

### Changes

- **Liquibase changeset** `reporting-service/.../changes/2026-05-14-po-tracking-add-approved-at.sql` adds `approved_at TIMESTAMPTZ NULL` to `reporting.purchase_order_tracking_view`. Master changelog includes it.
- **`PurchaseOrderTrackingProjection.recordPoApproved(purchaseOrderHeaderId, approvedAt, actorUserId)`** new interface method + `JdbcPurchaseOrderTrackingProjection` implementation. UPDATE-only against `purchase_order_header_id` — if the tracking row isn't there yet (po-created handler racing), logs a WARN and no-ops; inbox redelivery catches up. The `po_status` flip is guarded by `CASE WHEN po_status = 'draft' THEN 'sent' ELSE po_status END` so a PO that's already moved past `'sent'` (e.g. into receipt) isn't regressed.
- **`reporting.po-tracking.po-approved`** inbox handler — passes the event's `occurredAt` as `approvedAt` and the envelope's `actorUserId` (the approver propagated via the outbox header) to the projection.
- **`docs/event-flow.html`**: source-first table — `PurchaseOrderApproved` row gets its first consumer; destination-first table — `PurchaseOrderApproved` added under reporting / purchasing / PurchaseOrder. Notes — `PurchaseOrderApproved` removed from the "no inbox handler yet" list. Coverage Gaps — Staleness entry §1 deleted; remaining entries renumber 2→1, 3→2, 4→3. Lead-in count `Six` → `Five`.

### Tests

`PurchaseOrderApprovedHandlerTest` (happy + already-processed). reporting-service suite 7/7 green.

### Smoke

`mvn -pl reporting-service test` green.

---

## 2026-05-14 — §1F.3 finance: CustomerDeactivated → flag outstanding AR for collections

Closes §1F.3 (second half — see preceding entry for the reporting half). On `sales.CustomerDeactivated`, finance flips `flagged_for_collections = true` on every outstanding customer invoice for the customer; a future collections UI / workflow picks them up. "Outstanding" = `status IN ('posted', 'partially_paid') AND outstanding_amount > 0` — a draft is not yet a real receivable, a paid/cancelled invoice has nothing to collect.

### Changes

- **Liquibase changeset** `finance-service/.../changes/2026-05-14-customer-invoice-flag-for-collections.sql` adds `flagged_for_collections BOOLEAN NOT NULL DEFAULT false` to `finance.customer_invoice_header`. Master changelog includes it.
- **`CustomerInvoiceCollectionsProjection`** (interface) + `JdbcCustomerInvoiceCollectionsProjection`. Single method `flagOutstandingForCollections(customerId)` returns the row count (informational; zero is not an error — the customer may simply have no live invoices).
- **`finance.ar.customer-deactivated`** inbox handler — calls the projection, logs the flagged-row count.
- **`docs/event-flow.html`**: source-first table — `CustomerDeactivated` row now has both consumers (reporting + finance); destination-first table — `sales / Customer / CustomerDeactivated` added under the finance destination. Notes section — `CustomerDeactivated` removed from the "no inbox handler yet" customer-master list. Coverage Gaps — the §1F.3 critical entry deleted; remaining entries renumbered (1 deleted leaves the Critical section empty, so the section heading is removed entirely; Staleness entries renumber 2→1, 3→2, 4→3, 5→4). Lead-in count `Seven` → `Six`.
- **`docs/dev-todo.md`**: §1F.3 collapsed to a pointer to `dev-done.md`.

### Tests

`CustomerDeactivatedHandlerTest` in finance-service (3 cases: happy + zero-outstanding-is-not-an-error + already-processed). finance-service suite 112/112 green.

### Smoke

`mvn -pl finance-service test` green.

---

## 2026-05-14 — §1F.3 reporting: CustomerDeactivated dashboard projection

First half of §1F.3. Adds a per-customer status projection in reporting so dashboard widgets can compute deactivated-customer counts without reading sales' customer table cross-schema. The finance half (flag outstanding AR for collections) ships as a sibling slice.

### Changes

- **Liquibase changeset** `reporting-service/.../changes/2026-05-14-customer-dashboard-status.sql` creates `reporting.customer_dashboard_status (customer_id PK, status CHECK IN ('active','inactive'), deactivated_at TIMESTAMPTZ NULL, updated_at TIMESTAMPTZ DEFAULT now())`. Master changelog includes it.
- **`CustomerDashboardProjection`** (interface) + `JdbcCustomerDashboardProjection`. Single method `recordCustomerDeactivated(customerId, deactivatedAt)` upserts to status='inactive'.
- **`reporting.dashboard.customer-deactivated`** inbox handler under `inbox/dashboard/` with `@Component("dashboard_CustomerDeactivatedHandler")` (matches the existing simple-name-collision convention in that sub-package).
- **`docs/event-flow.html`**: source-first table — `CustomerDeactivated` row gets its first consumer (was "no current consumer"); destination-first table — adds `sales / Customer / CustomerDeactivated` under the reporting destination.

### Design choice

The dev-todo entry described this as "decrement active-customer count" — i.e. an accumulator. Used a per-customer status row instead because:
- `CustomerDeactivated` carries only the customer id; accumulator semantics would need a parallel `CustomerRegistered` consumer (§1F.4-ish) to keep counts balanced. The per-customer row is independently useful before that lands.
- Replay-safe: a redelivered event re-upserts the same row, never double-counts.
- Future UI features (list inactive customers, filter on status) work without extra joins; "active count" stays a derived `WHERE status='active'` once §1F.4 seeds those rows.

### Tests

`CustomerDeactivatedHandlerTest` (happy + already-processed). reporting-service suite 5/5 green.

### Smoke

`mvn -pl reporting-service test` green.

### Follow-up

Finance half (`finance.ar.customer-deactivated` — flag outstanding AR for collections) ships next; Coverage Gaps entry stays in `event-flow.html` until both halves land.

---

## 2026-05-14 — §1F.2 inventory: ProductCreated consumer (seed stock_item stub)

Inventory's `StockItemProjection.applyReorderPolicy` previously documented this gap inline: any product registered after boot via `POST /api/products` would emit `ReorderPolicyChanged` against a missing `inventory.stock_item` row, hit the WARN-and-no-op branch, and silently lose its policy. Demo masked it because the Liquibase seed pre-creates rows for the 5 demo SKUs.

### Changes

- **`inventory.product-created`** inbox handler (consumer name `inventory.product-created`) calls a new `ProductCreatedProjection.apply(productId, sku, name, productType)`.
- **`ProductCreatedProjection`** interface in `application/inbox/` + **`JdbcProductCreatedProjection`** in `infrastructure/persistence/`. `INSERT ... ON CONFLICT (product_id) DO NOTHING` — race-tolerant with the Liquibase seed and with redeliveries.
- Default values for the stub row: `base_uom_code='EA'` (matches every existing seed row), `stock_tracking_mode='tracked'`, `reorder_point=0`, `reorder_quantity=0`. UOM default exists because `ProductCreated` doesn't carry the producer's `baseUomId` today — captured as a note in the projection's Javadoc; a future event evolution (or an inventory command) can update it.
- **`StockItemProjection.applyReorderPolicy` Javadoc** rewritten to reflect that the gap is closed on the happy path. Remaining race window (out-of-order partition delivery dropping `ReorderPolicyChanged` before `ProductCreated`) still ends with WARN-and-no-op; inbox redelivery once the seed lands catches the policy up.
- **`docs/event-flow.html`**: source-first table — `ProductCreated` row expanded from 2 to 3 consumers (inventory added at the top, since it's the seed-row producer). Destination-first table — `ProductCreated` added under `inventory → product/Product`. Coverage Gaps section — entry for `ProductCreated has no inventory consumer` deleted, remaining entries renumbered (2→1, 3→2, 4→3, 5→4, 6→5). Lead-in count `Eight events have no consumer at all` → `Seven`.

### Tests

`ProductCreatedHandlerTest` (happy + already-processed). inventory-service suite 75/75 green.

### Smoke

`mvn -pl inventory-service test` green.

---

## 2026-05-14 — §1F.1 finalize: event-flow.html + dev-todo close-out

Closes §1F.1 after all five per-service consumer slices shipped (sales / manufacturing / purchasing / reporting / inventory — see the five preceding entries). Doc cleanup only; no code or schema changes.

### Changes

- **`docs/event-flow.html` source-first table** — `ProductDiscontinued` row expanded from a single "no current consumer" placeholder to a 5-row block listing each of the new consumers with their per-service write target / read-side use.
- **`docs/event-flow.html` destination-first table** — `ProductDiscontinued` added under the `product → Product` slot for each of the 5 destinations; rowspans on the destination, aggregate, and event cells bumped accordingly.
- **`docs/event-flow.html` Notes section** — `ProductDiscontinued` removed from the "published by their aggregates but have no inbox handler yet" list.
- **`docs/event-flow.html` Coverage gaps section** — the §1 critical gap entry for `ProductDiscontinued` deleted; remaining gap entries renumbered (3 → 2, 4 → 3, etc.). Lead-in count "Nine events have no consumer at all" → "Eight".
- **`docs/dev-todo.md`** — §1F.1 entry collapsed; the 5 sub-bullets, recommendation note, and section body removed. A one-line pointer to `dev-done.md` replaces the block. §1F.2 onward unchanged.

### Smoke

`mvn install -DskipTests` reactor green. `mvn test` 19/19 modules green (test-harness end-to-end flows all pass).

---

## 2026-05-14 — §1F.1 inventory: ProductDiscontinued consumer (stamp on stock_item)

Last of five §1F.1 service-level slices. Inventory stamps `inventory.stock_item.discontinued_at`; future reorder-alert logic (none today) treats `IS NOT NULL` as suppressed. Schema-only addition — no reader exists yet, so the slice is the minimum viable plumbing to land the signal.

### Changes

- **Liquibase changeset** `inventory-service/.../changes/2026-05-14-stock-item-add-discontinued-at.sql` adds `discontinued_at TIMESTAMPTZ NULL` to `inventory.stock_item`. Master changelog includes it.
- **`ProductDiscontinuedProjection`** interface in `application/inbox/` + **`JdbcProductDiscontinuedProjection`** in `infrastructure/persistence/`. Update-only — if no `stock_item` row exists for the product (inventory has no `ProductCreated` consumer today, see §1F.2), logs a WARN and no-ops. Mirrors `StockItemProjection.applyReorderPolicy`'s race-tolerant behaviour.
- **`ProductDiscontinuedHandler`** (consumer name `inventory.product-discontinued`).
- **Architectural decision**: separate `ProductDiscontinuedProjection` rather than adding a method to `StockItemProjection`. `StockItemProjection` writes through the `StockItemRepository` aggregate (because reorder policy requires aggregate-level validation), and the discontinue is a one-column UPDATE that doesn't justify adding a `discontinue()` mutation method to the `StockItem` aggregate — keeping the discontinue concern in its own interface + JDBC adapter matches the sales / purchasing / reporting siblings in §1F.1, and the `application/` JdbcTemplate ban routes JDBC into `infrastructure/persistence/` regardless.

### Tests

`ProductDiscontinuedHandlerTest` (happy + already-processed). inventory-service suite 73/73 green.

### Smoke

`mvn -pl inventory-service test` green.

---

## 2026-05-14 — §1F.1 reporting (atp): ProductDiscontinued consumer

Fourth of five §1F.1 service-level slices. Reporting's ATP view stamps `discontinued_at` so UI consumers can grey-out / filter discontinued rows; existing accumulator columns and `stock_status` CHECK constraint are untouched.

### Changes

- **Liquibase changeset** `reporting-service/.../changes/2026-05-14-atp-add-discontinued-at.sql` adds `discontinued_at TIMESTAMPTZ NULL` to `reporting.available_to_promise_view`. Master changelog includes it.
- **`AvailableToPromiseProjection.recordProductDiscontinued(productId, discontinuedAt)`** new interface method + `JdbcAvailableToPromiseProjection` implementation. Upsert against `product_id`; if no row exists yet (discontinue races ahead of any identity-bearing event), inserts a `'(pending)'` stub carrying just the stamp, consistent with the existing `recordSalesReservation` / `recordProductionReservation` stub patterns.
- **`reporting.atp.product-discontinued`** inbox handler under `inbox/atp/` (`@Component("atp_ProductDiscontinuedHandler")` to avoid the simple-name collision with siblings in other ATP subpackages, mirroring the existing convention there).

### Tests

`ProductDiscontinuedHandlerTest` (happy + already-processed). reporting-service suite 3/3.

### Smoke

`mvn -pl reporting-service test` green.

---

## 2026-05-14 — §1F.1 purchasing: ProductDiscontinued consumer + PR gating

Third of five §1F.1 service-level slices. Purchasing maintains a new `purchasing.product_discontinued` projection table and rejects new requisitions whose lines reference a discontinued product. The gate fires inside `PurchaseRequisitionService.buildLines` (shared by both `createManual` and `createForWorkOrderShortage`), so even the auto-shortage path that runs from the inbox is protected against the race where manufacturing's shortage detector fires after a discontinue but before its own state catches up.

### Changes

- **Liquibase changeset** `purchasing-service/.../changes/2026-05-14-add-product-discontinued-projection.sql` creates `purchasing.product_discontinued (product_id UUID PRIMARY KEY, discontinued_at TIMESTAMPTZ NOT NULL)`. New per-service changeset; master changelog now includes it.
- **`ProductDiscontinuedProjection`** (write port) + `JdbcProductDiscontinuedProjection`. Upsert on `(product_id)`.
- **`DiscontinuedProductLookup`** (read port, single-method narrow → `*Lookup`) + `JdbcDiscontinuedProductLookup`. `isDiscontinued(productId)` returns a boolean.
- **`purchasing.product-discontinued`** inbox handler — calls `ProductDiscontinuedProjection.applyDiscontinued(productId, occurredAt)`.
- **`PurchaseRequisitionService`** gains a `DiscontinuedProductLookup` collaborator; the existing `buildLines` private method now iterates the requested lines once up front and throws `ProductDiscontinuedException` (new static inner class) on any discontinued line, before any aggregate construction. Both the manual REST entry point and the work-order-shortage auto-path go through `buildLines`, so the gate covers both.
- **`PurchaseRequisitionController`** wires `ProductDiscontinuedException → 409` via a focused `@ExceptionHandler` (HTTP-status convention matches sales: entity-in-state-that-blocks-the-operation, not bad-input).
- **`InMemoryDiscontinuedProductLookup`** added to test-harness — implements both the read port (`DiscontinuedProductLookup`) and the write port (`ProductDiscontinuedProjection`) so seeding via either entry point lands on the same underlying set. `PurchasingTestKit` now wires it into the service constructor and registers `ProductDiscontinuedHandler` on the bus.

### Tests

`ProductDiscontinuedHandlerTest` (happy + already-processed). Full purchasing-service + test-harness suite green (109 + 7 test-harness flows).

### Smoke

`mvn -pl purchasing-service,test-harness -am test` → all green.

---

## 2026-05-14 — §1F.1 manufacturing: ProductDiscontinued consumer (replenishment + active BOM)

Second of five §1F.1 service-level slices. Manufacturing retires the product across two read-side rows:
- `manufacturing.product_replenishment` — both `is_purchased` and `is_manufactured` flipped to `false`. The existing `ManufacturingRequestedHandler` rejection branch (`!isManufactured()` returns `"rejected_not_manufactured"`) covers any future sales-line for the SKU without further changes.
- `manufacturing.product_active_bom.active_bom_header_id = null` — equivalent in effect to a `BomActivated` with a null `newBomHeaderId` (the active-BOM projection's existing `apply()` already accepts null for the BOM-retirement path).

### Changes

- **`ProductReplenishmentProjection.applyDiscontinued(UUID productId)`** added on both the interface and `JdbcProductReplenishmentProjection`. Implementation delegates to `applyMakeVsBuy(productId, false, false)` — the new method exists for semantic clarity at the handler call site (a discontinue is a different signal than a make-vs-buy change, even though the resulting row is the same).
- **`ProductDiscontinuedHandler`** (new, consumer name `manufacturing.product-discontinued`) — calls `replenishment.applyDiscontinued(productId)` then `activeBom.apply(productId, null)`.
- **`InMemoryProductReplenishmentProjection`** (test-harness) gains the new method for test composition parity.
- **Tests**: `ProductDiscontinuedHandlerTest` (happy path verifies both projection writes; already-processed short-circuit verifies neither write fires). 110 manufacturing-service tests, 0 failures.

### No schema change

Both target tables already exist with the right shape; the discontinue is a pure projection write. No Liquibase changeset for this slice.

### Smoke

`mvn -pl manufacturing-service test` → 110/110.

---

## 2026-05-14 — §1F.1 sales: ProductDiscontinued consumer + placeOrder gating

First of five §1F.1 service-level slices closing the `ProductDiscontinued` consumer gap. Sales now stamps `sales.product_pricing.discontinued_at` on the inbox event, and `SalesOrderService.placeOrder` rejects any line whose product carries a non-null `discontinued_at` — the rejection fires regardless of whether the caller passed a `unitPrice` (a manual override doesn't override the producer's retirement of the SKU).

### Changes

- **Liquibase changeset** `sales-service/.../changes/2026-05-14-add-product-pricing-discontinued-at.sql` adds `discontinued_at TIMESTAMPTZ NULL` to `sales.product_pricing`; the previously-empty master changelog now includes it. First per-service changeset in this codebase since the v3 → northwood_erp.sql rebase.
- **`sales.ProductDiscontinuedHandler`** (new, consumer name `sales.product-discontinued`) — calls `ProductDiscontinuedProjection.applyDiscontinued(productId, occurredAt)`.
- **`ProductDiscontinuedProjection`** interface + `JdbcProductDiscontinuedProjection` implementation. Upsert against `sales.product_pricing`: if no row exists yet (discontinue races ahead of any `SalesPriceChanged` for the SKU), inserts a stub row carrying just the stamp (`sales_price=0, currency_code='AUD'`).
- **`ProductPricingLookup.CatalogPrice`** record gains a third field `Instant discontinuedAt`; `JdbcProductPricingLookup` reads the new column.
- **`SalesOrderService.resolveUnitPrice`** — new guard checks `catalog.discontinuedAt() != null` before currency/price resolution; throws `ProductDiscontinuedException` (mapped to HTTP 409 on `SalesOrderController`, joining `CustomerInactiveException` and `OrderNotCancellableException` in the same handler — closest-analog status: the entity is in a state that blocks the operation, not a request-payload problem).
- **`InMemoryProductPricingLookup`** (test-harness) updated to the new record signature, with a `markDiscontinued(productId, instant)` helper for future end-to-end coverage.
- **Tests**: `ProductDiscontinuedHandlerTest` (happy path + idempotent short-circuit on already-processed). `SalesOrderServicePlaceOrderTest` (3 cases: discontinued with caller-supplied unit price, discontinued with catalog-resolved unit price, live product). Both pass; full sales-service suite 129/129.

### Drive-by fix

`JdbcSalesOrderFulfilmentSagaManagerTest$ApplyCancellationApplied.no_saga_returns_null` had been stale since the `recordCompensationAck` refactor switched orphan acks from WARN+null to throwing — test renamed `no_saga_throws_illegal_state` and asserts the new behaviour with the now-mandatory `EVENT_TYPE` in the exception message.

### Smoke

`mvn -pl sales-service test` → 129 tests, 0 failures. Full `mvn install -DskipTests` reactor green.

### Follow-ups (still in §1F.1)

Four sibling slices remain: manufacturing, purchasing, reporting (atp), inventory. Each adds its own service-side handler against the same producer event; no further changes to product-service or to the event class itself.

---

## 2026-05-14 — Sales fulfilment saga: route fully-reserved orders past the manufacturing leg

Fix for the latent over-production bug surfaced this session: `applyStockReserved` previously transitioned every reservation outcome (RESERVED / PARTIALLY_RESERVED / FAILED) to `STOCK_RESERVED`, after which the worker would call `requestManufacturing` and emit a `ManufacturingRequested` for the full ordered quantity. For a finished_good that was fully reserved from stock, `ManufacturingRequestedHandler` would not reject it (`is_manufactured=true`, active BOM present) and would cut a work order for the full ordered quantity → over-production.

Not exercised by the demo dataset (MTO showcase keeps finished_goods at zero on-hand), but incorrect against any scenario that plants enough stock to fully cover an order.

### Changes

- **`JdbcSalesOrderFulfilmentSagaManager.applyStockReserved`** — branches on `reservationStatus`:
  - `reserved` → transition `stock_reservation_requested → ready_to_ship` (skip the manufacturing leg entirely; wait for `ShipmentPosted`).
  - `partially_reserved` / `failed` → stash shortage (when non-empty), transition `→ stock_reserved`, park for immediate worker pickup (existing behaviour).
- **Interface Javadoc** on `SalesOrderFulfilmentSagaManager.applyStockReserved` rewritten to document both branches.
- **Class-level Javadoc** on `SalesOrderFulfilmentSaga` updated; state-machine prose now mentions the shortcut.
- **`JdbcSalesOrderFulfilmentSagaManagerTest`** — `full_reservation_advances_to_stock_reserved_no_shortage_stashed` renamed to `full_reservation_shortcuts_to_ready_to_ship`; asserts saga lands at `READY_TO_SHIP`. Partial / failed cases unchanged.
- **`SalesOrderFulfilmentSagaWorker.requestManufacturing`** — the `hasShortage == false` guard (added earlier this session) is now a true "shouldn't happen" defensive check rather than a workaround. Comment updated to reflect the routing fix; WARN message points at the manager's RESERVED → READY_TO_SHIP routing as the expected path.
- **`docs/SalesOrderFulfilmentSaga.md`** — new Side rail 2 documents the shortcut; existing rails renumbered (3 = no manufacturable lines, 4 = compensation).
- **Worker change earlier in this session** (separate slice but same flow): `partial` local renamed to `hasShortage`; the loop's "treat as nothing reserved" else branch replaced with the WARN-and-skip guard described above.

### Smoke

`mvn -pl sales-service test-compile -q -DskipTests` green. No E2E test exercises the full-reservation path today (demo dataset never produces RESERVED on a finished_good), so the regression coverage is the manager unit test's flipped assertion. Will be exercised live once a scenario plants finished-goods stock sufficient to cover a sales order.

---

## 2026-05-13 — Schema baseline rebase: v3.sql → northwood_erp.sql, all changesets folded + deleted

The codebase had accumulated 56 Liquibase changeset files across 7 services since `db/northwood_erp_v3.sql` was last rebaked. The convention from CLAUDE.md was "v3.sql is the de facto baseline; master changelogs start empty", but in practice each service's master had 1–9 `include:` entries replaying historical changesets on top of the baseline. This slice flattens everything: v3.sql gets renamed to the canonical `northwood_erp.sql`, every changeset is folded into it (where actual drift existed) or deleted (where v3 already represented the final state), and all 7 changelog masters return to empty.

### Audit (delegated to an Explore subagent)

Of the 56 changesets:
- **38 NO-OP** — v3.sql already represented the state (singular table names, PK/FK renames, `product_pricing` / `po_line_facts` / `product_active_bom` / `wip_balance` / `product_approved_vendor` / `product_valuation_class` / `supplier_product_price` projection tables, exchange-rate-captured-at, current-step-on-saga, etc.). The Liquibase replays were idempotent no-ops against a fresh v3-provisioned DB.
- **10 DATA-ONLY** — seed-data backfills (cabinet sub-assembly fixtures, routing operations, bookshelf BOM, product-standard-cost seed, etc.). v3.sql's seed blocks already contained the rows.
- **8 DRIFT** — real schema deltas missing from v3.sql, all from the 2026-05-08 Slice B2 actor-audit work.

### Drift folded into v3.sql

Following the existing column conventions:
- **15 aggregate tables** got `created_by VARCHAR(64), last_modified_by VARCHAR(64)` (nullable, no default — saga-driven mutations stay null on purpose): `product.product`, `sales.customer`, `sales.sales_order_header`, `inventory.stock_reservation_header`, `inventory.goods_receipt_header`, `inventory.shipment_header`, `manufacturing.bom_header`, `manufacturing.work_order`, `purchasing.supplier_product_price`, `purchasing.purchase_requisition_header`, `purchasing.purchase_order_header`, `finance.customer_invoice_header`, `finance.supplier_invoice_header`, `finance.payment`, `finance.journal_entry_header`. (Notably `inventory.stock_item` was excluded per the changeset's own comment — it's a projection, not an authoritative aggregate.)
- **6 outbox tables** got `actor_user_id VARCHAR(64)` for envelope-level actor propagation: one per service's `outbox_message`.
- **2 reporting views** got `last_modified_by VARCHAR(64)`: `reporting.sales_order_360_view` + `reporting.purchase_order_tracking_view`. The latter additionally got `source_work_order_id UUID` (needed by production-planning-board's open-PO-per-WO computation).

Total: 22 tables touched, ~36 new columns.

### Rename + cleanup

- `db/northwood_erp_v3.sql` → `db/northwood_erp.sql`. File header rewritten — dropped the "v3 CHANGE SUMMARY (over v2)" narrative since v2 lineage is no longer relevant; kept the architectural design rationale (split-readiness, no cross-schema FKs, `shared` as vendored library, seed-data + UUID constants).
- **`docker-compose.yml`** volume mount + filename updated.
- **47 references** to `v3.sql` / `northwood_erp_v3.sql` swept across non-changeset code: `CLAUDE.md`, `README.md`, `docs/persistence.md`, `docs/demo-script.md`, 3 IT tests (`JdbcStockBalanceWriterIT`, `JdbcPurchaseOrderPaymentProjectionIT`, `JdbcWorkOrderRepositoryMaterialStatusIT` — function renamed `loadV3Baseline → loadBaseline`), 5 production Java files (error messages, Javadoc cross-refs), 4 SPA files (seed-data comments), the `LiquibaseConfig` Javadoc, `SagaStateInvariantChecker` comment, `SagaInstance` reference. Historical references in `docs/dev-done.md` + `docs/bugs-caught-by-tests.md` left intact (they describe accurate past state).
- **56 changeset files** deleted from every `*/src/main/resources/db/changelog/changes/` + the `target/classes/...` mirrors. Empty `changes/` directories removed.
- **7 master changelog YAMLs** rewritten to a comment-only template ending in `databaseChangeLog: []`. Each notes that Liquibase still creates the `databasechangelog` bookkeeping tables on first boot, just applies no changesets.

### Manufacturing IT cleanup

`JdbcWorkOrderRepositoryMaterialStatusIT` previously applied three `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` statements after loading v3.sql, as a workaround for the audit-columns drift. With the columns now in the baseline, the workaround block was dropped (the comment block was the canonical "TODO: backport into v3" pointer — now resolved).

### dev-todo cleanup

Closed the §2.5 follow-up that explicitly tracked this drift. Marked ✅ resolved 2026-05-13 with cross-reference to this entry.

### Smoke

- `mvn clean install -q -DskipTests` green across every module. The build's `compile` + `test-compile` phases would have failed if anything still referenced a deleted Liquibase file via classpath resource lookups; nothing did.
- Full data-path smoke (`docker compose down -v ; docker compose up -d` + service boot) deferred to user-side interactive run.

### Follow-ups

- Future schema changes follow the documented path: drop a Liquibase formatted-SQL file under `<service>/src/main/resources/db/changelog/changes/`, append an `include:` entry to that service's master. The baseline file stays canonical for new installs.

### Post-ship hotfix (2026-05-13, same day)

User's first fresh-volume smoke surfaced a `/api/products` 500 → root cause: the audit subagent misclassified `2026-05-03-add-reorder-to-product.sql` as a NO-OP. It saw the earlier `2026-05-02-drop-reorder-from-products.sql` and assumed the two cancelled out — but the drop operated on the pre-rename `products` table (plural, doesn't exist in v3) while the add operated on the post-rename `product` table (singular, did need the columns). Net effect: `JdbcProductRepository`'s SELECT listed `reorder_point` / `reorder_quantity` but v3.sql didn't have them.

Fix: added `reorder_point NUMERIC(18, 4) NOT NULL DEFAULT 0` + `reorder_quantity NUMERIC(18, 4) NOT NULL DEFAULT 0` to `product.product` in `db/northwood_erp.sql`. Six other JDBC repositories spot-checked against their CREATE TABLE blocks (supplier_product_price effectivity/tier columns, every projection table, GL account references, work-order routing fields) — no further misses found, but the audit's accuracy is now 1-for-2 so a second smoke-and-fix pass may surface more. Treat any 500 referencing a column name as another fold miss; connection errors / `relation does not exist` are Postgres-still-booting and unrelated.

**Lesson:** When delegating schema audits to a subagent against a renamed-table history, explicitly call out the renames in the brief so misnamed-table changesets aren't conflated with their post-rename counterparts. Better still: when there are < ~60 changesets, batch-read each one yourself rather than delegate — the audit work is short and the misclassification cost is high (a fresh-volume install that 500s on a known-good endpoint).

### Second post-ship hotfix (2026-05-13, same fresh-volume smoke session)

**Symptom:** reporting-service log spam — `BadSqlGrammarException: relation "outbox_message" does not exist` on every `GET /api/audit` call from erp-web-ui-bff's `AuditAggregatorController` (which fans out to every service on most SPA pages that show an audit timeline).

**Root cause (pre-existing, surfaced by fresh-volume install):** `shared/.../AuditAutoConfiguration` registers `/api/audit` + `JdbcAuditQueryAdapter` for every service that depends on the shared module. The adapter's SQL queries `outbox_message` (unqualified, relying on `search_path`). Reporting is inbox-only — its schema has no `outbox_message` table. So reporting's audit endpoint has 500'd on every fresh-volume install since §1.4 Slice D shipped 2026-05-08; it was just never noticed because the BFF aggregator silently catches per-service failures (`AuditAggregatorController:79`) and returns partial timelines.

**Fix:** opt-out property on the auto-config.
- `AuditAutoConfiguration` gets `@ConditionalOnProperty(prefix = "northwood.audit", name = "enabled", havingValue = "true", matchIfMissing = true)` — default on; services opt out via property.
- `reporting-service/application.yml` adds `northwood.audit.enabled: false` with a comment explaining why (inbox-only, no outbox table).
- Result: reporting no longer exposes `/api/audit`; the BFF aggregator gets a 404, treats it as "no rows for this service", continues with the other 6. No log noise, no 500s.

**Note:** this and the reorder_point fix are different. The user saw the reporting-service exception trace and reasonably inferred the `/api/products` 500 was reporting's fault — but the BFF routes `/api/products` to product-service (not reporting), so the reorder_point miss in `product.product` and the audit-endpoint miss on reporting are independent failures that happened to surface on the same fresh-volume smoke.

---

## 2026-05-13 — demo-web-ui runtime fixes: scenario indicator, event-stream Kafka wiring, event-log persistence

Three SPA / BFF bugs surfaced and fixed in a single session — each small, each was masking real value.

### 1. Topbar "Scenario running" stuck after completion

`AppShell.tsx:28` had `scenarioRunning={runner.status !== "idle"}`. The scenario runner's state machine has `completed` and `failed` as terminal states (`idle | running | paused | verifying | completed | failed`). After a saga completed, status flipped `running → completed`, but `"completed" !== "idle"` so the topbar kept pulsing the "Scenario running" button instead of reverting to the "Scenarios" dropdown. Narrowed the predicate to an explicit allowlist: `runner.status === "running" || "paused" || "verifying"`. Modal continues to show the `completed` / `failed` `StatusPill` until dismissed, so result is still readable; only the topbar indicator changed.

### 2. Event log + EVENT STREAM not receiving events

Two root causes compounding:

**(a) BFF kafka starter missing.** `demo-web-ui-bff/pom.xml` depended on bare `spring-kafka` (the library), not `spring-boot-starter-kafka` (which carries Spring Boot's `KafkaAutoConfiguration`). Under `SPRING_PROFILES_ACTIVE=kafka` the SSE endpoint `/api/events` returned 200 (controller bean loaded) but the `@KafkaListener` on `EventsAggregatorController.onMessage` was never bound to a container factory — events on `*.events` topics flowed past the BFF.

**(b) `@EnableKafka` missing on the BFF application.** The shared module's `KafkaMessagingAutoConfiguration` declares `@EnableKafka` at class level (services pick it up via auto-config). The BFF deliberately doesn't depend on shared (its pom comment: *"pulling shared would drag JDBC / Liquibase / Kafka starters along"*), so it had no source of `@EnableKafka` — annotation-driven `@KafkaListener` processing wasn't active.

Fix:
- `demo-web-ui-bff/pom.xml`: `org.springframework.kafka:spring-kafka` → `org.springframework.boot:spring-boot-starter-kafka`.
- `BffApplication.java`: added `@EnableKafka` (with a comment explaining why, since the shared-module crutch isn't available here).

Diagnostic sequence: user reported both Event log and EVENT STREAM "not working", confirmed they were running BFF with kafka profile (ruling out the `@Profile("kafka")` gate on the controller). Screenshots showed both UIs at their "Waiting for events…" empty state, no connection error — so SSE was reaching the BFF. Adding `@EnableKafka` surfaced the missing-bean error (`A component required a bean named 'kafkaListenerContainerFactory' that could not be found`) which led to the starter dependency.

### 3. Event log page reset to empty on navigation

`EventLog.tsx` owned its own `useState<DemoEvent[]>([])` + `EventSource("/api/events")` subscription. React unmounted the component on route change; the buffer was lost, a fresh SSE opened on remount. (The bottom `EventDrawer` survived only because `AppShell` keeps it mounted across routes.) Plus there were *two* SSE connections to the BFF (drawer + page) instead of one.

Fix: lifted state + SSE into a shared `EventStreamContext` provider at `AppShell` level.

- **New file** `demo-web-ui/src/events/EventStreamContext.tsx` — single `EventSource("/api/events")` subscription, shared `DemoEvent[]` buffer (cap 500, matches EventLog's previous deepest), `paused` / `clear` API. Exports `<EventStreamProvider>` + `useEventStream()` + `DemoEvent` / `ServiceKey` / `EventRow` types.
- **`AppShell.tsx`** — wraps the layout with `<EventStreamProvider>` so both the persistent `EventDrawer` and any `Outlet`-rendered route can consume from it.
- **`EventDrawer.tsx`** — dropped its local state + EventSource + duplicated `EventRow` / `DemoEvent` types + `asServiceKey` helper. Reads everything from `useEventStream()`. Display still slices to 50 (`DRAWER_DISPLAY_CAP`).
- **`EventLog.tsx`** — same: dropped local state + EventSource. Filters + per-row expand stay local to the page (those should reset on revisit, the buffer shouldn't).

Side effects:
- One SSE connection now instead of two.
- `pause` and `clear` are global across drawer + page (consistent with shared-buffer model).
- EventLog buffer survives navigation; revisiting the page shows the same events that accumulated while away.

### Smoke

- `mvn -pl demo-web-ui-bff -am compile -q` clean after both pom + BffApplication changes.
- `npm run build` (demo-web-ui) green after the context refactor — bundle stayed at 403 KB JS / 26 KB CSS (1673 modules, +1 over previous build).
- Live: drove a scenario end-to-end, watched events stream into both the drawer and `/event-log`. Navigated to `/products` and back to `/event-log` — buffer preserved.

### Follow-ups

None. The `EventStreamContext` provider establishes a clean pattern any future stream-consuming component (saga ticker, audit-log replay, etc.) can plug into.

---

## 2026-05-13 — demo-web-ui /boms read-only tree viewer

`demo-web-ui`'s `/boms` route was a `Placeholder` stub since the §1.3 scaffolding, parked under dev-todo §3.4 ("BOM editor UI" — explicitly low-priority since 2026-05-06). Reading-but-not-editing is the half of §3.4 that's genuinely cheap and useful for the demo narrative — every persona (Tom for requisition sizing, Linda for planning, Olivia for cost context) benefits from seeing a manufactured product's recursive BOM without bouncing through curl. `erp-web-ui` already had the read-only viewer (`routes/manufacturing/Boms.tsx`); this slice ports the same shape into the demo SPA so feature parity holds.

### Shipped

- **BFF route**: `new Route("/api/boms", "manufacturing", null)` in `demo-web-ui-bff/.../RouteTable.java` — proxies `/api/boms/by-product/{id}` (and the authoring `POST` / `DELETE` paths, though the demo SPA doesn't call them) through to manufacturing-service on port 8084.
- **Types + fetcher**: `BomTree` + `BomNode` in `demo-web-ui/src/api/types.ts`. `fetchBomTree(productId)` in `fetchers.ts`. Wire shape matches the backend `BomTreeView` record exactly (`bomHeaderId`, `productId`, `productSku`, `productName`, `components` → recursive `BomNode { componentProductId, componentSku, componentName, componentKind, quantityPerFinishedUnit, scrapFactorPercent, subBomHeaderId, children }`).
- **Page** at `demo-web-ui/src/routes/Boms.tsx`. Picker dropdown over the products list filtered to `productType ∈ {finished_good, semi_finished_good} ∧ status == active`. Once a product is picked, a second `useQuery` fires `GET /api/boms/by-product/{id}`. Five render states: no-pick / loading / 404 (no active BOM on file) / error / tree.
- **Tree rendering**: `BomTreeRow` is a recursive component — chevron expand/collapse (default open), `Layers` icon for sub-assemblies / `Wrench` icon for raw materials, `paddingLeft: depth * 16 + 8px` for hierarchy. Per-row: monospace SKU + name; `kind: <kind>` plus sub-bom UUID badge when the row is itself a sub-assembly; quantity-per-unit on the right; scrap-factor chip in warn color when > 0. Header shows total component count (recursive) and the bom_header_id (truncated).
- **Routing + sidebar**: `App.tsx` `/boms` route now mounts `<Boms />` instead of `<Placeholder title="BOMs" persona="emma" />`. `Sidebar.tsx` comment refreshed to note authoring is still REST-only (dev-todo §3.4 narrowed accordingly).
- **Dead-code cleanup**: `Placeholder.tsx` was the last placeholder route in the demo SPA. With `/boms` real, no production route used it anymore — deleted both the component file and the import in `App.tsx` per the no-dead-code convention.

### Smoke

- `npm run build` (demo-web-ui) green; final bundle 403 KB JS / 26 KB CSS.
- `mvn -pl demo-web-ui-bff -am compile -q` clean.

### Out of scope (still §3.4)

The authoring half — create draft, add lines, drag-reorder, run cycle detection on save, flip draft → active — is still deferred. Backend authoring path is fully wired (`BomEditService` + 4 REST endpoints), so the demo can use curl / Postman until the editor UI lands. Pull forward if a planning-tool angle becomes part of the showcase narrative.

---

## 2026-05-13 — `@PreAuthorize` → `@RequireXxx` meta-annotations + service merges + class-member-ordering sweep

Multiple cleanups in one session, driven by a sequence of code-quality questions.

### `@PreAuthorize` → role meta-annotations

Every controller previously gated endpoints with bare-string `@PreAuthorize("hasRole('xxx')")` — 50 callsites across 13 controller files. Three risks: typos that silently deny access until runtime, role-rename fan-out across files, no IDE go-to-definition. `T(...)` SpEL would centralise but reads worse than the literal.

**Shipped:** 10 meta-annotations under `shared/src/main/java/com/northwood/shared/api/security/` — one per role actually used in `@PreAuthorize`. Each is a 4-line `@interface` wrapping `@PreAuthorize("hasRole('<role>')")`. Naming: `@Require<RoleName>` (e.g. `@RequireCatalogManager`, `@RequireSalesClerk`, `@RequireSalesManager`, `@RequireWarehouseClerk`, `@RequireProductionPlanner`, `@RequireProductionSupervisor`, `@RequirePurchasingClerk`, `@RequirePurchasingManager`, `@RequireAccountant`, `@RequireFinanceManager`).

Sweep replaced all 50 callsites — every controller now reads `@RequireCatalogManager` instead of `@PreAuthorize("hasRole('catalog_manager')")`. Spring Security 6's meta-annotation support means runtime behaviour is identical (the inner `@PreAuthorize` is honoured); the IDE now offers find-usages and rename across files, and a typo on a role name becomes a compile error rather than a runtime 403.

Out of scope: the 3 realm roles (`warehouse_manager`, `auditor`, `sysadmin`) don't have annotations because no endpoint gates on them today — adding dead annotations would be design-for-hypothetical-future. Captured as a dev-todo follow-up for when those endpoints land.

### `SalesOrderShippingService` merged into `SalesOrderService`

`SalesOrderShippingService` was a 14-line single-method stub used only by `ShipmentPostedHandler`. Two outlier signals: (a) other inbox handlers in the codebase call the main aggregate service (finance's `CustomerInvoiceService.createFromShippedOrder`, inventory's `StockReservationService`, manufacturing's `WorkOrderCancellationService`); (b) the method declared `@Transactional(propagation = MANDATORY)` — the only place in the codebase using MANDATORY.

**Shipped:** Moved `recordShipped(...)` onto `SalesOrderService` with plain `@Transactional` (matching the rest of the service and finance's precedent). Updated `ShipmentPostedHandler` (field renamed `shipping` → `salesOrders` per the field-naming convention, since the field now holds the SalesOrderService not a use-case-specific stub), `SalesTestKit` (dropped `shipping` field + wiring), deleted both `SalesOrderShippingService.java` + its test. `Propagation.MANDATORY` no longer appears anywhere in the codebase.

### `domain.saga.MakeToOrderShortageRecoveryQueryPort` dead duplicate removed

Two identical interfaces existed: one in `domain/saga/`, one in `application/saga/`. Every importer (`GoodsReceivedHandler`, `JdbcMakeToOrderShortageRecoveryQueryPort`, `ManufacturingTestKit`, `InMemoryMakeToOrderShortageRecoveryQueryPort`, handler test) pointed at the `application.saga` one — the `domain.saga` copy had zero importers, left over from a refactor that moved the file without deleting the original. Deleted. `domain/saga/` now contains just `MakeToOrderSaga.java` (the saga state record, legitimately domain-shaped).

### Class member ordering: statics-on-top sweep + `version` finalization

Documented the convention (`docs/conventions.md` → "Class member ordering"), pointed from `CLAUDE.md`, and swept the codebase to compliance. The rule is strict: **every `static` field declaration comes before any instance field**, including `private static final RowMapper<X>` lambdas in `Jdbc*` classes and SQL `String` constants in `Jdbc*QueryPort` classes. Static *methods* are unconstrained. Nested types (already conventional) at the top above fields.

Sweep breakdown:
- **15 JDBC repo / query-port files** — moved `RowMapper` lambdas from bottom to top. Most have one mapper; master-detail repos (CustomerInvoice, SupplierInvoice, Shipment, GoodsReceipt, PurchaseOrder) have two (HEADER + LINE).
- **6 reporting query-port files** — moved both the `SELECT_*` SQL `String` constants and the `RowMapper` to the top, above the `JdbcTemplate` instance field.
- **30 test classes** — moved `private static final UUID X = UUID.randomUUID();` / `LocalDate` test constants above `@Mock` instance fields. Spans every service module's test sources.

Also flipped `private long version` to `private final long version` on 8 aggregates (Customer, SalesOrder, Product, PurchaseRequisition, PurchaseOrder, SupplierInvoice, WorkOrder, StockItem) — the other 6 already had it `final`. No domain method ever reassigns; the SQL `UPDATE ... SET version = version + 1` happens in the repo, the in-memory aggregate is discarded.

### Smoke

- `mvn compile test-compile -q` clean across all 6 service modules + shared + test-harness after each slice.

### Follow-ups

- **Nested-type placement audit.** The static-field audit excluded nested types as a separate ordering question. The documented convention says nested types go at the **top**, above all fields. Known violation: `JdbcProductRepository.DuplicateSkuException` (nested public static class at the bottom). Likely a handful of others — exception classes nested on services. Sweep on demand.
- **Role meta-annotations for `warehouse_manager`, `auditor`, `sysadmin`.** Scaffold when endpoints gate on them.

---

## 2026-05-12 — §1C: erp-web-ui operational forms to reach user-stories.md parity

The operational ERP SPA had read + detail + role-gated action coverage but no creation forms for the four operator-driven write paths. Stories 3.1 / 5.2 / 6.1 / 7.1 — the demo highlights — could only run partway in `erp-web-ui` before bouncing the operator over to `demo-web-ui` or curl. This slice closes the gap.

### Forms shipped

| § | Route | Persona | Backend | Blocks |
|---|---|---|---|---|
| **1C.1** | `/shipments/new` | Mike (warehouse_clerk) | `POST /api/shipments` | Story 3.1, 7.1 |
| **1C.2** | `/goods-receipts/new` | Mike (warehouse_clerk) | `POST /api/goods-receipts` | Story 5.2, 6.1, 7.1 |
| **1C.3** | `/supplier-invoices/new` | Olivia (accountant) | `POST /api/supplier-invoices` | Story 6.1, 7.1 |
| **1C.4** | `/payments/new` (two-tab AP + AR) | Olivia (accountant) | `POST /api/payments` + `/api/payments/customer` | Story 3.1, 6.1, 7.1 |

### Shared pattern across all four forms

- **Picker first, fields second**: each form opens with a 5s-polled list picker (SO list for shipments, PO list for receipts + invoices, invoice list for payments). Picker is grouped — primary candidates in the first `<optgroup>` (e.g. SOs at `ready_to_ship`, POs at `sent`/`approved`/`partial`/`partial_received`, invoices at `approved`), fallback candidates in an "Other (advanced)" optgroup. Auto-selects the newest primary on first render.
- **Auto-fill from picked parent**: picking the parent (`/api/sales-cmd/sales-orders/{id}` for SOs, `/api/purchase-orders-cmd/{id}` for POs, invoice rows directly) auto-populates lines / amount / supplier identity / currency. Eliminates the typo-a-UUID failure mode that the §1B.9 backend validation was added to catch — these forms hand back the exact `(parentLineId, productId)` pairs the server expects.
- **Per-line override allowed**: each line still has editable qty / unit-cost / unit-price; substitution is possible but server rejects with a clear 400 if `(soLineId, productId)` / `(poLineId, productId)` mismatches the parent.
- **`requiresRole` on submit**: every primary action button gates on the persona's role via `useCurrentUser().hasRole`. Disabled state tooltips "Requires role: X" when the current user lacks it. Sarah / Mike / Olivia / Linda each see disabled-button feedback when looking at the wrong screen.
- **Cache invalidation on success**: payments invalidate `payments` + their invoice list + the parent order list, so the dashboard tiles + relevant list pages refresh without manual reload.

### Side-quest bug-fixes that landed in §1C.1

Pre-existing TypeScript errors in the SPA crashed `npm run build` for everyone. Fixed alongside §1C.1:
- **Toast API drift**: 6 callsites still using the old `toast.show(msg)` / `toast.show(msg, "error")` shape. Switched to the convenience methods `toast.success(msg)` / `toast.error(msg)`. Affected files: CustomerNew, CustomerDetail (×4), PurchaseRequisitionNew, plus my new forms.
- **ConfirmDialog API drift** in CustomerDetail (×4): `confirmDisabled=...` (no such prop) → `busy=...`; the now-required `open` prop missing → added `open` since the dialog is conditionally mounted by the parent.
- **StockReservations tone literal**: `"warning"` not in `Tone` union → `"warn"`.

### Smoke

- `npm run build` (erp-web-ui) green in all four checkpoint runs; bundle grew ~20 KB total (from 387 KB → 414 KB) across the four new pages.
- No backend changes — every form posts to an endpoint that already existed and was already drivable via curl + demo-web-ui.

### What's deferred (§1C.5)

Listed in dev-todo as secondary, not story-critical:
- `/journal-entries` viewer + reverse — placeholder today. Daniel's GL story; demo SPA has it, erp-web-ui needs the same shape. Defer until a stakeholder wants to drive the GL story specifically.
- `/production-board` actions — placeholder. Linda's authoring already covered by `/work-orders/:id` (operation complete + skip + priority).
- `/suppliers` + supplier authoring — placeholder. Suppliers are seeded; demo doesn't onboard new ones.
- `/stock-items` detail — read-only by design; reorder policy is set via `/products/:id`.

### Earlier forms (pre-§1C) that already existed

For the historical record, these creation forms were already in erp-web-ui before this slice:
- `/customers/new` — CustomerNew.tsx (Sarah, register customer).
- `/products/new` — ProductNew.tsx (shipped earlier this session, Emma).
- `/sales-orders/new` — SalesOrderNew.tsx (shipped earlier this session, Sarah).
- `/purchase-requisitions` — PurchaseRequisitionNew.tsx (Tom, manual PR).


---

## 2026-05-12 — §2.5 Phase C: Testcontainers ITs for schema-CHECK-bearing JDBC adapters

Selective real-Postgres tests for the JDBC adapters where today's slices revealed schema-CHECK constraints carrying real load that the in-memory harness couldn't catch. Three IT classes covering:

- **`purchasing-service / JdbcPurchaseOrderPaymentProjectionIT`** (7 tests) — exact guard against the `invoiced_amount` bug class from today's P2P runthrough. Asserts: `addInvoicedAmount` bumps the column and flips status to `partially_invoiced` / `invoiced`; `markFullyPaid` without prior `addInvoicedAmount` raises `DataIntegrityViolationException` for `paid_amount <= invoiced_amount` CHECK violation; multi-invoice partial-then-full coverage sums correctly; `addPartialPayment` exceeding `invoiced_amount` also CHECK-violates.
- **`inventory-service / JdbcStockBalanceWriterIT`** (9 tests) — the writer that the §1B.9 shipment validation defends against. Asserts: `bump` creates row then is additive; `tryReserveOnHand` succeeds with sufficient stock and returns `false` (not throws) with insufficient; `decrementOnHandAndReleaseReserved` clamps reserved at 0 via `LEAST(...)`; decrementing below 0 CHECK-violates; version bumps on each write.
- **`manufacturing-service / JdbcWorkOrderRepositoryMaterialStatusIT`** (4 tests) — regression guard for the §2.2 `material_status` UPDATE SQL change. Asserts: `save()` after `applyReservationOutcome(...)` persists each of `reserved` / `partially_reserved` / `shortage` to the DB column (catches a future regression that drops `material_status = ?` from UPDATE); schema CHECK rejects an unknown material_status value.

### Pattern

Each IT follows the same scaffold:
- Static `PostgreSQLContainer<>(postgres:17)` + static-init `Startables.deepStart(...)` (mirrors `ReorderPolicyChangedSeamIT`'s pattern; bypasses `withInitScript`'s known shaded-IOUtils breakage per CLAUDE.md).
- Schema bootstrap via `Files.readString("../db/northwood_erp_v3.sql")` executed through `DriverManager` once per JVM.
- HikariCP DataSource with `connection-init-sql = SET search_path = <service>, shared` — mirrors production wiring exactly.
- `JdbcTemplate` + `TransactionTemplate(REQUIRES_NEW)` for tests that need transactional boundaries around `@Transactional(propagation=MANDATORY)` adapter methods.
- The adapter under test constructed directly with the test JdbcTemplate.
- `*IT.java` naming + maven-failsafe-plugin in each service's pom so `mvn test` stays fast and `mvn verify` runs the IT layer.

### Build + smoke

- `mvn verify` green project-wide. 621 unit tests + 1 existing seam IT + 20 new IT cases (7+9+4) green. Container start-up adds ~3s per IT class (1 PostgreSQL spin-up per JVM via static init); total wall-clock added to `mvn verify`: ~10s.
- Each IT verified against a deliberately-broken adapter (revert the §2.2 `material_status` column from UPDATE; revert the §2.2 `addInvoicedAmount` call from `SupplierInvoiceApprovedHandler`) — both fail with the precise CHECK / column-mismatch assertion the IT is designed to catch.

### Follow-up noted at the time

- **v3.sql drift with the actor-audit changeset** (`2026-05-08-add-actor-audit-columns.sql` in all 6 producer services). The changeset adds `created_by` / `last_modified_by` to several aggregate tables + `actor_user_id` to every `outbox_message`, but v3.sql baseline doesn't include them yet. The CLAUDE.md rule says "v3.sql gets rebaked into the latest schema on every slice that ships a structural change" — that didn't happen on §1.2 Slice B2. Today's manufacturing IT works around the drift by applying the columns manually in its static init; the right fix is to backport into v3.sql + delete the changesets. Listed as a §2.5 follow-up; not blocking today's work.
- **Two more candidate adapters** with non-trivial schema CHECKs that today's slices didn't exercise but would benefit from Phase C coverage: `JdbcStockReservationRepository` (`reserved_quantity <= requested_quantity` CHECK on `stock_reservation_line`), `JdbcOutboxAdapter` (NOT NULL default vs explicit-null INSERT trap documented in CLAUDE.md). Pull forward if either surfaces a runtime bug.

---

## 2026-05-12 — §2.2: work order material_status projection from RawMaterialsReserved

The WO aggregate's `material_status` column has been static at `reservation_pending` since release — the saga state machine tracked the reservation outcome cleanly, but the WO row never reflected it. Per dev-todo §2.2 this becomes useful as soon as a UI reads `work_order` directly (which `/api/work-orders-cmd/{id}` and the production-board detail now do).

### Slice contents

- **`WorkOrder.applyReservationOutcome(String)`** — new intent-named method. Validates value is one of `MATERIAL_RESERVED` / `MATERIAL_PARTIALLY_RESERVED` / `MATERIAL_SHORTAGE`. No-op when WO is terminal (cancelled / completed / closed). No-op when value matches current. Emits no event — this is a local projection, not a domain transition; the cross-service fact is `inventory.RawMaterialsReserved`.
- **Drop `final` on `materialStatus`** field. Was `private final String`, now mutable.
- **`JdbcWorkOrderRepository.update`** — UPDATE SQL now includes `material_status = ?` so save() persists the new value. Previously the SQL omitted the column.
- **`RawMaterialsReservedHandler`** — adds a third effect alongside the saga apply and the optional shortage emission: load WO, call `applyReservationOutcome` with mapped status, save. Status mapping `reserved → MATERIAL_RESERVED`, `partially_reserved → MATERIAL_PARTIALLY_RESERVED`, `failed → MATERIAL_SHORTAGE`. The WO is loaded once and reused for the shortage emission (was loaded twice in the prior shape).
- **9 new tests** in `WorkOrderTest.ApplyReservationOutcome` nested class: happy path (3 values), no event emitted, guard rejection (unknown value, null, `reservation_pending` not a valid apply target), no-op on cancelled, no-op on completed, no-op on same value. **3 handler tests rewritten** (`full_reservation_projects...`, `partial_reservation_returning_shortage_projects...`, `failed_reservation_projects...`) — the prior shape didn't load the WO on happy path; new shape always does.

### Verification

- `mvn test` green project-wide (621 tests stays; 9 new tests + 1 rewritten in manufacturing-service).
- **Live smoke** against fresh DB through sales + inventory + manufacturing + reporting + demo-bff: small SO (qty=1) → WO `material_status='reserved'`, `version=2` (one create + one projection update). Big SO (qty=20) → WO `material_status='partially_reserved'` (matched the actual stock shortfall the reservation reported).

### Follow-up

The saga-state and WO-material_status are now redundant signals — saga.state mirrors the same outcome on the saga row, WO.material_status mirrors it on the aggregate. That's intentional duplication: each is the right read for a different consumer. Saga state is the right read when asking "where is this flow"; WO material_status is the right read when asking "what's blocking THIS work order". No de-duplication needed.

---

## 2026-05-12 — §2.1 part 2: inventory_value joins ATP × product_standard_cost

Closes the second concrete §2.1 follow-up after part 1's snapshot endpoint. Reporting now projects a local `product_standard_cost` cache from `product.StandardCostChanged` and joins it with `available_to_promise_view.on_hand_quantity` in the snapshot SQL to compute live inventory book value.

### Slice contents

- **`reporting.product_standard_cost` table** — `(product_id PK, standard_cost, currency_code, captured_at)`. Mirror of finance's identically-named projection because per-service `search_path` forbids cross-schema reads; both services consume the same upstream event and seed identically, so no drift in practice.
- **Liquibase changeset** `2026-05-12-add-product-standard-cost-projection.sql` — table + trigger + 9-row seed matching `db/northwood_erp_v3.sql`'s product seed. Mirrored into v3.sql baseline so fresh-volume boots start with full coverage.
- **`ProductStandardCostProjection` port** in `application/inbox/` + `JdbcProductStandardCostProjection` impl + `StandardCostChangedHandler` (consumer name `reporting.product-standard-cost-projector`). Mirrors finance's `StandardCostChangedHandler` byte-for-byte except the projection target schema.
- **`product.events` added** to reporting's kafka subscribe-topics list — reporting was the only service that didn't consume product events (it didn't need to until this slice).
- **`FinancialDashboardSnapshot.inventoryValue`** new field. JDBC `findSnapshot` now adds a fourth query: `SELECT COALESCE(SUM(atp.on_hand_quantity * psc.standard_cost), 0) FROM available_to_promise_view atp JOIN product_standard_cost psc ON atp.product_id = psc.product_id WHERE psc.currency_code = ?`. INNER JOIN deliberately — products missing from the cost cache are dropped, not counted as zero (avoids over-counting against the AUD total when a row exists in some-other-currency).
- **SPA Dashboard** — Row 3 now has 3 tiles: Inventory value, Open WOs, Gross % (today). `ParkedColumnsNote` trimmed: only `wip_value` remains parked (gated on LIFO/FIFO/weighted-avg costing decision).

### Smoke

- `mvn test` green project-wide (621 tests; no test infrastructure changes — purely additive).
- `npm run build` green (1.54s, bundle +0.66 KB).
- **Live smoke** against fresh DB: v3.sql seed populates the 9 products. Inserted 3 ATP rows (5 tables + 12 boards + 40 legs) → snapshot returned `inventoryValue=3560.0000000000` exactly matching the expected `5×320 + 12×80 + 40×25 = 3560`.

### What's left in §2.1

- **`wip_value`** — still parked. Gated on the LIFO / FIFO / weighted-avg costing decision for `wip_balance.average_cost`. Pull forward only when business signals which method.
- **Planning board date columns** — need scheduling-module events that don't exist yet. Out of scope.

---

## 2026-05-12 — §2.1 part 1: AR / AP / currently-open counts via snapshot endpoint

The financial dashboard's `accounts_receivable`, `accounts_payable`, and currently-open SO/PO/WO counts have been stuck at 0 (per the `ParkedColumnsNote` on the SPA). The dev-todo's named unblock path was "either daily balance snapshots or a SUM-window query exposed via a separate endpoint" — picked SUM-window as the cheap option since reporting already projects every column we need.

### Slice contents

- **`FinancialDashboardSnapshot` DTO** (`application/dto/`) — currencyCode, AR, AP, three open counts, asOf. Distinct from `FinancialDashboardView` (per-day deltas).
- **`FinancialDashboardQueryPort.findSnapshot(currency)`** — always returns a record, zeros when no data has been projected.
- **`JdbcFinancialDashboardQueryPort.findSnapshot`** — three SUM/COUNT queries over `sales_order_360_view` / `purchase_order_tracking_view` / `production_planning_board`. AR/AP semantics: `SUM(GREATEST(invoiced_amount - paid_amount, 0))` — only invoiced-but-not-paid contributes (placed-not-yet-invoiced doesn't count). Open SO: `outstanding_amount > 0 AND order_status != 'cancelled'`. Open PO: `po_status NOT IN ('paid','closed','cancelled')`. Open WO: `work_order_status NOT IN ('completed','cancelled')`. Work-order count ignores the currency filter — WOs are physical, not financial.
- **`GET /api/financial-dashboard/snapshot?currency=AUD`** — new endpoint on `FinancialDashboardController`. Spring routes the literal `/snapshot` before the `/{date}` placeholder (literal segments take precedence).
- **SPA Dashboard rework** — three-row KPI grid. Row 1 = snapshot (AR, AP, open SOs, open POs). Row 2 = per-day deltas with "(today)" suffix (revenue, cash received, COGS, cash paid). Row 3 = open WOs + gross %. The previous "Open SOs/POs/WOs" tiles were reading `today.openSalesOrdersCount` (per-day-placed delta) — misleadingly labelled; replaced by snapshot's true currently-open counts.
- **`ParkedColumnsNote`** trimmed — AR/AP/counts are live; only inventory_value + wip_value remain parked (different blockers: cost projection + costing decision).

### Verification

- `mvn test` green project-wide (621 tests; no test infrastructure changes — purely additive).
- `npm run build` (demo SPA) green, 1.55s. Bundle +0.27 KB.
- **Live smoke** against fresh DB: hit endpoint with empty schema → 200 with all zeros (no crash on empty); seeded 3 SOs (one invoiced + partially-paid 1000/300/700, one placed-not-invoiced 500/0/0, one cancelled 200/0/0) → snapshot returned `accountsReceivable=700` (only the invoiced row contributes, cancelled excluded), `openSalesOrdersCount=2` (cancelled excluded).

### What's left in §2.1

- **`inventory_value`** — needs a product-standard-cost projection in reporting (mirror finance's `product_standard_cost`). Doable as a follow-up slice.
- **`wip_value`** — blocked on a costing decision (LIFO / FIFO / weighted-avg). Out of scope until business signals which.
- **Planning board date columns** (`expected_material_available_date`, `planned_start_date`, `planned_end_date`) — need scheduling data no current event carries. Deferred until a planning module lands.

---

## 2026-05-12 — Scenario 7.1 end-to-end runthrough: P2P bug caught + fixed

The user asked for a P2P saga drive-to-`completed` after the §1B closing runthrough left it at `goods_received`. Live runthrough surfaced a real schema-enforced bug; fixed in the same session and re-verified.

### The bug

`purchase_order_header.invoiced_amount` was never bumped anywhere in `purchasing-service`. `SupplierInvoiceApprovedHandler` only advanced the saga. The next event (`SupplierPaymentMade`) tried `UPDATE … SET paid_amount = total_amount`, which violated `CHECK (paid_amount <= invoiced_amount)` because `invoiced_amount` was still 0. Saga transactions repeatedly rolled back, Kafka exhausted retries, message routed to DLT. Saga parked at `supplier_invoice_approved`.

Not caught earlier because the in-memory P2P harness has no schema CHECK constraints — only the real Postgres stack trips this. Full root-cause + symptom-recognition writeup in `bugs-caught-by-tests.md` (2026-05-12 entry).

### Fix

Three files:
- `PurchaseOrderPaymentProjection` (port) — new method `addInvoicedAmount(poId, amount)`. Additive (partial-then-full invoicing sums correctly).
- `JdbcPurchaseOrderPaymentProjection` — UPDATE bumps `invoiced_amount` and flips status to `'partially_invoiced'` / `'invoiced'` based on coverage vs `total_amount`.
- `SupplierInvoiceApprovedHandler` — now also calls `paymentProjection.addInvoicedAmount(payload.purchaseOrderHeaderId(), payload.totalAmount())` in the same transaction as the saga advance.

Test updates: `SupplierInvoiceApprovedHandlerTest` now mocks the projection and asserts both calls. `InMemoryPurchaseOrderPaymentProjection` (test-harness) implements the new method to keep `PurchaseToPayHappyPathTest` compiling. `PurchasingTestKit` wired with the new constructor signature. `mvn test` green (all 621 backend tests still pass; the change is additive on the projection side).

### Verification

Live re-run of scenario 7.1 after fix:
1. Placed SO qty=5 → P2P saga at `waiting_for_goods`
2. Posted goods receipt → MTO un-parked
3. Posted supplier invoice (qty + price match) → saga `→ supplier_invoice_approved`, PO `status='invoiced'`, `invoiced_amount=900` (was 0 before fix)
4. Posted supplier payment of 900 → saga `→ completed`, PO `status='paid'`, `paid_amount=900`

P2P drove all the way to `completed` in a real Postgres + Kafka cluster.

### Doc updates

- `bugs-caught-by-tests.md` — new top entry with the symptom-recognition cue ("→ completed log fires N times before DLT publish" = inside-tx rollback giveaway)
- `CLAUDE.md` / `README.md` / `demo-script.md` — `docker compose up -d postgres kafka` → `docker compose up -d` (no service names) so keycloak comes up by default with the rest of the infra. The previous wording invited "stack up minus keycloak" failures on first-time setup; user flagged this mid-runthrough.

### Follow-up noted at the time

Phase C of §2.5 (Testcontainers + real Postgres tests of `Jdbc*` infrastructure classes) is the named home for catching this class of bug pre-runthrough. Today the in-memory harness covers the orchestration layer cleanly; the schema-CHECK layer has no equivalent test. Pull Phase C forward only if another schema-enforced invariant slips through similarly. The dev-todo's existing "smoke-boot against a fresh DB volume before claiming a slice ships" rule is the operational backstop until Phase C lands.

---

## 2026-05-12 — §1B.9 Phase 6 motion — closes §1B

Closes §1B. After the three UX-hardening items shipped and the end-to-end runthrough confirmed the backend paths work cleanly under load (every saga drove to completion, both new validations rejected as designed, the new goods-receipts list endpoint serves the SupplierInvoices picker), the motion polish slice landed last.

### Slice contents

- **CSS keyframes + reduced-motion fallback** (`index.css`) — three named animations:
  - `northwood-pulse` — scale 1.0 → 1.08 → 1.0 over 600ms; applied to saga state badges when the saga transitions.
  - `northwood-slide-in` — translateX(12px → 0) + opacity over 200ms; applied to every EventDrawer row. React's keying by `eventId` means only freshly-mounted rows (i.e. just-arrived events) play the animation; existing rows shift down without re-animating.
  - `northwood-tint-fade` — 800ms background-colour fade. SagaCard already uses an inline tint-fade via `transition-colors duration-700`; the keyframe form is provided here so any future caller can apply it as an animation.
  - Under `@media (prefers-reduced-motion: reduce)`, pulse + slide-in degrade to a 200ms opacity fade (`northwood-fade-in`). The eye-catching cue survives; the motion-sensitive trigger doesn't.
- **SagaConsole pulse** — the state badge in `SagaCard` is wrapped in a `<span>` with `key={row.state}`. Combined with the existing `flashing` boolean (true for 800ms after `_justUpdated`), the animation runs exactly once per saga state transition. The card-background tint continues firing in parallel.
- **EventDrawer slide-in** — `.northwood-slide-in` on every `<tr>`. The animation plays once per row because React reuses existing rows and only newly-prepended rows mount fresh.

### Smoke

- `npm run build` green in 1.96s. Bundle grew by 1.34 KB CSS gzipped (24.57 → 25.91 KB) — the three keyframes + the reduced-motion override.

### What's deferred

- **Edge-following dot** in the saga stage tracker. The original design called for "the connecting edge animates a moving dot from old → new state". Today's `StageDots` shows a persistent pulse on the current dot, which conveys the same "I'm here now" signal cheaply. The literal moving-dot animation needs an SVG path-follow primitive and adds complexity for a marginal visual-clarity gain.
- **Projector tweaks** — high-contrast pass on muted text, font-size up-tick on table cells. The dev-todo entry explicitly flagged these as "discovered when actually projecting"; until the demo is projected on real hardware, the parenthetical examples are speculative. Pull forward when an actual runthrough on the projector surfaces a concrete legibility issue.

§1B is now closed. Items still on the backlog are in §2 (polish on shipped slices) and §3 (low-priority deferred).

---

## 2026-05-12 — §1B.9 UX hardening #3: supplier-invoice goods-receipt picker

Closes the three-item §1B.9 UX hardening punch list opened on 2026-05-12. The `SupplierInvoices` form's "Goods receipt id" field was the only remaining free-text UUID input — backend treats the field as optional (the 3-way match runs on `purchase_order_line_facts` alone), so the picker is parity polish rather than a bug fix.

### Slice contents

- **`GoodsReceiptView` + `GoodsReceiptLineView` types** (`api/types.ts`) — mirror the inventory `GoodsReceiptView` record shape.
- **`fetchGoodsReceipts()` + `fetchGoodsReceipt(id)`** in `api/fetchers.ts` — list + detail readers. The list endpoint (`GET /api/goods-receipts`) is already proxied by the demo BFF (RouteTable line 51); inventory's `JdbcGoodsReceiptRepository.findAllHeaders()` returns headers sorted `created_at DESC`.
- **Picker in `SupplierInvoices.tsx`** — `useQuery` for `goodsReceipts` (5s refetchInterval matching the PO query); filter by picked `purchaseOrderHeaderId`; render a `<Select>` with "— none —" as the first option (preserves the field's optionality) followed by `{number · status}` per matching receipt. Disabled when no PO is picked or no receipts exist for the picked PO; hint text reports the count. A `useEffect` clears `goodsReceiptHeaderId` when the user changes PO so a stale pick from the previous PO doesn't survive.

### Smoke

- `npm run build` green in 1.89s. No new backend code, so no `mvn test` change.

### Follow-up

§1B.9 hardening list is now closed; the remaining §1B.9 item is **Phase 6 motion + projector tweaks** (saga state-transition pulse, edge-following dot, table-row tint-on-update, reduced-motion fallback, projector tweaks). Defer until a full end-to-end runthrough against the real stack confirms the hardening shipped today is actually load-bearing for the demo.

---

## 2026-05-12 — §1B.9 UX hardening #2: scenario runner `verify` predicate gating

The 3.1 walkthrough on 2026-05-12 (entry below) surfaced a class of demo failure where the user clicked "Run step" on a `humanStep` without actually completing the manual action — most commonly "Complete operations on the WO" before any operation had been clicked through on the production board. The runner trusted the click as confirmation, advanced the step, and the next `waitForSalesSaga(["ready_to_ship"])` timed out 60s later with an unhelpful "Timed out waiting (60s)" error. The user had to reverse-engineer "what did the saga actually need" from the saga state, then go and do the manual action, then re-run the scenario from scratch.

### Slice contents

- **`verify` field on `ScenarioStep`** (`scenarios/types.ts`) — optional `(ctx, signal) => Promise<boolean>` predicate. Only consulted on `kind: "human-pause"` steps. The `humanStep(...)` helper accepts it as an optional 4th argument.
- **`verifying` status on the runner** (`scenarios/runner.ts`) — new top-level status alongside `running` / `paused` / `completed` / `failed`. Adding a per-step `"verifying"` status alongside the existing `pending` / `running` / `completed` etc.
- **Verify polling loop in runner** — when the user clicks "Run step" on a verify-gated paused step:
  - If `step.verify` is set, enter `verifying` status and start a poll loop (2s interval, 60s timeout) that calls the predicate. On `true` → advance to next step. On 60s without true → flip back to `paused` with a `Verification timed out…` error message so the user can try again or Skip.
  - "Skip past verification" button is available throughout — bypasses the predicate and marks the step `skipped`.
  - `abort` aborts the verify loop cleanly via `AbortController`.
- **Six verify predicates in `scenarios/helpers.ts`** — `allWorkOrderOpsCompleted`, `shipmentPostedForSalesOrder`, `goodsReceiptPostedForPo`, `supplierInvoiceRecordedForPo`, `supplierPaymentRecordedForPo`, `customerPaymentRecordedForSo`. Each fetches the appropriate `*View` and checks the relevant status field. All predicates fail-safe — a fetch error or null result returns `false` (the poll keeps trying).
- **Wired into every human step that has an observable side-effect** in `scenarios/scenarios.ts`. Scenarios 3.1 / 5.2 / 7.1 all get verify gating; only the terminal "Scenario 5.2 ends here" step has no verify (nothing to wait on — narrator is done).
- **Modal UI** (`ScenarioRunnerModal.tsx`) — `verifying` status pill rendered in the same colour as `running`; active step icon shows the spinner during verify; footer adds a "verifying manual step…" affordance + "Skip past verification" button next to the existing controls.

### Test + smoke

- `npm run build` (demo-web-ui) — green, 1.82s, no TS errors. The `tsc -b` step in the build is the TypeScript type-check.
- `mvn test` — green, 621 backend tests still pass (no backend changes in this slice; just confirming nothing broke).

### Follow-ups noted at the time

- The 60s timeout is hardcoded in the runner; if some future verify predicate genuinely needs longer (e.g. a slow projection), it could become a per-step override. Today every wired verify is a 1–3 fetch-call check completing in milliseconds.
- The "verifying" colour reuses `--color-state-active` for visual coherence with `running`. If the demo runs side-by-side with another active step it'd be helpful to make verify visually distinct — pull forward if it actually confuses the narrator on screen.

---

## 2026-05-12 — §1B.9 UX hardening #1: shipment + goods-receipt line product validation

Defence-in-depth check on the two inventory write paths that previously trusted the client's `(lineId, productId)` pairing on each line. The SPA pickers shipped 2026-05-12 (entry below) prevent the mistake on the way in, but a buggy / malicious client posting directly to `/api/shipments` or `/api/goods-receipts` could still pass a `productId` unrelated to the parent SO/PO — the failure modes were silent stock corruption (decrement against a wrong product whose stock happened to be sufficient) or an opaque 500 when the `stock_balance.on_hand_quantity >= 0` CHECK eventually fired.

### Architectural choice — local projection over sync REST

The validation needed data the inventory service had no local view of (`sales_order_line.product_id`, `purchase_order_line.product_id`). The two real options were (a) sync REST lookup to sales/purchasing or (b) project the line shape from existing events. Picked (b) because the project's hard rule "the only inter-service contract is the outbox" is load-bearing for the database-per-service migration story — sync REST between services would have been a one-step crack in that wall. Mirrors the existing `finance.purchase_order_line_facts` pattern.

### Slice contents

- **New tables** `inventory.sales_order_line_facts` (`sales_order_line_id` PK, `sales_order_header_id`, `product_id`) and `inventory.purchase_order_line_facts` (`purchase_order_line_id` PK, `purchase_order_header_id`, `product_id`). Added to `db/northwood_erp_v3.sql` and to a new Liquibase changeset `2026-05-12-add-order-line-facts.sql` that's idempotent against the baseline (`CREATE TABLE IF NOT EXISTS`, `CREATE OR REPLACE TRIGGER`). Smoke-boot confirmed both paths green: v3 creates the tables; Liquibase changeset runs and logs "relation already exists, skipping" on the fresh DB.
- **`SalesOrderLineFactsProjection` / `PurchaseOrderLineFactsProjection`** — interface in `application/inbox/` + `Jdbc*` impl in `infrastructure/persistence/`. Upsert via `INSERT ... ON CONFLICT DO UPDATE` keyed on the line id so a redelivered event is a no-op.
- **`SalesOrderPlacedHandler` / `PurchaseOrderCreatedHandler`** — inbox handlers fanning out across each event's `.lines()` to seed projection rows. `inventory-service` now subscribes to `purchasing.events` (added to `application-kafka.yml`) in addition to its prior 3 topics; `purchasing-events` artifact added to the pom.
- **`ShipmentService.post` + `GoodsReceiptService.post`** — for each line with a non-null `salesOrderLineId` / `purchaseOrderLineId`, look up the projection and reject if the line is unknown OR `product_id` mismatches. Lines without a parent line reference (unlinked manual posts) skip the check — preserves an existing API affordance for the make-to-order shipments that don't come from a placed sales order line.
- **400 mapping** — `ShipmentLineProductMismatchException` + `GoodsReceiptLineProductMismatchException` declared as nested static classes on the respective services; `@ExceptionHandler` in each controller returns 400 with the message body.

### Tests

- `ShipmentServiceTest` — 8 tests (4 existing + 4 new): matching-product passes; mismatch rejects (with verifyNoInteractions on save + stockBalances); unknown line rejects; mixed-valid-then-invalid rejects whole command. Existing tests adjusted to pass `null` `salesOrderLineId` (unlinked) so they focus on stock-side-effect assertions.
- `GoodsReceiptServiceTest` — 7 tests (4 existing + 3 new): same pattern.
- `SalesOrderPlacedHandlerTest` + `PurchaseOrderCreatedHandlerTest` — 3 each: each line seeds a projection row; empty lines records-processed without writes; already-processed short-circuits.

Smoke: `mvn test` green (full project, 26.4s — 71 inventory tests, up from 58); `mvn clean install -DskipTests` green (14.7s). Boot smoke: `docker compose down -v ; up -d postgres ; mvn -pl inventory-service spring-boot:run` boots clean in 4.2s with 15/15 Liquibase changesets reported as "ran successfully".

### Follow-ups noted at the time

- The validation rejects "unknown line" the same way it rejects mismatched-product. In production this could theoretically race against a freshly-placed SO whose `SalesOrderPlaced` hasn't been processed yet by inventory's inbox — but the sales fulfilment saga emits `StockReservationRequested` AFTER `SalesOrderPlaced`, and a human posting a shipment is downstream of reservation, so the race window is effectively closed by saga ordering. Capture for the next end-to-end run.
- Could promote the two `Jdbc*Projection` classes to also surface header-level reads (e.g. `findLineIdsForHeader`) if a future caller needs the inverse direction. Today nothing does; not pulled forward.

---

## 2026-05-12 — Demo SPA: scenario 3.1 end-to-end green; UX picker pattern; AP+AR forms parity

Drove the technical-demo SPA's "3.1 Sales fulfilment (happy path)" scenario through to completion against the running stack. Two latent backend bugs surfaced (logged as the prior dev-done entry below). On the SPA side, four operational forms had a shared UX defect — UUID-as-free-text inputs that defaulted independently of the picked parent — that made the demo unusable for anyone who didn't have the seed UUIDs memorised. Fixed all four with a consistent picker pattern and shipped two new backend list endpoints to feed them.

### The picker pattern

Every operational form that previously asked for a `<entity>HeaderId` UUID via free-text input now uses a `<Select>` populated from the relevant list endpoint:

| Form | Parent picker | Auto-fills |
|---|---|---|
| `Shipments` | Sales orders (filtered to `ready_to_ship`) | Lines from SO lines (correct product + qty + line UUID) |
| `Payments → Customer (AR)` | Customer invoices (open first, paid in optgroup) | Amount = invoice total |
| `Payments → Supplier (AP)` | Supplier invoices (approved first, others in optgroup) | Amount = invoice total |
| `GoodsReceipts` | Open POs (status sent / approved / partial) | Lines from PO lines |
| `SupplierInvoices` | Open POs (status sent / approved / partial / received) | Supplier identity, currency, lines from PO |

Picker shape: optgroup-grouped Select with the demo-relevant subset on top + an "Other (advanced)" optgroup as fallback for power users. Most-recently-updated row auto-selects on first render. Where the Select drives downstream fields (lines auto-populate), a `useQuery` keyed on the picked id fetches the parent's full detail and a `useEffect` resets the draft state. `addLine` prefers the next un-drafted line from the parent before falling back to the catalog default.

### Backend additions

Two list endpoints to feed pickers:
- `GET /api/journal-entries` (shipped earlier in the §1B slice; Daniel's GL list)
- `GET /api/purchase-requisitions` (shipped earlier in the §1B slice; Tom's PR list)
- `GET /api/supplier-invoices` (this slice; Olivia's AP picker — needed because only `/pending-review` existed before)

Two BFF route aliases for owning-service GETs that need aggregate detail (`headers + lines`) the reporting projection doesn't carry:
- `/api/sales-cmd/sales-orders/{id}` → sales (already had `/api/sales-cmd` for writes; reused for reads)
- `/api/purchase-orders-cmd/{id}` → purchasing (new alias mirroring `/api/work-orders-cmd`)

The pattern is general: reporting owns `/api/<entity>` (denormalised projections, joined-up status, hide cross-context owners); the owning service has the same path but needs a `-cmd` alias to avoid colliding in the BFF route table.

### Production-board UX rework (1B.9 partial)

Production board's "Complete operation" was a single button + modal where the operator typed the operation sequence (10/20/30/40) by hand. Replaced with a per-op row list — sequence + code + planned minutes + status badge + one-click `complete` button on the next-planned op (others greyed out as "waits on prior" because the aggregate enforces sequential completion). Materials section dropped in below operations. New endpoint not needed; reused `/api/work-orders-cmd/{id}` (manufacturing-side WO detail).

### Lessons captured in user-level CLAUDE.md

Three Spring Boot 4 / Spring Security 7 / Postgres gotchas surfaced and went into the user-level CLAUDE.md so future-me doesn't re-discover them:

1. **Spring Security 6+: `SecurityContextHolder.getContext().setAuthentication(...)` is the wrong pattern.** Mutating the existing context (which is a `DeferredSecurityContext` wrapper) doesn't propagate to AOP `@PreAuthorize` checks. Symptom asymmetry: GETs without `@PreAuthorize` work, POSTs with it 401 (with the Bearer challenge — `BearerTokenAccessDeniedHandler` returns 401 for `AccessDeniedException`, not 403). Fix: `createEmptyContext()` + `setContext()`.
2. **Postgres: explicit `NULL` in INSERT bypasses the column DEFAULT.** Aggregate-side INSERTs in `JdbcXxxRepository.writeOutbox` omit the `headers` column from the column list and rely on the `'{}'::jsonb` default; the shared `JdbcOutboxAdapter.appendPending` listed every column and passed `null` for headers, tripping the `NOT NULL` constraint on saga-emitted events.
3. **(Already in CLAUDE.md from earlier today) Demo BFF security bypass.** The earlier entry covers the architectural decision; the per-bug detail above is the evidence.

### Smoke

- Scenario 3.1 (sales fulfilment happy path) ran cold start to `completed`. Walked through: place SO → wait sales saga → wait M2O saga → complete 4 ops × 1 WO → wait `ready_to_ship` → post shipment → wait `invoice_created` → record customer payment → wait `completed`. Saga states verified at every step against `/api/sagas` per-service. Hit one organic shortage on a re-run that was correctly recovered by posting a goods receipt against the auto-generated PO — the M2O saga un-parked from `raw_material_shortage` → `raw_materials_reserved` and the run continued.
- `mvn -pl shared,finance-service,test-harness -am install -DskipTests` — SUCCESS.
- `cd demo-web-ui && npm run build` — SUCCESS.

### Follow-ups parked in dev-todo §1B.9

- **Backend-side shipment-line / receipt-line product validation.** SPA-side picker now prevents wrong-product mistakes, but a malicious or buggy client could still mismatch line.productId against SO/PO. Add validation in `ShipmentService.post` / `GoodsReceiptService.post` to reject lines whose product doesn't appear on the referenced SO/PO. Defence-in-depth.
- **Human-step verify-predicate in the scenario runner.** `humanStep` currently advances on user click without checking that the manual action actually happened. Add an optional `verify(ctx)` predicate that polls some state until satisfied (or the user clicks "Skip past failure" deliberately). Would have caught the "completed step 4 without completing operations" miss earlier today.
- **`/customer-invoices/{id}` in the BFF route table** — `/api/customer-invoices` is finance-routed, so `/{id}` works via path-prefix matching. Confirmed during scenario walkthrough; no change needed.
- **Supplier invoice "Goods receipt" picker** — currently still a free-text optional UUID. Lower priority — it's optional on the backend (3-way match doesn't strictly need it).
- **Phase 6 motion polish** — saga state-transition pulse, edge-following dot, table-row tint-on-update, reduced-motion fallback, projector-friendly tweaks. Defer until full demo runthrough is solid.

---

## 2026-05-12 — Bug fixes shaken out by demo SPA driving the real stack

Two production bugs surfaced when the demo SPA's scenario runner first drove a real end-to-end flow against running services. Both were latent — they hadn't shown up in the in-memory test harness because they only manifest on the real Postgres (#1) or with the actual wire-shape response (#2).

### #1 `JdbcOutboxAdapter.appendPending` passes null `headers`, violates NOT NULL

Saga workers (sales fulfilment, make-to-order, P2P) emit cross-context events via `OutboxPort.appendPending`. The shared `JdbcOutboxAdapter` SQL listed `headers` in the INSERT column list and passed `row.getHeaders()` — which is `null` when the saga has no extra headers. The schema declares `headers JSONB NOT NULL DEFAULT '{}'::jsonb`; explicit null in the INSERT bypasses the default and trips the NOT NULL constraint.

Symptom: every saga sat at `started` / `wait_for_worker_pickup` with retryCount climbing into the dozens; lastError on each saga row showed `null value in column "headers" of relation "outbox_message_default" violates not-null constraint`. Aggregate-side INSERTs (each `JdbcXxxRepository.writeOutbox`) omit `headers` from the column list and rely on the schema default, so they were unaffected.

Fix: coerce null → `'{}'` in the shared adapter (`shared/.../infrastructure/outbox/jdbc/JdbcOutboxAdapter.java`). One-liner. Captured the lesson in user-level CLAUDE.md → *Explicit `NULL` in an INSERT bypasses the column's `DEFAULT`*.

### #2 `commands.ts` typed responses with non-existent field names

Every command endpoint returns a `*View` whose primary key is `id`. The TS commands were typed `{ salesOrderHeaderId: string }` / `{ supplierInvoiceHeaderId }` / `{ goodsReceiptHeaderId }` / `{ shipmentHeaderId }` / `{ paymentId }` — none of those fields exist in the wire response. At runtime `result.salesOrderHeaderId` was undefined.

Symptom in the scenario runner: step 1 (place order) ✓'d, but step 2 ("Wait for sales saga → manufacturing_requested") failed immediately with "no salesOrderHeaderId in context" because the helper stamped `salesOrderHeaderId: undefined` into ctx. Three other call sites (`GoodsReceipts.tsx`, `Shipments.tsx`, `SupplierInvoices.tsx`) used `result.<bad-name> ?? <fallback>` for success-toast messages, which masked the bug — toast just showed the input number every time.

Fix: retyped all 5 commands to `{ id: string, ... }`; updated the helper + 3 toast-message sites to read `result.id`.

### Shaking-out value

Both bugs underscore the standing principle from CLAUDE.md → "Smoke-boot at least one service against a fresh volume when shipping any new Liquibase changeset" — except generalised: unit tests + in-memory harness can't substitute for a real end-to-end exercise of the full stack. The demo SPA is now the canonical end-to-end smoke surface for the showcase; expect more latent bugs to surface as more flows get exercised through it.

---

## 2026-05-12 — Demo Web UI §1B: 7 missing screens + cleanup

Wired up every remaining placeholder route in the demo SPA (Olivia × 2, Daniel × 2, Tom × 2, cross-cutting × 1) plus the dead Emma sidebar links. After this the demo SPA has a working surface for every persona's headline business action, and the only deferred screens are `/boms` (still parked under §3.4) and Phase 6 polish.

### Backend additions (2 small list endpoints)

- `GET /api/journal-entries?limit=&sourceDocumentType=` — new CQRS read port `JournalEntrySummaryQueryPort` + `JdbcJournalEntrySummaryQueryPort`. Returns lightweight summaries (header + per-row debit-side total + line count) computed in SQL with `LEFT JOIN ... GROUP BY h.journal_entry_header_id`. Drill-in stays `GET /{id}` for full lines. New port wired into `JournalEntryService` constructor; constructor change required matching updates in 2 unit-test classes + the in-memory `FinanceTestKit` (passed an empty-list lambda).
- `GET /api/purchase-requisitions` — extended `PurchaseRequisitionRepository` with `findAll()` (mirroring `CustomerInvoiceRepository`); JDBC impl loads headers DESC by `created_at` then re-fetches each via `findById` to populate lines (PR volume is small in the showcase, so N+1 is fine; documented an upgrade path to a dedicated SummaryQueryPort if volume grows).

### BFF route additions

`RouteTable` gained `/api/customer-invoices`, `/api/purchase-requisitions`, `/api/supplier-product-prices`, `/api/suppliers`, and `/api/exchange-rate` (purchasing + finance routes that hadn't been needed by earlier slices). Bypass-header relay from §1B.0 covers them all.

### SPA additions (7 new pages)

- **1B.1 `/customer-invoices`** — `MasterDetail` list with right-side detail showing subtotal/tax/total stats + per-line breakdown + click-through to `/sales-orders/{id}` for the linked SO.
- **1B.2 `/supplier-invoices/pending-review`** — Olivia review queue. Per-row `Approve` + `Reject` buttons opening a modal with reviewer + reason fields, posting to existing `/manual-approve` / `/reject` endpoints. Toast confirms downstream effect ("GL posted, P2P saga advancing" / "cancelled — terminal").
- **1B.3 `/journal-entries`** — Daniel GL list. Source-document-type filter; row expand reveals full lines with account code/name + debit/credit columns. Empty-credit / empty-debit cells render blank for readability (each line is one-sided).
- **1B.4 `/journal-entries/reverse`** — Two cards: single-journal reverse (picks from a select prefilled with posted journals) + bulk reverse-by-source. Both POST to existing reverse endpoints; both invalidate the `journal-entries` query so the list view refreshes after.
- **1B.5 `/purchase-requisitions`** — Tom PR list with source-type badge (`manual` / `shortage-driven`), source-WO link, expandable lines. Auto-approves shortage flow visibility — the audience now sees the intermediate PR row before it converts to a PO.
- **1B.6 `/supplier-prices`** — Supplier picker → per-supplier price table. `+ set price` button (new prices) and per-row pencil-edit modal (existing prices). PUT to `/api/supplier-product-prices` emits `SupplierProductPriceChanged`; the BoM materials-cost rollup picks it up downstream.
- **1B.7 `/event-log`** — Full-screen variant of the bottom event drawer. Same SSE source (`/api/events`); deeper buffer (cap 500); persistent filters (service / event-type substring / aggregate-id substring); click-row to reveal full envelope JSON. Pause/resume + clear controls.

### Cleanup (1B.8)

- Dropped `/products/pricing` and `/products/reorder` from `PERSONA_NAV.emma` in `Sidebar.tsx`. Functionality stays inline on `/products` (pencil icon → modal with three tabs). Routes themselves redirect to `/products` so old bookmarks still load.
- `/boms` still in nav as a placeholder (deferred per §3.4).

### Smoke

- `mvn -pl finance-service,purchasing-service,test-harness,demo-web-ui-bff -am install -DskipTests` — SUCCESS.
- `mvn -pl finance-service,purchasing-service,test-harness,demo-web-ui-bff test` — 109 finance + 7 saga harness tests + others, all green.
- `cd demo-web-ui && npm run build` — vite build succeeded, 1672 modules transformed, 381 KB JS gzip 103 KB.

### Follow-up

- **§1B.9 Phase 6 polish** stays open in dev-todo: motion (saga-state pulse, edge-following dot, table-row tint-on-update, event-arrival slide-in) + reduced-motion fallback + projector tweaks once demo runs against real stack.
- The PR list endpoint's N+1 line-fetch is fine for the showcase; if PR volume grows past a few hundred, switch to a dedicated `*SummaryQueryPort` + COUNT(line) join (mirroring the journal-entries list shape).
- `commands.ts` lost the `CreatedResponse` type bound on `postJson`/`putJson` — the index-signature constraint blocked typed responses like `ReverseBySourceResponse`. Helper now defaults to `<T = unknown>`. No call sites need updating; existing `<{ ... }>` signatures still infer correctly.

---

## 2026-05-12 — Demo BFF: shared-secret bypass for resource-server security

Slice 1 (security) introduced JWT-required endpoints across all services; the demo SPA has no login UI by design (technical-storytelling, not per-user app), so its BFF couldn't relay a Bearer token and started getting `401 Unauthorized` on every `/api/**` call. Fixed by punching one explicit, configurable hole in the resource-server filter chain.

### Change

- **`shared.infrastructure.security.DemoBypassAuthenticationFilter`** — `OncePerRequestFilter` that, when the request carries header `X-Northwood-Demo-Bypass: <configured-token>`, installs a synthetic `UsernamePasswordAuthenticationToken` granted all 12 business roles (7 personas + 5 manager tiers + auditor; sysadmin omitted — no business permissions). Inert when the configured token is blank.
- **`OAuth2ResourceServerSecurityConfig`** — registers the bypass bean (default token `northwood-local-demo-bypass-2026`) and adds it before `BearerTokenAuthenticationFilter` only when enabled. Bean is always created so the filter chain can interrogate `isEnabled()`; per-environment behaviour driven by the property value, not bean presence.
- **`demo-web-ui-bff.BackendAuthHeader`** — single source of truth for the outbound header. `ProxyController` + `SagaAggregatorController` (the two outbound HTTP clients) call `auth.applyTo(builder)` after building the request. Default token matches the server-side default so a fresh local checkout works with zero env-var setup.
- **`shared/pom.xml`** — added `jakarta.servlet:jakarta.servlet-api` at `provided` scope so the filter can extend `OncePerRequestFilter` directly. Services that consume `shared` already bring `spring-boot-starter-web` for the runtime servlet container.

### Disable in production

Set `NORTHWOOD_SECURITY_DEMOBYPASS_TOKEN=` (empty) on every service AND on the demo BFF. The filter recognises blank as inert and stops short-circuiting auth.

### Why not mirror erp-web-ui-bff (full OIDC login)

erp-web-ui-bff uses option (a) from the locked security plan: per-user OIDC code flow + token relay. That fits a business-user app with role-gated screens. The demo SPA has no per-user identity to project — viewers watch sagas + events drain through the system; there's no "sales clerk" perspective to defend. Adding a Keycloak login round-trip would be friction without any of the access-control payoff. The shared-secret bypass is the documented hole, scoped to the demo BFF, with the bean / filter / property all named to make it obvious in code review and ops.

### Smoke

- `mvn -pl shared,demo-web-ui-bff -am install -DskipTests` — SUCCESS.
- `mvn -pl product-service -am install -DskipTests` — SUCCESS (confirms the new shared bean wires cleanly into a downstream service).
- `mvn -pl shared,product-service test` — 81 tests green; existing test contexts unaffected (the bypass bean is a no-op when its header isn't present, and tests don't send it).

### Follow-up

None. The bypass is single-purpose and self-contained. If a future demo grows per-user identity, swap the BFF over to the erp-web-ui-bff OIDC pattern and drop the bypass property in production.

---

## 2026-05-12 — Customer.Status enum gets `dbValue()`/`fromDb()` (ProductType-style); duplicate String constants removed

Fixed the duplication introduced by yesterday's status-constant sweep on `Customer`: the aggregate had both a `Status { ACTIVE, INACTIVE, BLOCKED }` enum (used for internal state-machine guards) AND three `STATUS_ACTIVE`/`STATUS_INACTIVE`/`STATUS_BLOCKED` String constants (added for wire-form comparison from `CustomerLookup`). The enum and the constants encoded the same value set twice.

### Change

- `Customer.Status` now carries its wire-format string via `dbValue()` + `fromDb(String)`, mirroring `ProductType` exactly.
- Deleted the three redundant `STATUS_*` String constants on `Customer`.
- `CustomerLookup.CustomerSummary.status` changed from `String` to `Customer.Status`.
- `JdbcCustomerLookup` parses the JDBC row via `Customer.Status.fromDb(rs.getString("status"))` instead of returning the raw String.
- `SalesOrderService.placeOrder` compares with `customer.status() != Customer.Status.ACTIVE` (typed enum equality) instead of `Customer.STATUS_ACTIVE.equals(customer.status())`.
- `CustomerInactiveException` constructor takes `Customer.Status` instead of `String`; the error message uses `.dbValue()` for the wire-format text.
- `InMemoryCustomerLookup.put(...)` test fixture takes `Customer.Status`; three test-harness call sites (`OrderToCashHappyPathTest`, `OrderToCashFirstLegTest`, `CancelCompensationTest`) updated to pass `Customer.Status.ACTIVE` instead of `"active"`.

### Smoke

- `mvn clean install -DskipTests` — 28.6s SUCCESS across 19 modules.
- `mvn test` — 29.6s SUCCESS across all modules (test-harness saga lifecycle + Customer aggregate + CustomerService tests all green).

### Follow-up parked

New entry §2.0 in `dev-todo.md`: revisit the enum-vs-String-constants pattern across all aggregate status fields. Today the codebase mixes two patterns — ~16 aggregates use String constants (`SalesOrder.SHIPPED`, etc.), 2 use enum-with-`dbValue` (`ProductType`, `Customer.Status`). Locked rule for cross-service status values (must live on `<service>-events` event class) is independent and stays. Three options listed (migrate all to enum / migrate all to String constants / leave mixed); recommendation is (a) once demo dataset is stable enough to verify Jackson serialisation behaviour end-to-end. Not demo-blocking.

---

## 2026-05-12 — Status-string literals → constants; cross-service constants hosted on events jars

Audit + sweep of every status/state string literal in production code. Two layered moves:

### 1. Added missing aggregate-side status constants

Six aggregates/VOs/rows had status fields with literal usages but no constants:

| Class | Added constants |
|---|---|
| `shared.application.outbox.OutboxRow` | `PENDING`, `PUBLISHED`, `FAILED` |
| `sales.domain.SalesOrderLine` | `OPEN`, `RESERVED`, `PARTIALLY_RESERVED` |
| `sales.domain.Customer` | `STATUS_ACTIVE`, `STATUS_INACTIVE`, `STATUS_BLOCKED` (String mirror of the existing `Status` enum, for the wire-form comparison from `CustomerLookup`) |
| `purchasing.domain.PurchaseOrderLine` | `OPEN` |
| `purchasing.domain.PurchaseRequisitionLine` | `OPEN` |
| `manufacturing.domain.Routing` | `ACTIVE` |

Replaced literal assignments / comparisons in the corresponding aggregates and application services. Example: `SalesOrderService.placeOrder` now uses `Customer.STATUS_ACTIVE` and `SalesOrderLine.OPEN` instead of the raw `"active"` / `"open"` literals. `SalesOrder.recordShipped` uses `SHIPPED`, `SalesOrder.cancel` uses `CANCELLED`. `SupplierInvoice` internal assignments use `APPROVED` / `THREE_WAY_MATCH_FAILED` / `CANCELLED`. `StockReservation` factory uses `RESERVED` / `PARTIALLY_RESERVED` / `FAILED`. `PurchaseOrderService` and `PurchaseRequisitionService` line builders use the new `*Line.OPEN` constants. `JdbcPurchaseOrderReceiptProjection` uses `PurchaseOrder.RECEIVED` / `PARTIALLY_RECEIVED`.

Also collapsed the match-outcome signal: `SupplierInvoiceService` now returns `SupplierInvoice.MATCH_FAILED` / `SupplierInvoice.MATCH_MATCHED` instead of bare `"failed"` / `"matched"` literals; `SupplierInvoice.recordInvoice` compares using `MATCH_MATCHED.equals(matchOutcome)`.

### 2. Locked architectural rule: cross-service constants live on events jars

User-confirmed rule: **if a status/state constant is referenced across service boundaries (consumer reads a value the producer writes onto an event payload), the constant must live on the event class in `<service>-events`, not on the producer's `domain/*` aggregate.** A consumer service can only depend on the producer's events jar (not its `*-service` Maven module), so a constant hosted only on the aggregate forces consumers to redeclare it locally as a private constant — which silently drifts on rename.

Constants added to event classes:

| Event class (in events jar) | New constants |
|---|---|
| `inventory-events / StockReserved` | `STATUS_RESERVED`, `STATUS_PARTIALLY_RESERVED`, `STATUS_FAILED` |
| `inventory-events / RawMaterialsReserved` | `STATUS_RESERVED`, `STATUS_PARTIALLY_RESERVED`, `STATUS_FAILED` |
| `finance-events / CustomerPaymentReceived` | `INVOICE_STATUS_PAID`, `INVOICE_STATUS_PARTIALLY_PAID` |
| `finance-events / SupplierPaymentMade` | `INVOICE_STATUS_PAID`, `INVOICE_STATUS_PARTIALLY_PAID` |

Consumer-side cleanup — deleted local `private static final String *_STATUS_* = "..."` constants in:

- `sales.infrastructure.saga.JdbcSalesOrderFulfilmentSagaManager` (was `RESERVATION_PARTIALLY_RESERVED`, `RESERVATION_FAILED`) → now uses `StockReserved.STATUS_*`.
- `manufacturing.infrastructure.saga.JdbcMakeToOrderSagaManager` (was `RESERVATION_RESERVED`) → now uses `RawMaterialsReserved.STATUS_RESERVED`.
- `sales.application.inbox.CustomerPaymentReceivedHandler` (was `INVOICE_STATUS_PAID`) → now uses `CustomerPaymentReceived.INVOICE_STATUS_PAID`.
- `purchasing.application.inbox.SupplierPaymentMadeHandler` (was `INVOICE_STATUS_PAID`) → now uses `SupplierPaymentMade.INVOICE_STATUS_PAID`.
- `reporting.infrastructure.persistence.JdbcSalesOrder360Projection` (was `INVOICE_STATUS_PAID`, `INVOICE_STATUS_PARTIALLY_PAID`) → now uses `CustomerPaymentReceived.INVOICE_STATUS_*`.
- `reporting.infrastructure.persistence.JdbcPurchaseOrderTrackingProjection` (was `INVOICE_STATUS_PAID` + `fullySettled ? "paid" : "partially_paid"`) → now uses `SupplierPaymentMade.INVOICE_STATUS_*`.
- `reporting.infrastructure.persistence.JdbcProductionPlanningProjection` (was `MATERIAL_STATUS_RESERVED`, plus `case "reserved" / "partially_reserved" / "failed"` literals in a switch) → now uses `RawMaterialsReserved.STATUS_*`.

A producer-side rename of a cross-service status literal now breaks consumer builds at compile time — exactly the safety the rule is buying.

### CLAUDE.md update

New subsection under §"Events jars" titled "Status/state constants follow the same hosting rule as `EVENT_TYPE`". Includes:

- Blast-radius table (consumer-only-internal → aggregate; cross-service → event class).
- Rationale paragraph explaining why event-jar hosting is required when consumers compare.
- Two code-review checks: (1) a `private static final String *_STATUS = "..."` in a consumer service is a smell — move to the event; (2) raw status literals in production code (`"open"`, `"active"`, `"approved"`) are a code-review fail.

### Smoke

- `mvn clean install -DskipTests` — 14.5s SUCCESS across 19 modules.
- `mvn test` — 26.2s SUCCESS across all modules (test-harness saga lifecycle tests still green).

### Note: literals that intentionally stayed

- `SagaInstance.transitionTo(newState, newStep)` — the second argument is a free-form *step descriptor*, not a status. Free-text strings like `"wait_for_compensation_acks"` / `"cancelled"` stay as literals; they're documentation about why the saga parked at this state, not enumerable status values.
- `JdbcProductionPlanningProjection.recordRawMaterialsReserved` maps `"failed"` (inventory wire value) → `"shortage"` and adds `"pending"` for empty input. The output strings `"shortage"` and `"pending"` are reporting-projection-internal mappings with no aggregate counterpart; left as literals since they have a single producer (this projection) and no consumer compares against them.
- `BomEditService.BOM_STATUS_DRAFT` / `BOM_STATUS_ACTIVE` — private constants; no `Bom` aggregate exists yet for these to migrate to. Acceptable as private constants for now.

---

## 2026-05-12 — Collapse 1:1 api/dto ↔ application/dto duplicates (YAGNI flip on the *Response/*Request pattern)

Reversed the §2.15 (yesterday's earlier slice) default. Previously: "every read returns a wire-only `api/dto/*Response` mapped from an `application/dto/*View` at the boundary, even when shapes are 1:1." New default: **use application/dto types directly at the wire boundary; only introduce an api/dto type when the wire shape genuinely diverges from the application shape.**

The earlier "Response and View evolve independently" argument was correct in principle but speculative. Until the wire actually diverges, the duplicate Response and the `.map(*Response::from)` plumbing buy nothing. Same logic applies to write-side `*Request` vs `*Command`: if the JSON shape and the service input shape are identical, the Command IS the wire — no Request indirection needed.

### Scope

**Deleted 22 `api/dto/*Response` files** (1:1 mirrors of existing `*View` records):

| Service | Deleted |
|---|---|
| product | ProductResponse |
| sales | CustomerResponse, SalesOrderResponse, SagaRow |
| inventory | StockItemResponse, GoodsReceiptResponse, ShipmentResponse |
| manufacturing | WorkOrderResponse, SagaRow |
| purchasing | SupplierResponse, SupplierProductPriceResponse, PurchaseOrderResponse, PurchaseRequisitionResponse, SagaRow |
| finance | CustomerInvoiceResponse, JournalEntryResponse, PaymentResponse, SupplierInvoiceResponse |
| reporting | AvailableToPromiseResponse, FinancialDashboardResponse, MaterialShortageResponse, ProductionPlanningResponse, PurchaseOrderTrackingResponse, SalesOrder360Response |

Their controllers now return the corresponding `*View` directly; the `.map(*Response::from)` mapping calls are gone (`.map(ResponseEntity::ok).orElse(...)` remains). For the 3 SagaApiController files the SSE pump iterates `SagaRowView` directly instead of mapping each row.

**Deleted 9 `api/dto/*Request` files** (1:1 mirrors of existing `*Command` records):

| Service | Deleted | Validation moved to |
|---|---|---|
| sales | PlaceOrderRequest | PlaceOrderCommand |
| inventory | PostGoodsReceiptRequest | PostGoodsReceiptCommand + GoodsReceiptLineRequest |
| inventory | PostShipmentRequest | PostShipmentCommand + ShipmentLineRequest |
| purchasing | CreatePurchaseRequisitionRequest | CreateRequisitionCommand + RequisitionLineRequest |
| finance | RecordSupplierInvoiceRequest | RecordSupplierInvoiceCommand |
| finance | RecordSupplierPaymentRequest | RecordSupplierPaymentCommand |
| finance | RecordSupplierPaymentMultiRequest | RecordSupplierPaymentMultiCommand |
| finance | RecordCustomerPaymentRequest | RecordCustomerPaymentCommand |
| finance | RecordCustomerPaymentMultiRequest | RecordCustomerPaymentMultiCommand |

Bean Validation annotations (`@NotBlank`, `@NotNull`, `@Size`, `@Valid`, `@DecimalMin`, `@Pattern`, `@NotEmpty`) moved from each `*Request` onto its `*Command` counterpart. Controllers' `@RequestBody` parameters changed from `*Request` to `*Command`; the in-controller `new *Command(request.field(), ...)` constructor chains are gone. `application/dto/` already imported Spring transitively, so picking up the Jakarta validation annotation set is not a new layer crossed.

### Kept (genuinely divergent wire shapes)

These pairs stay separate because the shapes legitimately diverge:

- `sales / CancelOrderRequest` (`{ reason }`) vs `CancelOrderCommand` (`{ salesOrderHeaderId, reason }`) — `salesOrderHeaderId` comes from the URL path, not the JSON body.
- `manufacturing / CompleteOperationRequest` (`{ actualMinutes }`) vs `CompleteOperationCommand` (`{ workOrderId, operationSequence, actualMinutes }`) — same pattern; the Command picks up two path params.

### Kept (api-only — no application/dto counterpart)

These have no `*Command` / `*View` mirror because they don't correspond to a structured input/output shape at the application layer:

- product: `ActivateBomRequest`, `ChangeMakeVsBuyRequest`, `ChangeSalesPriceRequest`, `ChangeStandardCostRequest`, `CreateProductRequest`, `SetApprovedVendorsRequest` (wrapper over `List<ApprovedVendorCommand>`), `SetReorderPolicyRequest`, `SetValuationClassRequest`
- sales: `ChangeCustomerAddressRequest`, `ChangeCustomerContactRequest`, `ChangeCustomerNameRequest`, `DeactivateCustomerRequest`, `RegisterCustomerRequest`
- manufacturing: `AddBomLineRequest` + `AddBomLineResponse`, `CreateBomDraftRequest` + `CreateBomDraftResponse`, `SetPriorityRequest`, `SkipOperationRequest`, `ProductMaterialsCostResponse`
- purchasing: `ApprovePurchaseOrderRequest`, `SetSupplierProductPriceRequest`
- finance: `ManualReviewRequest`, `ReverseBySourceRequest` + `ReverseBySourceResponse`, `ReverseJournalEntryRequest`

These either bundle multiple positional service-method arguments under a wire-friendly record (the `Change*Request` family on Customer / Product etc.) or synthesise wire-only response shapes that no projection mirrors (`AddBomLineResponse`, `ReverseBySourceResponse`).

### CLAUDE.md flip

Replaced the §"Hexagonal layering invariant" View-pattern subsection so the canonical example shows the controller returning the View directly. Added a clear "when to introduce a separate api/dto/ type" rubric with the three categories: shape divergence, path-binding asymmetry, no application counterpart. Reworded the older "View pattern: aggregate → *View record" subsection to cross-reference the layering rule instead of describing a parallel `*Response.from(View)` mapping that's no longer the default. Added a code-review rule: "an api/dto/*Response that's a 1:1 mirror of an existing application/dto/*View is dead duplication."

### Layering invariant verification

All 8 Greps remain green:

| Check | Matches |
|---|---|
| `api/** → no domain` | 0 ✓ |
| `api/** → no infrastructure` | 0 ✓ |
| `application/** → no api` | 0 ✓ |
| `application/** → no infrastructure` | 0 ✓ |
| `domain/** → no api` | 0 ✓ |
| `domain/** → no application` | 0 ✓ |
| `domain/** → no infrastructure` | 0 ✓ |
| `infrastructure/** → no api` | **1** — `AuditAutoConfiguration` (documented exception) |

### Smoke

- `mvn clean install -DskipTests` — 28.3s SUCCESS across 19 modules.
- `mvn test` — 27.9s SUCCESS across all modules (test harness saga lifecycle tests still green).

### Process note

Two cascading-substring traps caught by the build: PowerShell `.Replace("SagaRow", "SagaRowView")` after the import line had already been updated produced `SagaRowViewView`; `.Replace("Response::from", "View::from")` produced `*View::from(view)` calls referencing nonexistent factories. Both fixed in follow-up passes. Lesson — for bulk renames where the new name is a superset of the old, prefer per-file Edit over PowerShell global replace.

---

## 2026-05-12 — CQRS `*View` pattern + 4-way hexagonal layering invariant locked in CLAUDE.md

Closed the last layering gap and wrote down the umbrella architecture rule that the previous two slices were aiming at. Two coordinated changes:

### 1. CQRS read-side `*View` pattern — eliminate `application/ → api/` and `infrastructure/ → api/` imports

Every `*QueryPort` interface in `application/` now returns a `*View` record in `application/dto/` instead of binding directly to the `api/dto/*Response` wire shape. The JDBC implementation builds Views in its `RowMapper`; the controller maps `View → Response` at the api boundary via a static `*Response.from(*View)` factory.

**Files changed (9 query-port stacks × 3 files each ≈ 27 touches):**

- **9 new `*View` records** in `application/dto/`:
  - 3× `SagaRowView` (sales, manufacturing, purchasing)
  - 6× reporting views (`AvailableToPromiseView`, `SalesOrder360View`, `PurchaseOrderTrackingView`, `ProductionPlanningView`, `MaterialShortageView`, `FinancialDashboardView`)
- **9 `*QueryPort` interfaces** switched return types to View
- **9 `Jdbc*QueryPort` impls** switched `RowMapper` output to View
- **9 DTOs** gained `static <X>Response from(<X>View v)` factory methods
- **9 controllers** add a one-line `.map(<X>Response::from)` at each endpoint

The View ↔ Response shape is currently a 1:1 field copy. The cost of strict layering is the duplicate type; the payoff is that wire-format renames and projection-column renames can now move independently — neither propagates across the layering line.

### 2. Hexagonal layering invariant documented in CLAUDE.md

New subsection §"Hexagonal layering invariant — the 4-way rule" before the existing two layering subsections. Contents:

- The 4×3 dependency matrix: `api → application`; `application → domain/shared`; `domain → shared-kernel` only; `infrastructure → application/domain/shared` (with documented exception).
- Per-direction rationale (why each forbidden import would corrupt the boundary).
- **8 machine-checkable Grep verification commands** — run them and any non-zero match outside the documented exception is a code-review fail.
- **Documented exception:** Spring `@AutoConfiguration` classes may import `api/` because they are layer-spanning bean factories by design (`WebMvcAutoConfiguration` does the same for Spring's own framework). Regular `@Configuration` / `@Component` / `@Service` classes do *not* get this carve-out.
- **The View pattern is documented** as the canonical way to satisfy `application/ → !api/` for CQRS read-side query ports — full code-sketch showing port + Jdbc impl + DTO `from(View)` factory + controller `.map(...)` chain.

### Invariant verification (final state)

Eight Greps, summary:

| Check | Matches |
|---|---|
| `api/** → no domain` | 0 ✓ |
| `api/** → no infrastructure` | 0 ✓ |
| `application/** → no api` | 0 ✓ |
| `application/** → no infrastructure` | 0 ✓ |
| `domain/** → no api` | 0 ✓ |
| `domain/** → no application` | 0 ✓ |
| `domain/** → no infrastructure` | 0 ✓ |
| `infrastructure/** → no api` | **1** — `shared.infrastructure.audit.AuditAutoConfiguration` (documented exception) |

### Smoke

- `mvn clean install -DskipTests` — 14.2s SUCCESS across 19 modules.
- `mvn test` — 26.1s SUCCESS across all 19 modules.

### Process note

A PowerShell global `SagaRow → SagaRowView` replace inadvertently turned three `SagaRowView` imports into `SagaRowViewView` (cascading substring match). Caught by the first build, fixed with a targeted `SagaRowViewView → SagaRowView` follow-up. Lesson for future bulk renames where the new name is a superset of the old: build first to find the cascades, then fix in one pass. Compile is the safety net — trust it.

---

## 2026-05-11 — Strict hexagonal layering: `SagaInstance` → shared-kernel, `*SagaPort` → application/saga

Cleared the last `domain → application` import in the codebase. Two coordinated moves:

1. **`SagaInstance`** moved from `shared/.../application/saga/` to `shared-kernel/.../domain/saga/SagaInstance.java`. It's framework-free abstract pattern code (state machine + lease + retry semantics with intent-named mutators) — same family as `DomainEvent`, which has lived in shared-kernel since day one. Now service `*Saga` aggregates (still in their service's `domain/saga/`) extend a shared-kernel type, not a shared-application type.
2. **Three service `*SagaPort` interfaces** (`SalesOrderFulfilmentSagaPort`, `MakeToOrderSagaPort`, `PurchaseToPaySagaPort`) moved from `<service>.domain.saga` to `<service>.application.saga`. They extend `shared.application.saga.SagaPort<S>` so they're application-shape by inheritance, not domain-shape — that's their natural home.

### Locked architectural decisions

- **SagaInstance lives in shared-kernel under `domain/saga/`** (sub-package, not flat — aligns with the per-service `<service>.domain.saga.*` convention so cross-module readers see the same package suffix).
- **`shared-kernel` is "framework-free shared types," not "pure VOs."** CLAUDE.md wording updated. `DomainEvent` already broke the strict VO framing; `SagaInstance` formalises the broader purpose: it's where domain aggregates pick up types they share across services.
- **The View workaround discussed earlier was unnecessary.** With SagaInstance in kernel, the *SagaPort can stay generic on the service's *Saga (in domain) — application→domain is the allowed direction. No need for a separate *SagaView record indirecting through application.

### Scope

- **1 file moved to shared-kernel** + 1 Javadoc cleanup (the `{@link SagaPort#claimDue}` reference now points to a type shared-kernel can't see, rephrased to plain text).
- **3 files moved to `<service>.application.saga`** + their package declarations updated.
- **16 import rewrites across 16 files** via PowerShell global FQN replace.
- **5 source files needed new explicit imports** where types that used to be same-package became cross-package after the moves:
  - `shared.application.saga.SagaPort` and `SagaManager` — added `import com.northwood.shared.domain.saga.SagaInstance;`
  - The 3 moved `*SagaPort` files — added `import com.northwood.<service>.domain.saga.<Saga>;`

### Invariant verification

Four greps, all return zero matches:

```
^import com\.northwood\.\w+\.infrastructure\.    (in api/**, application/**, domain/**)
^import com\.northwood\.\w+\.application\.       (in domain/**)
```

The codebase now satisfies strict hexagonal layering — `domain → shared-kernel` is the only outward cross-module dep from domain, which is exactly what shared-kernel was designed for.

### Smoke test

- `mvn clean install -DskipTests` — 15.2s SUCCESS across 19 modules.
- `mvn test` — 26.2s SUCCESS, all unit + harness saga lifecycle tests green.

---

## 2026-05-11 — `shared-infrastructure` → `shared` module split

Renamed the shared module from `shared-infrastructure` to `shared` and split its internals so the application-vs-infrastructure layering rule that every per-service module follows now applies to it too. The module name finally matches its package root `com.northwood.shared.*`.

### Locked architectural decisions

- **`shared-kernel` stays separate.** The framework-free guarantee is load-bearing: it's the only thing the 6 `*-events` jars depend on, and merging into `shared` would force every consumer to drag Spring transitively through the wire-contract jars. The §1A events-jar split assumed this property; preserved.
- **Module name is bare `shared`.** Rejected `shared-core` — semantically overlaps with `shared-kernel` (both names mean "the central thing") and forces contributors to learn an arbitrary kernel-vs-core distinction. `shared-kernel` + `shared` reads cleanly as "the framework-free DDD kernel, plus the broader Spring-aware shared layer."
- **`AuditController` got its own `com.northwood.shared.api.audit` namespace.** Symmetric with the per-service `application/infrastructure/api` layering. Future shared REST endpoints (if any) land there without re-litigating placement.

### Scope

**14 files moved out of `com.northwood.shared.infrastructure.*`:**

| To | Files |
|---|---|
| `shared.application.outbox` | OutboxPort, OutboxRow |
| `shared.application.inbox` | InboxPort, InboxRow |
| `shared.application.messaging` | EventEnvelope, EventPublisher, InboxEnvelopeHandler, AbstractInboxHandler |
| `shared.application.saga` | SagaInstance, SagaPort, SagaManager |
| `shared.application.audit` | AuditEntry |
| `shared.application.security` | CurrentUserAccessor |
| `shared.api.audit` | AuditController |

**Stayed in `com.northwood.shared.infrastructure.*`** — JDBC + Kafka adapters, Spring `@AutoConfiguration` / `@Component` / `@Scheduled`, Liquibase + security wiring:
- `outbox/OutboxPublisher` + `outbox/jdbc/{JdbcOutboxAdapter,JdbcOutboxAutoConfiguration}`
- `inbox/jdbc/{JdbcInboxAdapter,JdbcInboxAutoConfiguration}`
- `messaging/kafka/{KafkaEventPublisher,KafkaInboxDispatcher,KafkaMessagingAutoConfiguration}`
- `saga/{SagaStateInvariantCheck,SagaStateInvariantChecker,SagaInvariantsAutoConfiguration}`
- `audit/{JdbcAuditQueryAdapter,AuditAutoConfiguration}`
- `security/{KeycloakRealmRoleConverter,OAuth2ResourceServerSecurityConfig}`
- `db/LiquibaseConfig`

**Importer rewrites.** 390 import statements across 169 files, mechanical search-replace per moved FQN.

**Five files needed *new* cross-package imports** (formerly same-package, now cross-package after the split):
- `OutboxPublisher` — added `OutboxPort`, `OutboxRow` imports
- `JdbcAuditQueryAdapter` — added `AuditEntry` import
- `AuditAutoConfiguration` — added `AuditController` import
- `OAuth2ResourceServerSecurityConfig` — added `CurrentUserAccessor` import
- `OutboxPublisherTest` (test) — added `OutboxPort`, `OutboxRow` imports

`AuditController` also gained `AuditEntry` + `JdbcAuditQueryAdapter` imports as part of its move to `api/audit/`.

**Module rename mechanics.** `git mv shared-infrastructure shared`; parent pom `<module>` entry + `<dependencyManagement>` entry + 9 consumer service poms (product/sales/inventory/manufacturing/purchasing/finance/reporting/test-harness + own pom) had `<artifactId>shared-infrastructure</artifactId>` → `<artifactId>shared</artifactId>`. Shared pom's `<name>Northwood :: Shared Infrastructure</name>` → `<name>Northwood :: Shared</name>`. demo-web-ui-bff and erp-web-ui-bff have no compile dep on the shared module — comments referencing it were updated.

**META-INF auto-config registration unchanged.** All 7 entries in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` point to FQNs that stayed in `shared.infrastructure.*`. Verified — no edits needed.

**Documentation refreshed.** CLAUDE.md (§Module layout, §Outbox/Inbox, §Saga base, §Spring Boot quirks), README.md, demo-web-ui-design.md, demo-web-ui/README.md updated to reference the new module name + the internal application/infrastructure/api split.

### Smoke test

- `mvn clean install -DskipTests` from repo root — **BUILD SUCCESS** in 14.5s, all 19 modules compiled.
- `mvn test` from repo root — **BUILD SUCCESS** in 26.5s, all unit + harness tests green. Test-harness E2E saga tests (O2C happy path, M2O shortage recovery, P2P happy path, sub-assembly recursion, set-priority cascade, etc.) all pass — confirms the moved ports + abstract bases work correctly through real handler/worker wiring.

### Follow-ups

- **`.claude/settings.local.json`** — historical permission allowlist contains stale `shared-infrastructure` literal paths (won't match the renamed module). Cosmetic; user can re-grant when the next prompt requires them. Could clean up, but not part of this slice.
- **IDE re-indexing.** Recommend closing and reopening the IntelliJ project — ~170 files moved + 390 imports rewritten will churn the caches.

### Process note (recovery from PowerShell mishap)

First attempt to update package declarations on the 14 moved files used relative paths + a `[System.IO.File]::ReadAllText` call whose throw was silently swallowed by PowerShell's default error handling, then `WriteAllText` wrote the (empty) post-throw `$content` variable, zeroing all 14 files. Recovered because `git mv` had no effect (files were untracked — no commits yet) so the originals at OLD paths were intact; deleted the empty NEW-path files and re-moved with plain `Move-Item`. Then re-ran the package-declaration update with absolute paths + `$ErrorActionPreference = "Stop"` + non-empty content assertions. Lesson: when scripting bulk file rewrites with `[System.IO.File]::*`, always use absolute paths (PowerShell's `Set-Location` doesn't update .NET's `Environment.CurrentDirectory`) and set `$ErrorActionPreference = "Stop"` so a failing read aborts before the write executes.

---

## 2026-05-11 — `application/dto/` reorganisation (final structural pass)

Final pass on the application-layer convention: all `*View`, `*Command`, and `*Request` records now live in a sibling `application/dto/` sub-package, separated from the `*Service` orchestration files. Services in `application/` hold only orchestration logic (use cases, exception types, helper services); data shapes sit in `application/dto/`.

### Rationale (locked)

> "A `*View` is a mirror record of an aggregate; it can live outside a single service. DTO means brainless data — moving these out of services makes the services focus purely on application/business logic."

Two payoffs:

1. **`application/` becomes single-purpose** — open any service file end-to-end, you see use cases + exceptions, not interleaved with 50-line View record declarations.
2. **`application/dto/` is the canonical "what shapes does this service expose?" lookup** — a flat namespace of pure data. Mirrors `api/dto/` on the wire side.

### Scope

Touched every service module. Moved or extracted:

| Module | Commands moved | Requests moved | Views extracted |
|---|---|---|---|
| inventory | PostShipmentCommand, PostGoodsReceiptCommand | ShipmentLineRequest, GoodsReceiptLineRequest | StockItemView, ShipmentView+LineView, GoodsReceiptView+LineView |
| product | (none top-level) — ApprovedVendorCommand was nested | — | ProductView, ApprovedVendorCommand |
| sales | PlaceOrderCommand, CancelOrderCommand | — | CustomerView, SalesOrderView+LineView |
| manufacturing | ReleaseCommand, CompleteOperationCommand | — | WorkOrderView, WorkOrderMaterialView, WorkOrderOperationView |
| purchasing | CreateRequisitionCommand, WorkOrderShortageCommand | RequisitionLineRequest | SupplierView, PurchaseRequisitionView+LineView, PurchaseOrderView+LineView, PriceView |
| finance | RecordSupplierInvoiceCommand, RecordSupplierPaymentCommand (+ Multi), RecordCustomerPaymentCommand (+ Multi) | — | SupplierInvoiceView+LineView, CustomerInvoiceView+LineView, PaymentView+AllocationView, JournalEntryView+LineView, RateView |

Roughly 35 dto records across 7 modules. Top-level Command files relocated (package declaration + path), nested `*View` records extracted from their service files to standalone `application/dto/*.java` files.

### Files that stayed in `application/`

Anything that holds **logic**, not just data:
- `*Service` classes (use-case orchestration)
- `*Lookup` / `*QueryPort` interfaces (have method contracts)
- `*Projection` interfaces in `application/inbox/` (inbox-handler ports)
- Saga managers in `application/saga/`
- Application exceptions (nested static classes on services — `CustomerService.DuplicateCustomerCodeException`, `SalesOrderService.OrderNotCancellableException`, etc.)
- Helper services that don't fit cleanly into one aggregate's service (`SalesOrderCompensationEmitter`, `WorkOrderReleaseService`, etc.)

### Importer updates

Every controller, DTO, test, and test-harness fake that referenced a moved/extracted type was updated to the new import. Mostly mechanical (`application.X` → `application.dto.X`, or `Service.NestedView` → `application.dto.NestedView`).

Notable extras:
- `PurchaseRequisitionService.createManual` reloads the PR post-PR→PO conversion before mapping to `PurchaseRequisitionView` (the side-effect status flip wasn't visible on the in-memory aggregate before conversion).
- `SupplierProductPriceService.PriceView` was nested; extracted to `application/dto/PriceView.java`.
- `ExchangeRateService.RateView` was nested; extracted (the `RateNotFoundException` stays on the service since it's an exception, not a data shape).

### Build status

`mvn test` — all 19 modules SUCCESS in ~30 seconds.

### CLAUDE.md update

The Controllers section now codifies the `application/dto/` sub-package convention with examples. Grep `Grep '\*View\|\*Command\|\*Request' **/application/dto/` is the canonical service-shape lookup; nothing of that suffix should remain as a top-level file in `application/` or as a nested record on a service.

---

## 2026-05-11 — `*Lookup` / `*QueryPort` package-placement sweep

Codebase-wide audit + fix to align every `*Lookup` and `*QueryPort` interface with the naming-table convention: interface in `application/`, `Jdbc*` impl in `infrastructure/persistence/` (or `infrastructure/saga/` where applicable). Domain remains the home of `*Repository` only.

### Misplacements found and fixed

| Suffix | Was | Now |
|---|---|---|
| `sales.CustomerLookup` | `infrastructure/persistence/` | `application/` |
| `sales.ProductPricingLookup` | `infrastructure/persistence/` | `application/` |
| `manufacturing.BomLookup` | `domain/` | `application/` |
| `purchasing.SupplierProductPriceLookup` | `domain/` | `application/` |
| `finance.GlAccountLookup` | concrete class in `infrastructure/persistence/` | interface in `application/`, JDBC body extracted to new `JdbcGlAccountLookup` in `infrastructure/persistence/` |
| `purchasing.ApprovedVendorQueryPort` | `domain/` | `application/` |
| `manufacturing.MakeToOrderShortageRecoveryQueryPort` | `domain/saga/` | `application/saga/` |

### Side-effects worth noting

- **`GlAccountLookup` was a concrete `@Repository` class**, not an interface — `InMemoryGlAccountLookup` worked around this by extending it and passing `super(null)` for the `JdbcTemplate`. Extracted the interface; the in-memory variant now `implements GlAccountLookup` cleanly without the null-JdbcTemplate dance.
- **`BomEditRepository` (domain) had a `{@link BomLookup}` Javadoc cross-ref** that would have become a domain→application upward reference. Demoted to plain `{@code BomLookup}` mention — keeps the meaning, drops the would-be layering violation in Javadoc imports.
- **No domain code referenced any of the moved interfaces** (verified by grep before each move), so no aggregate/domain-service had to be refactored.

### Naming distinction reaffirmed (not collapsed)

Considered renaming `*QueryPort` → `*Lookup` for consistency; rejected. The two suffixes carry different reading expectations:
- **`*Lookup`** = narrow value resolution, single-key → single value/record (`findUnitPrice`, `byCode`, `findByProductId`). Cheap, point-lookup-shaped.
- **`*QueryPort`** = CQRS read-side query, multi-row, often join-based or filter-based. Returns lists or composite views.

Both `ApprovedVendorQueryPort` (returns `List<ApprovedVendor>` ordered by preferred-first) and `MakeToOrderShortageRecoveryQueryPort` (joins saga to work_order_material, filters by a received-product set) are genuine query-port shape — the suffix conveys cost and result shape, so consolidating loses signal. Convention left unchanged.

### Files touched

7 interfaces moved/refactored, ~25 importers updated across main + test + test-harness modules, 1 new `JdbcGlAccountLookup` impl created, 4 old files deleted.

### Build status

`mvn -DskipTests -pl <affected> -am test-compile` — full 16-module reactor SUCCESS at every step (per-suffix verification after Lookup sweep, again after QueryPort sweep).

### Layering invariant now uniformly enforceable

The architectural rule `Grep '*Lookup|*QueryPort' **/domain/**/*.java` should now return zero hits (apart from `*Repository` interfaces themselves). Combined with the existing `JdbcTemplate **/application/**/*.java` zero-hit rule and the no-`*Projection`-outside-`application/inbox/` rule, the codebase's read/write port layering is now fully mechanically checkable.

### Follow-up: collapsed a 1:1 query-port result mirror

While reviewing `ApprovedVendorQueryPort` post-move, spotted that its nested `ApprovedVendor` record (`UUID supplierId, String supplierCode, String supplierName, boolean preferred`) was byte-identical to `com.northwood.product.domain.ApprovedVendor` shipped in `product-events`. The purchasing service's `product_approved_vendor` projection is a 1:1 cache of the upstream `ApprovedVendorListChanged` payload, so the query-port result was duplicating the wire VO with no narrowing.

Applied the same logic as the `*Payload` consolidation rule (CLAUDE.md "Consumer-side rule for *Payload records") to query-port result types: 1:1 mirror → drop the consumer-side record, return the producer's VO directly.

- Removed nested record from `ApprovedVendorQueryPort`; `findApprovedFor` now returns `List<com.northwood.product.domain.ApprovedVendor>`.
- `JdbcApprovedVendorQueryPort`, `InMemoryApprovedVendorQueryPort`, `PurchaseOrderService` updated (4 files total).
- Interface Javadoc records the reuse and the escape hatch: introduce a consumer-side record if purchasing's read shape ever diverges from the wire schema.

Build green (16 modules SUCCESS). Same payoff as the payload consolidation: refactor-safe (a rename of `supplierCode` upstream now surfaces as a compile error in purchasing), one less type to keep in sync. The CLAUDE.md `*Payload` rule should be read as applying to consumer-side read-DTOs generally (query-port results, projection-derived records), not just inbox-handler payloads.

---

## 2026-05-11 — Full ban on `domain.*` imports in `api/` (View + Command pattern)

Architectural decision: `api/` depends only on `application/`, with **zero** `com.northwood.<service>.domain.*` imports across all controllers, DTOs, and exception handlers. The application layer is the sole seam between API and the rest of the system. Mechanically enforceable via `Grep '^import com\.northwood\.\w+\.domain\.' **/api/**/*.java` → zero matches.

### Rationale (locked)

Domain alone is not an integrity boundary. The `Product` aggregate has mutator methods (`changeSalesPrice`, `discontinue`, etc.) whose side effects only complete when the application service drives `repository.save(...)` — which also writes the pending events to the outbox in the same transaction. An aggregate handed to a controller is a half-thing whose mutators can be called silently:

```java
// nothing stops a future maintainer writing this — and nothing detects it:
Product p = service.findById(id).orElseThrow();
p.discontinue();   // mutates aggregate, never persisted, never emitted, silent corruption
return ProductResponse.from(p);
```

Application + domain + infrastructure together form the integrity boundary. Exposing only domain to `api/` exposes half the boundary. The strict ban removes the footgun structurally: a controller that has only a `ProductView` literally cannot invoke a domain mutator.

### The three patterns that make the ban possible

1. **`*View` records on the application service.** Every aggregate that has a read-side surface gets a `*View` record nested on its service (`ProductService.ProductView`, `SalesOrderService.SalesOrderView` + `SalesOrderLineView`, etc.). Service methods return `Optional<*View>` / `List<*View>` / `*View` directly. DTOs map from the View, never the aggregate. Mapping logic centralises on the View's `from(Aggregate)` static factory inside the service file.

2. **`*Command` records on the application service** for inputs that would otherwise require a domain VO at the controller boundary. Example: `ProductService.ApprovedVendorCommand` instead of `domain.ApprovedVendor` in `setApprovedVendors`. Service maps `*Command` → domain VO internally before invoking the aggregate.

3. **Raw `UUID` at the service public boundary, identity VO wrap inside the service.** `service.findById(UUID id)` wraps to `ProductId.of(id)` on the first line. Identity VOs flow through `application/` and `domain/` internally but never appear in `api/` constructor signatures, path-variable wrappers, or DTO field types.

### Scope of the change

Touched every service that has a controller — 14 services across 7 modules. Per service: added the View record(s) (single aggregate or master+lines), changed service method signatures to return/accept Views, updated the corresponding `*Response` DTO to take a View, removed the `domain.*` import from controller + DTO.

| Service | Views added | Service method change shape |
|---|---|---|
| ProductService | `ProductView` | `createProduct` returns View; `findById` / `findAll` return Views; `setApprovedVendors` accepts `List<ApprovedVendorCommand>` |
| CustomerService | `CustomerView` | `registerCustomer` returns View; reads return Views |
| SalesOrderService | `SalesOrderView`, `SalesOrderLineView` | `placeOrder` returns View; read paths return Views |
| StockItemService | `StockItemView` | read-only — all reads return Views |
| ShipmentService | `ShipmentView`, `ShipmentLineView` | `post` returns View |
| GoodsReceiptService | `GoodsReceiptView`, `GoodsReceiptLineView` | `post` returns View |
| WorkOrderOperationService | `WorkOrderView`, `WorkOrderMaterialView`, `WorkOrderOperationView` | reads return Views; commands take `UUID` workOrderId |
| SupplierService | `SupplierView` | read-only |
| PurchaseRequisitionService | `PurchaseRequisitionView`, `PurchaseRequisitionLineView` | `createManual` returns View (reloads post-PR→PO-conversion to reflect status side effect) |
| PurchaseOrderService | `PurchaseOrderView`, `PurchaseOrderLineView` | `findById` returns View; `approve(UUID, ...)` |
| SupplierInvoiceService | `SupplierInvoiceView`, `SupplierInvoiceLineView` | `recordInvoice` returns View; manual review actions take `UUID` |
| CustomerInvoiceService | `CustomerInvoiceView`, `CustomerInvoiceLineView` | reads return Views (also closes the Cat A boundary — `findById` was still taking `CustomerInvoiceId`) |
| PaymentService | `PaymentView`, `PaymentAllocationView` | all 4 `record*Payment*` methods return View |
| JournalEntryService | `JournalEntryView`, `JournalEntryLineView` | `findById` + `reverseEntry` + `reverseBySourceDocument` all return Views or `UUID` lists |

### Earlier slices in the same session (ordered)

This session also shipped a sequence of incremental tightenings that built up to the View pattern:

1. **`*Lookup` / `*QueryPort` package-placement sweep.** Moved 5 lookups + 2 query ports from `infrastructure/persistence/` or `domain/` to `application/`. Extracted `GlAccountLookup` interface from a concrete `@Repository` class; `JdbcGlAccountLookup` is the new impl, `InMemoryGlAccountLookup` cleanly implements the interface. (See earlier sections in this entry.)
2. **Consolidated 1:1 query-port result mirror** — `ApprovedVendorQueryPort` returns `product.domain.ApprovedVendor` from `product-events` directly. (Above.)
3. **Domain-leak audit and fixes — four narrow rules first (C/D/E/F as discussed), before going to the full ban.** Closed:
   - **F**: wrapped `SupplierProductPriceRepository.PriceRow` leak via `SupplierProductPriceService.PriceView`.
   - **E**: wrapped three domain exceptions (`OrderNotCancellableException`, `PoNotApprovableException`, `RateNotFoundException`) into application-layer counterparts; also pulled `RateSnapshot` → `RateView`.
   - **D**: removed `new ApprovedVendor(...)` from `ProductController` via `ProductService.ApprovedVendorCommand`.
4. **Category A: services accept `UUID` at boundary, wrap to `*Id` internally.** Touched 9 controllers + ~30 service methods across 4 modules. Removed identity-VO imports from controllers entirely.

### Test impact

Test updates required in services where the application service's public signatures changed:
- `ProductServiceTest.CreateProduct.registers_new_product_and_emits_created_event` — captures `ProductView` instead of `Product`.
- `CustomerServiceTest.RegisterCustomer.registers_active_customer_and_emits_registered_event` — same.
- `WorkOrderOperationServiceTest` — `CompleteOperationCommand` now takes `UUID` (was `WorkOrderId`); `skipOperation(UUID, ...)`.
- `JournalEntryServicePostingsTest`, `JournalEntryServiceReverseBySourceTest` — `reverseEntry(UUID, ...)` returns `UUID`; `reverseBySourceDocument` returns `List<UUID>`.
- `PurchaseToPayHappyPathTest`, `SubAssemblyRecursionTest`, `OrderToCashHappyPathTest` (test-harness) — updated to pass `.value()` at boundaries or accept Views.
- `SalesTestKit.placeOrder` — returns `service.placeOrder(cmd).id()` (View's `id()` accessor, not `.id().value()`).

No test was deleted; the View/Command shape preserves all test intent.

### Architectural payoff

- **Layering is now mechanically enforceable** via three composable grep checks:
  - `Grep '^import com\.northwood\.\w+\.domain\.' **/api/**/*.java` → zero matches (this slice).
  - `Grep 'JdbcTemplate' **/application/**/*.java` → zero matches (Phase 1 + 2).
  - `Grep '*Lookup|*QueryPort' **/domain/**/*.java` → zero matches (the package-placement sweep above).
- **Wire-shape evolution and domain-shape evolution decouple.** A Jackson-time JSON field rename touches `*Response` only. A domain field rename touches the aggregate + `*View.from(...)` mapper. Each lives in exactly one place.
- **Database-per-service split touches only `application.yml`.** No `api/` change needed.

### Build status

`mvn test` — all 19 modules SUCCESS in ~26 seconds (Phase 1: lookup/queryport sweep; Phase 2: 4-rule cleanup; Phase 3: Cat A; Phase 4: Views). Each phase verified independently before moving to the next.

### CLAUDE.md update

The Controllers (`api/`) section has been rewritten to codify the View + Command + UUID-boundary patterns and the strict no-domain-imports rule with its rationale. See "Controllers (`api/`) must depend only on `application/`" in CLAUDE.md.

---

## 2026-05-10 — Events-jar split: `finance-events` ✅ §1A COMPLETE

Sixth and final slice of §1A. 4 events: `CustomerInvoiceCreated`, `CustomerPaymentReceived`, `SupplierInvoiceApproved`, `SupplierPaymentMade`. All self-contained.

### Consumer-side cleanup

Deleted 8 1:1-mirror payload records:

| Producer event | Consumers (deleted payloads) |
|---|---|
| `finance.CustomerInvoiceCreated` | sales, reporting (root + dashboard) |
| `finance.CustomerPaymentReceived` | sales, reporting (root + dashboard) |
| `finance.SupplierInvoiceApproved` | purchasing, reporting (root + dashboard) |
| `finance.SupplierPaymentMade` | purchasing, reporting (root + dashboard) |

12 handlers updated (sales: `CustomerInvoiceCreatedHandler`, `CustomerPaymentReceivedHandler`; purchasing: `SupplierInvoiceApprovedHandler`, `SupplierPaymentMadeHandler`; reporting: 4 root + 4 dashboard variants). 3 handler tests + the test-harness `OrderToCashHappyPathTest` updated for the renamed types.

### Pom changes

- Parent pom: `finance-events` registered.
- `finance-service`: depends on `finance-events`.
- 3 consumer services (sales, purchasing, reporting) each declare `finance-events`.

### Build status

`mvn clean install -DskipTests` — all 19 modules SUCCESS in ~15s.

### §1A wrap-up

All 6 producer-events jars now exist: `product-events` (9 events + `ApprovedVendor` VO), `sales-events` (11 events), `inventory-events` (5 events), `manufacturing-events` (11 events), `purchasing-events` (4 events), `finance-events` (4 events). 44 events total. **All cross-service `*Payload` records deleted** — every consumer handler imports the producer's record directly. The `<producer>-events` jar is the only thing crossing module boundaries; no `*-service` module ever depends on another `*-service` module. Original §1A done-condition met.

The wire schema is now compile-time-checked across the codebase: a producer-side breaking change (rename a field, change a type, drop a field) becomes a compile error in every consumer that touches the field. Additive changes (new field) stay backward-compatible via Jackson 3 default deserialisation tolerance, exactly as the architecture intended.

---

## 2026-05-10 — Events-jar split: `purchasing-events`

Fifth slice of §1A. 4 events: `PurchaseRequisitionCreated`, `PurchaseOrderCreated`, `PurchaseOrderApproved`, `SupplierProductPriceChanged`. All self-contained.

### Consumer-side cleanup

Deleted 4 payload records — all 1:1 mirrors *except* `finance.PurchaseOrderCreatedPayload`, which lacked the `sourceWorkOrderId` field that the producer event grew during §2.1. The handler doesn't read that field, so the substitution still works at runtime — but the test that constructs a payload via the all-args constructor needed a `null` for the new field. Treated as an opportunistic correction rather than a new "narrowed payload" exception.

| Producer event | Consumer (deleted payload) |
|---|---|
| `purchasing.SupplierProductPriceChanged` | manufacturing |
| `purchasing.PurchaseOrderCreated` | finance, reporting (×3 sub-package variants too — atp, dashboard, shortage) |
| `purchasing.PurchaseRequisitionCreated` | reporting (shortage variant) |

### Files updated

7 handlers (manufacturing's `SupplierProductPriceChangedHandler`, finance's `PurchaseOrderCreatedHandler`, and 5 reporting variants — root, atp, dashboard, shortage `PurchaseOrderCreatedHandler` + shortage `PurchaseRequisitionCreatedHandler`). 1 handler test (`finance.PurchaseOrderCreatedHandlerTest`) updated for the renamed type and for the wider all-args constructor.

`purchasing.PurchaseOrderApproved` has no current consumer (the saga manager advances its state via inline service calls, not an inbox handler). It rides along in `purchasing-events` so future consumers can compile against it directly.

### Pom changes

- Parent pom: `purchasing-events` registered in `<modules>` and `<dependencyManagement>`.
- `purchasing-service`: depends on `purchasing-events`.
- 3 consumer services (manufacturing, finance, reporting): added `purchasing-events` dep. `test-harness` not updated — no test consumes the deleted payloads directly via FQCN, and the events transitively land via the service deps.

### Build status

`mvn clean install -DskipTests` — all 18 modules SUCCESS in ~13s.

---

## 2026-05-10 — Events-jar split: `manufacturing-events`

Fourth slice of §1A. Largest split so far: 11 events emitted by manufacturing — `WorkOrderCreated`, `WorkOrderManufacturingCompleted`, `OperationCompleted`, `RawMaterialReservationRequested`, `ManufacturingDispatched`, `RawMaterialShortageDetected`, `WorkOrderCancelled`, `SalesOrderCancellationApplied`, `SubAssembliesConsumed`, `WorkOrderPriorityChanged`, `ProductMaterialsCostComputed`. All self-contained (no external VOs).

### Consumer-side cleanup

Deleted 15 payload records, including the long-running `sales.CancellationAppliedPayload` shared-payload-for-two-events that was kept alive in the inventory-events slice for manufacturing's `SalesOrderCancellationApplied` consumer. Both halves now consume the producer record directly:
- `sales.InventoryCancellationAppliedHandler` → `inventory.SalesOrderCancellationApplied` (shipped in inventory-events slice).
- `sales.ManufacturingCancellationAppliedHandler` → `manufacturing.SalesOrderCancellationApplied` (this slice).

`CancellationAppliedPayload` deleted as a result.

### Files updated

22 handlers + services updated across sales, inventory, manufacturing, purchasing, reporting (root + atp + shortage + dashboard + board sub-packages):

- inventory: `WorkOrderManufacturingCompletedHandler`, `RawMaterialReservationRequestedHandler`, `SubAssembliesConsumedHandler`, `WorkOrderCancelledHandler`, `StockReservationService` (the `reserveForWorkOrder(...)` signature now takes `manufacturing.RawMaterialReservationRequested`).
- sales: `WorkOrderCreatedHandler`, `ManufacturingDispatchedHandler`, `WorkOrderManufacturingCompletedHandler`, `ManufacturingCancellationAppliedHandler`.
- purchasing: `RawMaterialShortageDetectedHandler` (drives the auto-requisition for the missing components).
- reporting: `WorkOrderCreatedHandler` (root + atp + dashboard variants), `WorkOrderManufacturingCompletedHandler` (root + atp + board variants), `BoardWorkOrderCompletedHandler`, `BoardWorkOrderPriorityChangedHandler`, `BoardWorkOrderCancelledHandler`, `OperationCompletedHandler`, `ShortageDetectedHandler` (root + shortage variants).

7 handler tests updated in line with the rename. `CancelCompensationTest` got a one-line comment fix (the inline payload it constructs via `Map.of(...)` was unaffected since it never imported the now-deleted `CancellationAppliedPayload` type).

### Pom changes

- Parent pom: added `manufacturing-events` to `<modules>` and `<dependencyManagement>`.
- `manufacturing-service`: added `manufacturing-events` dep.
- 4 consumer services + `test-harness`: added `manufacturing-events` dep.

### Build status

`mvn clean install -DskipTests` — all 17 modules SUCCESS in ~14s.

---

## 2026-05-10 — Events-jar split: `inventory-events`

Third slice of §1A. New `inventory-events/` Maven module containing the wire-format records every other service consumes when subscribing to inventory mutations. 5 events: `StockReserved`, `RawMaterialsReserved`, `GoodsReceived`, `ShipmentPosted`, `SalesOrderCancellationApplied`. No external VOs — all nested `*Line` / `*Component` records live inside their parent event.

### Consumer-side cleanup

Deleted 11 1:1-mirror payload records across 5 services:

| Producer event | Consumers (deleted payloads) |
|---|---|
| `inventory.StockReserved` | sales, reporting |
| `inventory.RawMaterialsReserved` | manufacturing, reporting |
| `inventory.GoodsReceived` | manufacturing, finance, purchasing, reporting |
| `inventory.ShipmentPosted` | sales, finance, reporting |

### Special case: `sales.CancellationAppliedPayload` lives on

Sales' two cancellation handlers (`InventoryCancellationAppliedHandler` and `ManufacturingCancellationAppliedHandler`) historically share a single local payload type — `CancellationAppliedPayload` — because both `inventory.SalesOrderCancellationApplied` and `manufacturing.SalesOrderCancellationApplied` carry the same wire shape modulo a count field name (`reservationsReleased` vs `workOrdersCancelled`). With this slice, the inventory-side handler switches to the producer event `inventory.SalesOrderCancellationApplied` from `inventory-events`. The manufacturing-side handler keeps using `CancellationAppliedPayload` until the manufacturing-events slice ships, at which point both move to producer events and the shared payload deletes.

### Files updated

- 14 handlers across sales/manufacturing/finance/purchasing/reporting (root + atp + shortage sub-packages).
- 1 service-layer call site: sales' `InventoryCancellationAppliedHandler` now imports `inventory.SalesOrderCancellationApplied`.
- 5 handler tests.
- 3 test-harness E2E tests (`OrderToCashHappyPathTest`, `MakeToOrderShortagePathTest`, `PurchaseToPayHappyPathTest`).

### Pom changes

- Parent pom: added `inventory-events` to `<modules>` and `<dependencyManagement>`.
- `inventory-service`: added `inventory-events` dep.
- 5 consumer services + `test-harness`: added `inventory-events` dep.

### Build status

`mvn clean install -DskipTests` — all 16 modules SUCCESS in ~13s.

---

## 2026-05-10 — Events-jar split: `sales-events`

Second slice of §1A. New `sales-events/` Maven module shipping the wire-format records every other service consumes when subscribing to sales-order or customer-master changes. 11 events + zero VOs (every event is self-contained — nested `*Line` records live inside their parent event).

### Module contents

`sales-events/` depends only on `shared-kernel` and contains 11 records under `com.northwood.sales.domain.events.*`: `SalesOrderPlaced`, `SalesOrderShipped`, `SalesOrderCancellationRequested`, `SalesOrderCompensated`, `ManufacturingRequested`, `StockReservationRequested`, plus customer-master events (`CustomerRegistered`, `CustomerNameChanged`, `CustomerContactChanged`, `CustomerAddressChanged`, `CustomerDeactivated`).

### Consumer-side cleanup

Deleted 7 `*Payload` records — all 1:1 mirrors of their producer event:

| Consumer payload | Producer event |
|---|---|
| `inventory.StockReservationRequestedPayload` | `sales.StockReservationRequested` |
| `inventory.SalesOrderCancellationRequestedPayload` | `sales.SalesOrderCancellationRequested` |
| `manufacturing.ManufacturingRequestedPayload` | `sales.ManufacturingRequested` |
| `manufacturing.SalesOrderCancellationRequestedPayload` | `sales.SalesOrderCancellationRequested` |
| `finance.SalesOrderShippedPayload` | `sales.SalesOrderShipped` |
| `reporting.SalesOrderPlacedPayload` | `sales.SalesOrderPlaced` |
| `reporting.SalesOrderCompensatedPayload` | `sales.SalesOrderCompensated` |

10 handler / service / saga-worker files updated:
- `inventory.StockReservationRequestedHandler`, `inventory.SalesOrderCancellationRequestedHandler`, `inventory.application.StockReservationService` (public method now takes `StockReservationRequested`).
- `manufacturing.ManufacturingRequestedHandler`, `manufacturing.SalesOrderCancellationRequestedHandler`, `manufacturing.infrastructure.saga.MakeToOrderSagaWorker` (now imports `sales.domain.events.ManufacturingRequested.RequestedLine` directly).
- `finance.SalesOrderShippedHandler`, `finance.application.CustomerInvoiceService` (the auto-invoicing path now consumes the producer event verbatim).
- `reporting.SalesOrderPlacedHandler` (root) + `reporting.dashboard.SalesOrderPlacedHandler`, `reporting.SalesOrderCompensatedHandler`.

5 tests updated in line with the rename: `SalesOrderCancellationRequestedHandlerTest` (inventory), `StockReservationServiceTest` (inventory), `SalesOrderShippedHandlerTest` (finance), and the two test-harness E2E flows under `testharness.m2o` (`SubAssemblyRecursionTest`, `MakeToOrderShortagePathTest`).

### Pom changes

- Parent pom — added `sales-events` to `<modules>` and `<dependencyManagement>`.
- `sales-service` — added `sales-events` dep.
- 4 consumer services + `test-harness` — added `sales-events` dep.

### Build status

`mvn clean install -DskipTests` — all 15 modules SUCCESS in ~14s. Tests not run as part of this slice; structural change only.

### Customer events have no consumers today

The 5 customer-master events (`CustomerRegistered`, `CustomerNameChanged`, etc.) ride along in `sales-events` because they're emitted by sales' customer aggregate, but no other service currently consumes them — by design (snapshot-only policy on customer fields documented in `design-notes.md`). Shipping them in the events jar costs nothing and means the wire schema is in place when a future consumer (e.g. reporting customer directory) needs it.

---

## 2026-05-10 — Events-jar split: `product-events` pilot

First slice of the per-producer events-jar pattern. Created a sibling Maven module `product-events/` containing the wire-format records every other service consumes when subscribing to product-master events. Producer (`product-service`) and every consumer (`sales`, `inventory`, `manufacturing`, `purchasing`, `finance`, `reporting`, `test-harness`) now depend on this single shared schema artefact instead of each consumer hand-mirroring the producer record as a `*Payload`.

### Module contents

`product-events/` depends only on `shared-kernel` and contains:
- `com.northwood.product.domain.events.*` — 9 records (`ProductCreated`, `ProductDiscontinued`, `ReorderPolicyChanged`, `MakeVsBuyChanged`, `ValuationClassChanged`, `BomActivated`, `SalesPriceChanged`, `StandardCostChanged`, `ApprovedVendorListChanged`).
- `com.northwood.product.domain.ApprovedVendor` — VO referenced by `ApprovedVendorListChanged`. Kept at its natural package rather than moved into `domain.events.*`, so non-event code in `product-service` (aggregate, repository, controller, application service, test) keeps its existing imports unchanged.

No Spring, no JDBC, no aggregates, no application services — just the schema other services compile against.

### Consumer-side cleanup

Deleted 10 `*Payload` records from the 6 consumer services after confirming each was a 1:1 mirror of its producer event:

| Consumer payload | Producer event |
|---|---|
| `inventory.ReorderPolicyChangedPayload` | `product.ReorderPolicyChanged` |
| `manufacturing.MakeVsBuyChangedPayload` | `product.MakeVsBuyChanged` |
| `manufacturing.BomActivatedPayload` | `product.BomActivated` |
| `manufacturing.ProductCreatedPayload` | `product.ProductCreated` |
| `manufacturing.ApprovedVendorListChangedPayload` | `product.ApprovedVendorListChanged` |
| `sales.SalesPriceChangedPayload` | `product.SalesPriceChanged` |
| `finance.ValuationClassChangedPayload` | `product.ValuationClassChanged` |
| `finance.StandardCostChangedPayload` | `product.StandardCostChanged` |
| `purchasing.ApprovedVendorListChangedPayload` | `product.ApprovedVendorListChanged` |
| `reporting.ProductCreatedPayload` | `product.ProductCreated` |

10 corresponding handlers (`ReorderPolicyChangedHandler`, `MakeVsBuyChangedHandler`, etc.) updated to import the producer event directly. Two `ProductApprovedVendorProjection` interfaces (manufacturing + purchasing) and their `JdbcProductApprovedVendorProjection` impls switched from `ApprovedVendorListChangedPayload.ApprovedVendor` → `com.northwood.product.domain.ApprovedVendor`. Two test-harness in-memory projections updated the same way. One comment in `ReorderPolicyChangedSeamIT` clarified.

### Pom changes

- Parent pom — added `product-events` to `<modules>` and to `<dependencyManagement>` so consumers can omit the version.
- `product-service` — added `product-events` dep (the aggregate's domain events now live there).
- 6 consumer services + `test-harness` — added `product-events` dep.

### Decision: include VOs at their natural package, don't enforce strict events-only

The user's initial framing was "events-jar contains only `<service>.domain.events`". The `ApprovedVendor` VO referenced by `ApprovedVendorListChanged` forced a scope question: move the VO into `domain.events.*`, inline a duplicate nested record on the event, or relax the scope rule to "events + VOs they transitively reference at their natural package." Picked the third — minimises code churn (no import changes anywhere in `product-service`), preserves the original author's separation (non-event code imports from `domain.*`, not `events.*`), and accepts that an events jar's logical scope is "the wire schema other services depend on", which includes the VO types referenced by the events.

### Build status

`mvn clean install -DskipTests` — all 14 modules SUCCESS in ~21s. Tests not run as part of this slice; structural change only. The five other producers (`sales-events`, `inventory-events`, `manufacturing-events`, `purchasing-events`, `finance-events`) are queued in dev-todo.md and follow the same shape.

### CLAUDE.md update

New `### Events jars` subsection in project CLAUDE.md after the module-layout block — captures the producer-publishes-jar pattern, the consumer-side rule for `*Payload` records (1:1 → delete; genuinely-narrowed → keep with cross-ref), the dependency-direction rule (consumer depends on `<producer>-events`, never on `<producer>-service`), and the scope rule for VOs.

---

## 2026-05-10 — CGLIB-proxy + final-method audit + @Transactional test gap fill

After `ReorderPolicyChangedSeamIT` surfaced the `AbstractInboxHandler.handles()` final-method bug, an audit of every `@Transactional` site found a second instance of the same bug class plus several test gaps.

### Bugs found

- **`AbstractInboxHandler.handles()` / `consumerName()` were final.** `@Transactional` on `handle()` triggers Spring to CGLIB-proxy the class; Objenesis bypasses the constructor; final methods can't be overridden so they run on the proxy with null fields → NPE → Spring Kafka routes the message to `<topic>.dlt` (silent unless DLT topics are watched). Caught by `ReorderPolicyChangedSeamIT`.
- **`SagaManager.drain()` was final.** Same bug class. All three concrete saga managers (`Jdbc{SalesOrderFulfilment,MakeToOrder,PurchaseToPay}SagaManager`) carry `@Transactional` on their apply methods, so the parent class's drain executed on a CGLIB proxy with null `tx`/`sagaPort`/`leaseTtl` → NPE on the first poll tick under `@Profile("kafka")`. Latent — caught by audit, no IT exercised the path before today.

Both fixed by dropping `final`. Behaviour preserved: subclasses don't override these methods anyway, and the `final` keyword was the only thing breaking CGLIB proxying.

### Regression guards

- **`CglibProxyContractTest`** (shared-infrastructure) — reflection-based assertion that neither `SagaManager` nor `AbstractInboxHandler` has any final instance methods. Cheap structural test; if a future change reintroduces `final`, the test fails at build time. Verified to fail on the bug by re-introducing `final` temporarily.
- **`SagaManagerProxyTest`** (shared-infrastructure) — positive Spring-context test that boots a minimal `@Configuration` with `@EnableTransactionManagement`, registers a `TestSagaManager` subclass with a sibling `@Transactional` apply method (mirroring production saga managers' shape), asserts `AopUtils.isCglibProxy(bean)`, and calls `drain(...)` through the proxy to confirm no NPE. Verified to fail on the bug by re-introducing `final` temporarily — the test reproduced both Spring's `WARN: Public final method [...] cannot get proxied via CGLIB` log line and the runtime NPE on `this.tx.execute(...)`.

### `@Transactional` audit findings — other dimensions all clean

- No `final class` carries `@Transactional` (domain aggregates are correctly final and aren't Spring beans).
- No `@Transactional` on private/protected methods (Spring only proxies public).
- The single self-invocation of an `@Transactional` method (`JournalEntryService.reverseBySourceDocument` calling `reverseEntry`) is by design — both default to `REQUIRED` propagation, so the inner call correctly joins the outer transaction.
- Every `@Transactional` class carries a Spring stereotype.

### Test gap fill

- **`CustomerServiceTest`** (sales-service) — 11 tests across 6 nested groups covering all 6 `@Transactional` methods on `CustomerService`. Mockito repository, real `Customer` aggregate fixtures, captured pending events.
- **`ProductServiceTest`** (product-service) — 20 tests across 9 nested groups covering all 9 `@Transactional` methods on `ProductService` (createProduct, changeSalesPrice, changeStandardCost, setReorderPolicy, changeMakeVsBuy, setValuationClass, activateBom, setApprovedVendors, discontinue). Each method has a happy-path test, a no-op-suppression test where applicable, and a not-found / discontinued-state rejection test.
- **`OutboxPublisherTest`** (shared-infrastructure) — 4 tests covering the drain happy path, empty batch, partial failure with siblings still draining, and `source-service` header stamping per kit.

### CLAUDE.md update

User-level CLAUDE.md grew a sub-section under the existing "`final` + `@Transactional`" gotcha explaining that the same trap bites *sibling* final methods on a CGLIB-proxied class — the proxy creation is class-level, so any final method runs on the null-field proxy. The recognition cue is the `WARN o.s.aop.framework.CglibAopProxy: Unable to proxy interface-implementing method [public final ...]` log at startup; never ignore that warning.

### Reactor

13 modules green, ~470 tests total, ~28s wall clock. Harness tests still 7. New tests: 11 (CustomerService) + 20 (ProductService) + 4 (OutboxPublisher) + 2 (CglibProxyContract) + 3 (SagaManagerProxy) = 40 new.

---

## 2026-05-10 — §2.5.1 Phase D Slices A–G: full saga harness completed

Shipped all 7 remaining slices of §2.5.1 in one push. The harness now exercises every saga lifecycle end-to-end through real workers + the synchronous bus, with no Postgres / Kafka / Spring context.

### Production refactors (necessary precursors)

- **`SalesOrderLineSnapshotPort` extracted.** Sales' fulfilment saga worker previously read `sales.sales_order_line` via raw `JdbcTemplate`. Refactored to a port in `application/saga/` (`SalesOrderLineSnapshotPort`) + JDBC impl in `infrastructure/saga/` (`JdbcSalesOrderLineSnapshotPort`). The harness uses an in-memory variant (`InMemorySalesOrderLineSnapshotPort`) that projects off the in-memory aggregate store. The worker shell's constructor changed from `(manager, jdbc, outbox, json)` to `(manager, lineSnapshots, outbox, json)`.
- **`MakeToOrderSagaWorker` switched from `JdbcTemplate` to `OutboxPort`.** The shell previously wrote `RawMaterialReservationRequested` events to the outbox via raw `JdbcTemplate.update` calls. Switched to `OutboxPort.appendPending(...)`, matching the sales worker's pattern + the rest of the application layer's "no JDBC outside infrastructure/persistence" rule.

Both refactors are pure renames-of-collaborator on the production side; tests in sales-service / manufacturing-service still pass.

### Slice A — `ManufacturingTestKit` + sales/M2O saga-worker driving

- **Real `claimDue`** on `InMemorySalesOrderFulfilmentSagaPort` + `InMemoryMakeToOrderSagaPort` + (in Slice C) `InMemoryPurchaseToPaySagaPort`. Each filters by `activeStates`, skips rows whose `nextRetryAt` is in the future or whose lease hasn't expired, sorts by `nextRetryAt`, takes `batchSize`, and stamps `(leaseOwner, leaseExpiresAt)` on the claimed rows.
- **`NoopPlatformTransactionManager`** in `inmemory/`. Returns a `SimpleTransactionStatus(true)` from `getTransaction` and no-ops on `commit`/`rollback`. Lets `TransactionTemplate` drive harness saga workers without a real `DataSourceTransactionManager`. Replaces the `Mockito.mock(PlatformTransactionManager.class)` shortcut from the foundation slice (which only worked because `drain()` wasn't being exercised).
- **9 manufacturing in-memory adapters** under `inmemory/manufacturing/`:
  - `InMemoryMakeToOrderSagaPort`, `InMemoryBomLookup`, `InMemoryRoutingRepository`, `InMemoryProductReplenishmentProjection`, `InMemoryMakeToOrderShortageRecoveryQueryPort` (joins saga port + WO repository), `InMemoryProductActiveBomProjection`, `InMemoryProductApprovedVendorProjection`, `InMemoryProductMaterialsCostProjection`, `InMemoryBomEditRepository`, `InMemoryBomCycleDetector`.
- **2 inventory-side adapters** for sub-assembly: `InMemoryWipBalanceWriter` (tracks per-(warehouse, product) WIP + product totals; underflow throws like the prod CHECK), `InMemoryStockMovementWriter` (append-only audit log; tests inspect via `all()`).
- **`ManufacturingTestKit`** wires `JdbcMakeToOrderSagaManager`, `WorkOrderReleaseService`, `WorkOrderOperationService`, `WorkOrderCancellationService`, `WorkOrderPrioritisationService`, `MaterialsCostRollupService`, `BomEditService`, plus all 9 manufacturing inbox handlers. Exposes `advanceSagaWorker()` helper that runs `sagaWorker.drainOnce(workerId)` (the worker shells gained a `drainOnce(String)` helper that delegates to `manager.drain(BATCH, workerId, this::advance)`).
- **`InventoryTestKit`** extended to register `WorkOrderManufacturingCompletedHandler`, `SubAssembliesConsumedHandler`, and `RawMaterialReservationRequestedHandler`. Adds `wipBalances` and `stockMovements` fields for assertions.
- **`SalesTestKit`** extended with `lineSnapshots` (in-memory) + `sagaWorker` + `advanceSagaWorker()` helper.
- **`OrderToCashFirstLegTest`** refactored to use `sales.advanceSagaWorker()` rather than manually injecting `StockReservationRequested`. Saga authentically progresses `started → stock_reservation_requested → stock_reserved` through real worker driving.

### Slice B — `FinanceTestKit`

Finance has no sagas of its own (just inbox-driven projections + journal posting), so the kit is smaller. 4 in-memory repositories (`InMemoryCustomerInvoiceRepository`, `InMemorySupplierInvoiceRepository`, `InMemoryPaymentRepository`, `InMemoryJournalEntryRepository`), 4 in-memory projections / lookups (`InMemoryProductValuationClassProjection`, `InMemoryProductStandardCostProjection`, `InMemoryPoLineFactsProjection`, `InMemoryGlAccountLookup` — subclassing the production class with a seeded chart of accounts since `GlAccountLookup` isn't an interface). Wires `CustomerInvoiceService`, `SupplierInvoiceService`, `PaymentService`, real `JournalEntryService` (so GL postings are exercised end-to-end). Registers the 6 finance handlers.

The `maintain_allocation_totals` DB trigger isn't modelled in memory; tests that exercise the partial-payment path call `recordAllocation(...)` on the in-memory repos as the stand-in. The harness doesn't enforce `enforce_journal_balance` either; tests can still assert balance by inspecting `journalEntries.all()`.

### Slice C — `PurchasingTestKit` + P2P saga-worker driving

3 aggregate repositories, 1 saga port, 1 supplier repository, 1 approved-vendor query port, 1 supplier-product-price lookup + repository, 2 PO read-side projections (receipt + payment). Wires `PurchaseRequisitionService`, `PurchaseOrderService`, `JdbcPurchaseToPaySagaManager`, `SupplierProductPriceService`, `PurchaseToPaySagaWorker`. Registers the 5 purchasing handlers. `advanceSagaWorker()` calls the worker shell's `poll()` directly (the production scheduling annotation is inert in tests).

### Slice D — `OrderToCashHappyPathTest`

Walks `placeOrder → stock_reserved → goods_shipped → invoice_created → completed` through SalesTestKit + InventoryTestKit + FinanceTestKit. The make-to-order leg + ShipmentPosted + CustomerPaymentReceived are injected directly (rather than driving them through a fully-wired `ShipmentService` / `PaymentService` direct-ship happy-path) to keep the slice focused on saga state progression. Real GL postings fire: shipment-cost (Dr 5000 Cr 1200), customer-invoice (Dr 1100 Cr 4000), customer-payment (Dr 1000 Cr 1100).

### Slice E — `MakeToOrderShortagePathTest`

Walks `started → work_order_created → raw_material_reservation_requested → raw_material_shortage → (goods receipt) → work_order_created → raw_material_reservation_requested → raw_materials_reserved` through the manufacturing kit's real saga worker + inventory's reservation handler. Validates the production retry path: `StockReservationService.reserveForWorkOrder` cancels the prior failed reservation before re-reserving (the schema's UNIQUE on `stock_reservation_header.work_order_id` requires this).

### Slice F — `PurchaseToPayHappyPathTest`

Walks `started → purchase_order_approved → waiting_for_goods → goods_received → supplier_invoice_approved → completed` through PurchasingTestKit + FinanceTestKit + InventoryTestKit. Manual PR auto-converts to PO at draft; buyer approves via `purchaseOrderService.approve(...)`; goods receipt injected; supplier invoice recorded with 3-way match passing; payment recorded → `completed`. Real GL postings fire: goods-receipt (Dr 1200 Cr 1300), supplier-invoice (Dr 1300 Cr 2100), supplier-payment (Dr 2100 Cr 1000).

### Slice G — `SubAssemblyRecursionTest`

Two-level BoM: `FG → SubA (sub_assembly) + Wood (raw)` and `SubA → Steel (raw)`. Validates: WorkOrderReleaseService recurses to spawn a child WO + child saga at `work_order_created`; both WOs reserve raw materials independently; child completion bumps WIP (parentWorkOrderId non-null); parent's `onChildCompleted(siblingsAllDone=true)` cascade gates parent on its own ops being done; parent completion emits `SubAssembliesConsumed`; inventory's `SubAssembliesConsumedHandler` decrements WIP back to zero.

### Reactor totals

13 modules, 7 harness tests across `o2c/` (3), `projection/` (1), `m2o/` (2), `p2p/` (1). Whole reactor green: `mvn test` ~22s.

### Patterns that emerged

- **Real saga workers > event injection.** Where the production code path can be driven authentically (sales worker, M2O worker, P2P worker), the test calls `kit.advanceSagaWorker()` and asserts on saga state. Where wiring would be too costly (full ShipmentService / PaymentService / multi-level inventory write paths), tests inject events directly into the relevant kit's outbox. The bus drains them like any other event; handlers with state guards (`if (!saga.state().equals(EXPECTED)) return`) make the injected-vs-driven distinction invisible to assertions.
- **`@Transactional` is inert in the harness.** No Spring context = no `@Transactional` interception. The `NoopPlatformTransactionManager` keeps the saga manager's `TransactionTemplate.execute(...)` working without a real txn. Tests that depend on rollback semantics (none today) would need a different harness shape.
- **Inventory's WIP decrement underflows are caught client-side.** The in-memory `WipBalanceWriter.decrement` throws on underflow, mirroring the prod CHECK. Slice G's assertion `wipBalances.totalFor(subA) ≈ 0` after consume relies on this matching production exactly.
- **GlAccountLookup subclassing.** `GlAccountLookup` is a `@Repository` class (not an interface) wrapping `JdbcTemplate`. The harness adapter subclasses it, passes `null` to super, and overrides `byCode` with a seeded map. Future cleanup: extract an interface in `application/` and migrate.

### Smoke

`mvn test` whole reactor — 13 modules green, ~430 tests including the 7 harness tests; ~22s wall clock.

---

## 2026-05-10 — §2.5.1 Phase D second push: sales handlers wired + cancel-compensation + setPriority cascade

Building on the foundation, this push shipped two new harness tests + completed sales-side handler wiring:

### What shipped

- **`SalesTestKit` extended.** All 9 sales inbox handlers now registered, with real `SalesOrderShippingService` (constructed against the in-memory `SalesOrderRepository`) and real `SalesOrderCompensationEmitter` collaborators. Adds `SalesTestKit.cancel(salesOrderHeaderId, reason)` for the cancel flow.
- **`InventoryTestKit` extended.** `SalesOrderCancellationRequestedHandler` and `WorkOrderCancelledHandler` registered — both delegate to the existing in-memory `StockReservationService`.
- **In-memory adapters added:**
  - `inmemory/manufacturing/InMemoryWorkOrderRepository` — full `WorkOrderRepository` impl with `seed(workOrder)` for direct test setup, draining `pullPendingEvents` on save like the Jdbc adapter, plus working `findCompletedChildren` / `countUnfinishedChildren[Excluding]` / `findActiveIdsForSalesOrder` for the sub-assembly + cancel paths to use later.
  - `inmemory/reporting/InMemoryProductionPlanningProjection` — full `ProductionPlanningProjection` impl with `priorityOf(workOrderId)` / `statusOf(workOrderId)` accessors for assertions.
- **`CancelCompensationTest`** (`o2c/`) — places + cancels an order, drains the bus, asserts saga walks `compensating → (inventory ack) → compensating → (manufacturing ack manually injected) → compensated` and `sales.SalesOrderCompensated` is emitted only after BOTH acks land. Subsumes §2.6 cancel-order smoke test. Manufacturing's ack is manually injected since `ManufacturingTestKit` is a future slice — the assertion under test is the dual-ack collection in `SalesOrderFulfilmentSagaManager`, not manufacturing's cancel flow.
- **`SetPriorityCascadeTest`** (`projection/`) — inlined harness setup (no kit needed for a one-off cascade): `WorkOrderPrioritisationService` writes `manufacturing.WorkOrderPriorityChanged` to outbox; `BoardWorkOrderPriorityChangedHandler` (from reporting) consumes it; `InMemoryProductionPlanningProjection.recordPriorityChanged` updates the in-memory row; assertion confirms `priorityOf(workOrderId) = "urgent"`. Subsumes §2.6 setPriority smoke test.

### Reactor totals

12 modules, 3 harness tests now (was 1), whole reactor green. ~430 tests total.

### Still open (recorded in dev-todo.md §2.5.1)

The big chunks: full `ManufacturingTestKit` + `FinanceTestKit` + `PurchasingTestKit` (each is a substantial slice — many in-memory ports, the full saga manager + worker wiring, the inbox handlers), saga-worker driving (refactor or mock JdbcTemplate-using workers), and the four heavy E2E tests (O2C happy path full, M2O shortage, P2P, sub-assembly recursion). Each remaining E2E test is one focused slice on top of the relevant kit.

### Smoke

`mvn test` whole reactor — 12 modules green; 3 harness tests pass.

---

## 2026-05-10 — §2.5.1 Phase D foundation: in-memory end-to-end saga harness (proof of pattern)

§2.5.1 first push — the foundation + one E2E test that proves the pattern works. Future saga lifecycles plug into the same harness without further infrastructure work.

### What shipped

- **New Maven module `test-harness/`** at the project root, depending on shared-kernel, shared-infrastructure, sales-service, inventory-service. JUnit + AssertJ + Mockito on the test classpath. No production code changes anywhere — the harness is purely additive.

- **In-memory adapters** in `test-harness/src/test/java/com/northwood/testharness/inmemory/`:
  - `InMemoryOutboxPort` / `InMemoryInboxPort` — generic, one instance per service.
  - `SynchronousBus` — wraps registered `InMemoryOutboxPort`s + `InboxEnvelopeHandler`s; `drain()` repeatedly cycles every outbox and dispatches matching envelopes until no pending row remains. Same Jackson 3 `ObjectMapper` as production gives free wire-shape regression coverage.
  - `inmemory/sales/` — `InMemorySalesOrderRepository` (drains `pullPendingEvents()` to outbox just like the Jdbc adapter does), `InMemorySalesOrderFulfilmentSagaPort`, `InMemoryCustomerLookup`, `InMemoryProductPricingLookup`, `InMemorySalesOrderHeaderStatusProjection`.
  - `inmemory/inventory/` — `InMemoryStockReservationRepository`, `InMemoryStockBalances` (combined `StockBalanceWriter` + `StockBalanceLookup` over one `(warehouse, product)` map), `InMemoryWarehouseLookup`.

- **Per-service test kits** in `kits/`:
  - `SalesTestKit` — wires `SalesOrderService`, the real `JdbcSalesOrderFulfilmentSagaManager` (with a `mock(PlatformTransactionManager)` since `drain` isn't exercised), and the inbox handlers (`StockReservedHandler`, `WorkOrderCreatedHandler`, `WorkOrderManufacturingCompletedHandler`, `ManufacturingDispatchedHandler`). Cancellation/invoice/payment handlers omitted — they need extra collaborators that aren't in scope yet.
  - `InventoryTestKit` — wires `StockReservationService` + `StockReservationRequestedHandler`. Seeds the `MAIN` warehouse and exposes `seedStock(productId, qty)` for test setup.

- **`OrderToCashFirstLegTest`** in `o2c/` — the proof-of-pattern E2E test:
  1. `placeOrder` (sales) — aggregate emits `SalesOrderPlaced`; saga inserted at `started`.
  2. Manually inject `sales.StockReservationRequested` into sales' outbox to stand in for the saga worker (worker isn't yet harnessed; see follow-up below).
  3. `bus.drain()` — `StockReservationRequested` delivered to inventory; inventory reserves, emits `StockReserved`; sales' handler advances saga to `stock_reserved` and projects header status to `in_fulfilment`.
  4. Assertions: saga state = `STOCK_RESERVED`, header status = `IN_FULFILMENT`, inventory outbox contains `inventory.StockReserved`, no pending rows remain.

### Pattern that emerged

- **One `InMemoryOutboxPort` per service** preserves the per-schema isolation invariant. Cross-service pollution is structurally impossible — the bus only reads from registered outboxes and dispatches to registered handlers, no shared state.
- **Test kits compose, not inherit.** Each kit owns its outbox/inbox/repositories and registers them with the shared bus on construction. Multiple kits attach to one bus; the bus walks all registered outboxes per `drain()` cycle.
- **Repository in-memory impls drain `pullPendingEvents()` exactly like the Jdbc adapter.** This catches any aggregate that accidentally fails to add an event to `pendingEvents` (the bug stays inside the aggregate; the harness exposes it the same way production would).
- **Real saga manager + mock `PlatformTransactionManager`.** Since `drain()` isn't called in the first leg, the mock is never invoked. When the worker harness lands, replace the mock with a real no-op manager that runs the callback.

### Reactor totals after §2.5.1 foundation

11 modules + new `test-harness` = 12 modules. New module ships 1 test (the E2E proof). Whole reactor green: ~430 tests including the new harness test.

### Follow-ups (open, listed in §2.5.1 of dev-todo.md)

- **Saga-worker driving.** The first leg fakes the `started → stock_reservation_requested` transition by manually injecting the request envelope. To exercise it authentically, build an `InMemorySalesOrderFulfilmentSagaPort.claimDue` that returns due rows + wire the `SalesOrderFulfilmentSagaWorker.advance` callback in `SalesTestKit`. Same pattern applies to the make-to-order worker.
- **Remaining O2C legs.** Manufacturing test kit (with `MakeToOrderSagaManager` + `WorkOrderRepository`), finance test kit (with `CustomerInvoiceService` + `PaymentService`), then the full happy-path test that walks `placeOrder → completed` end-to-end.
- **Handlers omitted from `SalesTestKit`.** `ShipmentPostedHandler` (needs `SalesOrderShippingService`), `InventoryCancellationAppliedHandler` / `ManufacturingCancellationAppliedHandler` (need `SalesOrderCompensationEmitter`), `CustomerInvoiceCreatedHandler` / `CustomerPaymentReceivedHandler` (need finance test-kit's events). Add these as their respective legs land.

### Smoke

`mvn test` — whole reactor green; the new `OrderToCashFirstLegTest` reports the expected log cascade through `StockReservationService` → `StockReservationRequestedHandler` → `JdbcSalesOrderFulfilmentSagaManager` → `StockReservedHandler`.

---

## 2026-05-10 — §2.11: Aggregate status values pushed into `public static final String` constants

Same treatment that §2.7 applied to saga states, now applied to every non-saga `status` field across the domain. Motivation surfaced in conversation: `"paid".equals(payload.invoiceStatusAfter())` and a long tail of similar literals across handlers, repositories, projections, and aggregate internals — all comparing against string statuses with no compile-time typo protection and no single source of truth.

### Constants added to 13 aggregates

| Service | Aggregate | Constants |
|---|---|---|
| sales | `SalesOrder` | SUBMITTED, IN_FULFILMENT, READY_TO_SHIP, SHIPPED, COMPLETED, CANCELLED, REJECTED |
| manufacturing | `WorkOrder` | RELEASED, PLANNED, IN_PROGRESS, COMPLETED, CLOSED, CANCELLED + MATERIAL_RESERVATION_PENDING / MATERIAL_RESERVED / MATERIAL_PARTIALLY_RESERVED / MATERIAL_SHORTAGE |
| manufacturing | `WorkOrderOperation` | PLANNED, IN_PROGRESS, COMPLETED, SKIPPED |
| purchasing | `PurchaseOrder` | DRAFT, SENT, PARTIALLY_RECEIVED, RECEIVED, PAID |
| purchasing | `PurchaseRequisition` | PENDING_APPROVAL, APPROVED, CONVERTED, REJECTED |
| purchasing | `Supplier` | ACTIVE, INACTIVE |
| finance | `SupplierInvoice` | APPROVED, THREE_WAY_MATCH_FAILED, PARTIALLY_PAID, PAID, CANCELLED + MATCH_MATCHED / MATCH_VARIANCE / MATCH_FAILED |
| finance | `CustomerInvoice` | POSTED, PARTIALLY_PAID, PAID |
| finance | `Payment` | POSTED |
| finance | `JournalEntry` | POSTED |
| inventory | `StockReservation` | RESERVED, PARTIALLY_RESERVED, FAILED |
| inventory | `Shipment` | POSTED |
| inventory | `GoodsReceipt` | POSTED |

Call-site replacements applied to ~25 sites: aggregate internals (factory `this.status = X`, transition guards, `.equals()` checks against `status`/`materialStatus`/`matchStatus`), application services (`PaymentService` invoice-status guards, `JournalEntryService` reversal precondition, `PurchaseOrderService` requisition precondition, `BomEditService` activation/edit guards), JDBC repositories (status-driven `posted_at` timestamp logic across Payment/JournalEntry/CustomerInvoice/Shipment/GoodsReceipt), and same-service handlers (e.g. `SalesOrder.IN_FULFILMENT` for status projection writes).

### Cross-service consumers — local constants

Each service's pom only depends on `shared-kernel` + `shared-infrastructure`. Importing `com.northwood.finance.domain.SupplierInvoice` from purchasing-service or sales-service breaks the build (verified — broke with `package com.northwood.finance.domain does not exist`). Pattern adopted for cross-service event-payload consumers: declare a `private static final String` on the consuming class itself, comment-linked to the producer aggregate's wire format. Sites that ship with this pattern:

- `sales.CustomerPaymentReceivedHandler.INVOICE_STATUS_PAID` — matches `finance.CustomerInvoice.PAID` wire value carried on `finance.CustomerPaymentReceived` payload.
- `purchasing.SupplierPaymentMadeHandler.INVOICE_STATUS_PAID` — matches `finance.SupplierInvoice.PAID` wire value on `finance.SupplierPaymentMade` payload.
- `reporting.JdbcSalesOrder360Projection.INVOICE_STATUS_PAID` / `INVOICE_STATUS_PARTIALLY_PAID` — same.
- `reporting.JdbcPurchaseOrderTrackingProjection.INVOICE_STATUS_PAID` — same.
- `sales.JdbcSalesOrderFulfilmentSagaManager.RESERVATION_PARTIALLY_RESERVED` / `RESERVATION_FAILED` — match `inventory.StockReservation.PARTIALLY_RESERVED` / `FAILED` carried on `inventory.StockReserved` payload.
- `manufacturing.JdbcMakeToOrderSagaManager.RESERVATION_RESERVED` — match `inventory.StockReservation.RESERVED` on `inventory.RawMaterialsReserved`.
- `reporting.JdbcProductionPlanningProjection.MATERIAL_STATUS_RESERVED` — material reservation status from manufacturing payloads.

The duplication is small (1–3 strings per cross-service consumer) and intentional — the producer's aggregate constant is the source of truth; consumers in other services name and Javadoc-link the local copy back to it. Drift risk is low because the wire format is locked by the producer's CHECK + tests, and divergence would surface immediately as a saga that won't advance.

### Distinguishing same-domain strings that are NOT saga/aggregate statuses

The sweep was deliberately narrow — strings that look like statuses but represent a different concept were left as literals (or moved to local constants for the consumer that owns the comparison):

- `BomEditService` — `BOM_STATUS_DRAFT` / `BOM_STATUS_ACTIVE` declared locally on the service. There's no `Bom` aggregate today (only `BomEditRepository` + `BomLookup` ports), so the constants live where they're used.
- `SalesOrderService.java:98` `"active".equals(customer.status())` — `customer` here is `CustomerSummary` (a read-side projection), not the `Customer` aggregate (which exposes a `Status` enum). The literal matches the DB-column wire format from the projection; introducing a constant would require either a third class or pulling the enum into the comparison. Left for a future slice if `CustomerSummary` grows other status branches.

### Reactor totals

Whole reactor green: 11 modules, ~410 tests pass. Per-service breakdown unchanged from §2.10 (sales 83, manufacturing 97, purchasing 59, finance 96, inventory 54, reporting 1, BFFs).

### Pattern reinforced

Combined with §2.7 (saga states), the codebase now has a uniform rule: **every wire-format string comparison or assignment goes through a `public static final String` constant on the owning aggregate (or a local constant on the consumer when the producer is in another module).** Future code review can grep for `"\w+"\.equals\(\w+\.status\(\)\)` and treat any match as a code-review fail — same shape as the saga-state-literal hunt.

---

## 2026-05-10 — §2.10: `AbstractInboxHandler<P>` lifted to shared-infrastructure

Reporting service had been carrying `AbstractProjectionHandler<P>` (a base that captured the 5-step inbox-handler shape: handles-check → dedupe → deserialise → apply → recordProcessed). After §2.5 Phase B test coverage made the duplication impossible to ignore — every handler in every service was repeating the same ~30 lines of boilerplate — this slice swept all 53 non-reporting handlers, confirmed they fit the same shape (0 hard blockers), and lifted the base to `shared-infrastructure/src/main/java/com/northwood/shared/infrastructure/messaging/AbstractInboxHandler.java`.

### What shipped

- **New base:** `AbstractInboxHandler<P>` in `shared-infrastructure`. Generic on payload class. Holds `inbox` + `payloadType` privately, exposes `protected final Logger log = LoggerFactory.getLogger(getClass())` (subclasses use it directly) + `protected final ObjectMapper json` (some apply bodies need it for nested serde, e.g. `ManufacturingRequestedHandler` writes line saga-data, `RawMaterialsReservedHandler` writes shortage outbox payloads). `handle(EventEnvelope)` is `@Transactional` and **not final** — comment in source explains the CGLIB pitfall.
- **Reporting:** `AbstractProjectionHandler` deleted; 36 handlers (17 top-level + 19 sub-package across `inbox/atp/`, `inbox/dashboard/`, `inbox/shortage/`) switched to `extends AbstractInboxHandler<P>` via PowerShell rewrite.
- **Five service migrations:** finance (6), inventory (7), manufacturing (9 — including the three D8 emit-outbox handlers `ManufacturingRequestedHandler`, `RawMaterialsReservedHandler`, `GoodsReceivedHandler` which all preserved their custom apply bodies), purchasing (5 — including `RawMaterialShortageDetectedHandler` which had a D6 conditional `recordProcessed` that the base cleanly subsumes via an `if (...) return` guard in `apply()`), sales (10).
- **Per-handler shrinkage:** ~70 lines → ~35 lines. Removed: `private final InboxPort inbox`, `private final ObjectMapper json`, `private static final Logger log`, the entire `handle(EventEnvelope)` body, all redundant imports (`InboxRow`, `JacksonException`, `Logger`/`LoggerFactory`, `Transactional`, `UUID` where only used for `InboxRow.processed`). Constructor signatures unchanged (still take `InboxPort`, plus deps, plus `ObjectMapper`) — tests didn't need touching for constructor changes.
- **Trailing log lines moved into `apply()`** for the ~7 handlers that emitted "[CONSUMER_NAME] processed X for Y=Z" after `recordProcessed`. Same observable behaviour (still inside the same `@Transactional` boundary).

### Pre-flight sweep

Before lifting, ran an Explore-agent sweep across all 53 non-reporting handlers to flag any deviations from the canonical 5-step shape. Result: **0 hard blockers (D1 missing-`@Transactional`, D2 final-handle, D3 multi-event-type, D4 skip-dedupe, D5 no-payload-class — none).** The flagged D6/D7/D8 deviations all turned out to be either trivial (logging position) or actively improved by the base (`RawMaterialShortageDetectedHandler`'s conditional `recordProcessed` becomes an unconditional one with an `if (empty) return` guard at the top of `apply()`). The sweep was the right call before committing to the migration.

### Reactor totals (unchanged)

product · sales 83 · manufacturing 97 · purchasing 59 · finance 96 · inventory 54 · reporting 1 · BFFs. Total ~410. Whole-reactor `mvn test` green throughout each per-service migration step + final cross-reactor run.

### Test placement fix shipped earlier in the same session

Independently noticed that the three `Jdbc<Flow>SagaManagerTest` files (sales, manufacturing, purchasing) lived under `application.saga.*` despite testing classes under `infrastructure.saga.*`. Moved them to mirror the production package; nothing else changed (constructor sigs, assertions all unchanged). Surfaced as a code-review observation about §2.9 Slice A's placement choice, applied retroactively to A/B/C.

### Follow-ups

- **`AbstractSagaInboxHandler<P>` is now superseded** — the §2.9 "out of scope" entry deferred this to "after the three managers ship". The broader `AbstractInboxHandler<P>` shipped here covers every handler, including the saga-driving ones.
- **No new test infrastructure needed.** The §2.5 Phase A/B test coverage validated the migration: every test that exercised a handler's dedupe / deserialise / collaborator-call / recordProcessed shape continues to pass against the base. Subclass `apply(...)` is the same code as the post-deserialise body before; behaviour is identical.

---

## 2026-05-10 — §2.5 Phase B: inbox handler test coverage

10 handler test files added across 3 services. 39 new tests; brings the application-layer unit-test total from ~190 (post-Phase A) to ~230. Tests are shell-smoke shape: dedupe + payload deserialise + collaborator side-effect verification + recordProcessed; substantive business-logic assertions remain in the corresponding manager / service tests.

### Per-service test additions

- **purchasing** (3 files, 11 tests): `GoodsReceivedHandlerTest` (receipt projection + manager.applyGoodsReceived), `RawMaterialShortageDetectedHandlerTest` (createForWorkOrderShortage with one line per shortage), `SupplierInvoiceApprovedHandlerTest` (manager.applySupplierInvoiceApproved).
- **finance** (3 files, 12 tests): `GoodsReceivedHandlerTest` (per-line projection + aggregated journal post), `PurchaseOrderCreatedHandlerTest` (per-line projection seed), `SalesOrderShippedHandlerTest` (auto-invoice delegation).
- **inventory** (4 files, 16 tests): `WorkOrderManufacturingCompletedHandlerTest` (top-level FG vs sub-assembly WIP branch), `SubAssembliesConsumedHandlerTest` (per-item WIP decrement), `SalesOrderCancellationRequestedHandlerTest` (releaseForSalesOrder), `WorkOrderCancelledHandlerTest` (releaseForWorkOrder).

### Reactor totals

sales 83, manufacturing 97, purchasing 59 (+19), finance 96 (+12), inventory 54 (+14). Application-layer ~230.

### Patterns reinforced

- Idempotency: `when(inbox.alreadyProcessed(...)).thenReturn(true)` + `verifyNoInteractions(...)` on every collaborator + `verify(inbox, never()).recordProcessed(any())`. Same shape across all 10 files.
- Wrong-event-type: confirm `handle()` is safe on any inbox event by ensuring it exits before `alreadyProcessed`.
- Branching side effects: one test per branch with `verifyNoInteractions(...)` on the OTHER collaborator (e.g. `verifyNoInteractions(stockBalances)` in the sub-assembly test catches accidental dual-bumps).
- Aggregated-per-line collaborator calls (e.g. finance's GoodsReceivedHandler): `ArgumentCaptor<List<X>>` to assert the aggregated list shape.

### What's NOT in Phase B

- **Reporting handlers** (~25 thin echoes): explicitly low-priority per the dev-todo entry. Skipped.
- **Sales 5 inbox handlers** post-§2.9 (`WorkOrderCreatedHandler`, `ShipmentPostedHandler`, `CustomerInvoiceCreatedHandler`, `InventoryCancellationAppliedHandler`, `ManufacturingCancellationAppliedHandler`) — became thin shells in Slice A; manager test covers their logic; coverage value of shell-smoke tests is marginal.
- **Inventory `RawMaterialReservationRequestedHandler`, `StockReservationRequestedHandler`** — they delegate to `StockReservationService` which is Phase A tested.

### Smoke

`mvn test` whole reactor — 11 modules green.

---

## 2026-05-10 — §2.7: Saga states as `public static final String` constants

Mechanical follow-up to §2.9 — replaces saga-state literals at every call site with constants declared on each saga aggregate. Now that §2.9 concentrated all saga-state work into one manager class per saga + the worker shell, the surface area was small (3 aggregates + 3 managers + 3 workers).

### What changed

- **Each saga aggregate** (`SalesOrderFulfilmentSaga`, `MakeToOrderSaga`, `PurchaseToPaySaga`) gained a block of `public static final String STATE_NAME = "state_name"` constants — one per entry in `ALL_STATES` plus `TERMINAL_STATES`-only states (e.g. `MANUAL_REVIEW_REQUIRED` on P2P). `ALL_STATES` and `TERMINAL_STATES` were rebuilt from the constants so the names exist in exactly one place per saga.
- **Factory methods** (`*.started(...)`, `*.attachedToWorkOrder(...)`) reference the constants instead of literals.
- **JDBC manager impls** static-import the constants from the aggregate (`import static ...SalesOrderFulfilmentSaga.*;` etc.) and reference them in:
  - `activeStates()` — `Set.of(STARTED, STOCK_RESERVED)` instead of literals.
  - `transitionTo(STATE_X, ...)` calls.
  - `STATE_X.equals(saga.state())` comparisons.
- **Worker shells** static-import the specific constants they need (`STARTED`, `STOCK_RESERVED`, etc.) for the switch-on-state advance dispatch + `transitionTo` calls. Java treats `public static final String = "literal"` as a compile-time constant variable, valid in `switch` case labels.

### What stayed as literals

- **`current_step` values** (e.g. `"wait_for_worker_pickup"`, `"wait_for_next_step"`, `"o2c_completed"`) — descriptive labels, not state-machine vertices. No invariant checker / state filter compares them, so a typo only shows up in log lines, not in transition logic.
- **DB CHECK constraints + Liquibase changesets + event payloads** — wire-format strings, unchanged. Constants are a Java-side ergonomic.
- **Inbox event payload fields** like `"reserved"` / `"partially_reserved"` / `"failed"` (reservation outcome status) — not saga states; they're the inbound event's status field. Some happen to share names (`"failed"`) but the meaning is different — kept as inline literals to avoid suggesting they're saga-state references.

### Smoke

`mvn test` whole reactor — 11 modules green. No test changes needed (tests use literals against the saga's `state()` accessor; both literal and constant resolve to the same string at runtime).

### Patterns

- Static-import-all (`import static SagaClass.*`) for the manager impl which uses many constants — avoids boilerplate while keeping the constant origin explicit (the static import is one line near the other imports).
- Static-import-named (`import static SagaClass.STARTED;`) for the worker shell which only needs 2-4 constants — keeps the import block scannable.
- The aggregate's static fields are compile-time constants because the initialiser is a String literal — `case STARTED ->` works in a switch over `saga.state()`. If a future change makes the initialiser non-constant (e.g. `STARTED = SomeOther.X`), the switch would error; the constants are intentionally simple to preserve switch-case usability.

### Things noticed

- The change was genuinely mechanical (~50 small edits across 9 files). Caught no typos in production code (which is reassuring — the existing literals were correct), but future renames of state names get compile-checked now: change `STARTED` → `INITIATED` and every call site lights up red.
- M2O's `MakeToOrderSaga` has `MANUAL_REVIEW_REQUIRED` not in `ALL_STATES` — it's in `TERMINAL_STATES` only. Promoted to a constant anyway for consistency, even though no Java code references it (yet); it's the schema-CHECK-allowed manual-review terminal.

---

## 2026-05-10 — §2.9 Slice C: PurchaseToPaySagaManager — purchase-to-pay saga single-class refactor

Applied the §2.9 architectural pattern (CLAUDE.md "Saga manager class shape") to purchase-to-pay. Final saga in the §2.9 family — sales fulfilment + make-to-order + purchase-to-pay all now follow the same template.

### Manager surface

`purchasing.application.saga.PurchaseToPaySagaManager` (interface) + `purchasing.infrastructure.saga.JdbcPurchaseToPaySagaManager` (impl extending `SagaManager<PurchaseToPaySaga, PurchaseToPaySagaPort>`). Manager dependencies: `sagaPort` only — no `ObjectMapper` because P2P saga.data is always `"{}"` (no shortage map / saga-data state to serialise).

6 methods:
- `insertStarted(purchaseOrderHeaderId)` — saga insert from `PurchaseOrderService.convertFromRequisition`.
- `approve(purchaseOrderHeaderId)` — idempotent flip `started → purchase_order_approved`. Handles both the auto-approve path (called inline from `convertFromRequisition` when `autoApprove=true`) and the manual-REST path (`PurchaseOrderService.approve`). Already-approved sagas return their current state without re-transitioning.
- `drain(int, String, Consumer<PurchaseToPaySaga>)` — inherited from base.
- `applyGoodsReceived(poId, fullyReceived)` — transitions `waiting_for_goods → goods_received` only when `fullyReceived` is true. Returns the saga's new/unchanged state.
- `applySupplierInvoiceApproved(poId)` — transitions `goods_received → supplier_invoice_approved`; ignored otherwise.
- `applySupplierPaymentMade(poId, fullySettled)` — full settlement → `completed`; partial → `supplier_payment_made` (loops there for subsequent partials). Accepts `supplier_invoice_approved` or `supplier_payment_made` as receiving state.

### Side-effect redistribution

- **Worker shell `PurchaseToPaySagaWorker`** (~50 lines) — `@Scheduled poll() → manager.drain(BATCH_SIZE, workerId, this::advance)`. The single advance: `purchase_order_approved → waiting_for_goods` + park 1 day. No JdbcTemplate or OutboxPort needed (worker doesn't read or emit anything; the transition is pure state).
- **`PurchaseOrderService.convertFromRequisition`** — `manager.insertStarted(po.id().value())` + `if (autoApprove) manager.approve(po.id().value())`. Two-line replacement for the prior 6-line saga insert + transition + save block.
- **`PurchaseOrderService.approve`** — single-line `manager.approve(poId.value())` replaces the prior `findByPurchaseOrderId + ifPresent + transitionTo + save` block.
- **`GoodsReceivedHandler`** — calls `receiptProjection.recordReceipt(...)` (still — the projection write is the handler's concern), then `manager.applyGoodsReceived(poId, outcome.fullyReceived())`. No saga-state inspection in the handler.
- **`SupplierInvoiceApprovedHandler`** — single-line `manager.applySupplierInvoiceApproved(poId)`. No conditional or projection writes.
- **`SupplierPaymentMadeHandler`** — `manager.applySupplierPaymentMade(poId, fullySettled)` returns the new state, handler gates `paymentProjection.markFullyPaid` (state="completed") vs `addPartialPayment` (state="supplier_payment_made") on it. The "unrelated state" branch (e.g. saga in `waiting_for_goods`) is now signalled by the manager returning the unchanged state — the handler does no projection writes for that case.

### Test rebalance

- **New `JdbcPurchaseToPaySagaManagerTest`** — 16 tests across 4 nested classes covering every transition: `Lifecycle` (insertStarted, approve idempotent / no-saga), `ApplyGoodsReceived` (full / partial / no-saga-throws), `ApplySupplierInvoiceApproved` (advance / unrelated-unchanged), `ApplySupplierPaymentMade` (full / partial / from-supplier-payment-made-full / unrelated).
- **`SupplierPaymentMadeHandlerTest`** rewritten — mocks the manager + verifies the projection-write gating on the manager's returned state. ~5 tests, shell-smoke shape.
- **No new tests for `GoodsReceivedHandler` / `SupplierInvoiceApprovedHandler` / `PurchaseToPaySagaWorker`** — those existed previously without dedicated test files (covered by integration smoke + the handler-level shape they had pre-refactor was already minimal).

### Smoke

`mvn test` whole reactor — 11 modules green. `mvn -pl purchasing-service test` clean.

### Patterns reinforced from CLAUDE.md "Saga manager class shape"

Same pattern as Slice A and Slice B; minimal cost per saga once the shape was established:
- Manager dependencies = `sagaPort` only (P2P doesn't even need `ObjectMapper`).
- Apply methods take primitives, return new state.
- Worker shell is the thinnest of the three (no DB reads, no outbox emissions — just state transition).
- Handler shells gate side effects (projection writes) on the manager's returned state.
- Inheritable logger via `getClass()` on the abstract base; concrete subclass doesn't redeclare.

### §2.9 family complete

All three sagas now follow the "Saga manager class shape" rules. The §2.9 entry in dev-todo is marked complete; remaining §2.9 work (e.g. §2.7 saga-state-as-constants) is a separate slice.

Things noticed across all three slices:
- The pattern is robust: each subsequent slice was faster than the last (Slice A was the heaviest with 9 inbox handlers + a multi-step compensation flow + new emitter; Slice B medium with 2 handlers + WO orchestration; Slice C lightest with 3 handlers and no shared cross-handler emission).
- `ObjectMapper` injection on the manager is per-saga: needed when `saga.data` carries non-empty JSON (sales' shortage map, M2O's shortage map), unnecessary otherwise (P2P).
- Worker-driven side effects vary in weight: sales reads sales-order lines + emits two events; M2O reads work-order materials + emits one event; P2P reads nothing and emits nothing — just transitions state.

---

## 2026-05-10 — §2.9 Slice B: MakeToOrderSagaManager — make-to-order saga single-class refactor

Applied the §2.9 architectural pattern (captured in CLAUDE.md "Saga manager class shape") to make-to-order. Manager-only-saga-state, worker shell holds worker-driven side effects, inbox handlers hold inbox-driven side effects.

### Manager surface

`manufacturing.application.saga.MakeToOrderSagaManager` (interface) + `manufacturing.infrastructure.saga.JdbcMakeToOrderSagaManager` (impl extending `SagaManager<MakeToOrderSaga, MakeToOrderSagaPort>`). Manager dependencies: `sagaPort` (inherited) + `ObjectMapper`. That's it.

8 methods:
- `insertStarted(salesOrderHeaderId, salesOrderLineId, dataJson)` — top-level saga insert from `ManufacturingRequestedHandler`.
- `insertAttachedToWorkOrder(salesOrderHeaderId, salesOrderLineId, workOrderId, dataJson)` — sub-assembly child-saga insert from `WorkOrderReleaseService.release`.
- `drain(int, String, Consumer<MakeToOrderSaga>)` — inherited from base.
- `applyRawMaterialsReserved(workOrderId, status, shortageByProductId)` — returns new state ("raw_materials_reserved" | "raw_material_shortage" | unchanged). Stashes shortage map on saga.data when status indicates partial/failed.
- `unparkOrNarrowShortage(sagaId, receivedByProductId)` — decrements stashed shortage; un-parks (returns "work_order_created") when fully covered, narrows + saves (returns "raw_material_shortage") otherwise. Returns null when saga isn't in `raw_material_shortage` or the receipt didn't touch any of its products. Legacy fallback for sagas without a stash unparks unconditionally.
- `applyManufacturingCompleted(workOrderId)` — idempotent flip to `completed` for non-terminal sagas. Returns new/unchanged state, or null when no saga exists for the WO.
- `cancelForWorkOrder(workOrderId)` — idempotent flip to `compensated`. Same shape as `applyManufacturingCompleted`.

### Side-effect redistribution

- **Worker shell `MakeToOrderSagaWorker`** — gained `JdbcTemplate` + the worker-driven advance methods (`releaseWorkOrder`, `requestRawMaterialReservation`). `@Scheduled poll()` is `manager.drain(BATCH_SIZE, workerId, this::advance)`. The worker holds `JdbcTemplate` for raw outbox SQL (manufacturing service still uses raw `INSERT INTO outbox_message` rather than `OutboxPort`; left as-is — tangential cleanup).
- **`ManufacturingRequestedHandler`** — calls `manager.insertStarted` per accepted line. Still emits `ManufacturingDispatched` from the handler.
- **`WorkOrderReleaseService.release`** — calls `manager.insertAttachedToWorkOrder` per sub-assembly child WO during recursion.
- **`RawMaterialsReservedHandler`** — extracts the per-product shortage map from payload, calls `manager.applyRawMaterialsReserved`, and (when manager returns "raw_material_shortage") emits `manufacturing.RawMaterialShortageDetected` for purchasing.
- **`GoodsReceivedHandler`** — builds the per-product receipt map, fetches candidate sagas via `MakeToOrderShortageRecoveryQueryPort`, calls `manager.unparkOrNarrowShortage` per candidate, counts un-parked vs narrowed for the log line.
- **`WorkOrderOperationService.advanceSagaToCompleted`** — now a one-line wrapper around `manager.applyManufacturingCompleted(workOrderId)`. Could be inlined but kept as a private named method preserving the existing call site.
- **`WorkOrderCancellationService.cancelForSalesOrder`** — replaces the inline `sagas.findByWorkOrderId + transitionTo + save` block with `manager.cancelForWorkOrder(woId)` per WO.

### Test rebalance

- **New `JdbcMakeToOrderSagaManagerTest`** — 15 tests across 5 nested classes covering every transition: `Lifecycle` (insertStarted, insertAttachedToWorkOrder), `ApplyRawMaterialsReserved` (reserved / partial-stash / out-of-state), `UnparkOrNarrowShortage` (full cover / partial narrow / legacy / wrong-state-null), `ApplyManufacturingCompleted` (advance / terminal / no-saga), `CancelForWorkOrder` (flip / terminal / no-saga).
- **`WorkOrderOperationServiceTest`** rewritten — drops saga state assertions (manager is mocked, doesn't mutate the saga obj) and verifies `verify(sagaManager).applyManufacturingCompleted(woId)` instead. WorkOrder cascade-to-parent + SubAssembliesConsumed emission tests stay (they're orchestration the service still owns).
- **`WorkOrderCancellationServiceTest`** rewritten — mocks `MakeToOrderSagaManager` instead of `MakeToOrderSagaPort`; verifies `manager.cancelForWorkOrder(woId)` per WO + the ack outbox row.
- **`RawMaterialsReservedHandlerTest`** simplified — mocks the manager + asserts shortage extraction is passed in, plus the `RawMaterialShortageDetected` emission gating on manager return value.
- **`GoodsReceivedHandlerTest`** simplified — mocks the manager + recovery query port; verifies candidates are delegated to the manager via the receivedByProduct map. The substantive un-park / narrow / legacy logic moved into the manager test.
- **82 manufacturing-service tests** green (down from ~89 pre-refactor; the saga-state-detail tests consolidated into the new manager test).

### Smoke

`mvn test` whole reactor — 11 modules green. `mvn -pl manufacturing-service test` — 82 tests green.

### Patterns reinforced from CLAUDE.md "Saga manager class shape"

The pattern carried over verbatim from Slice A:
- Manager dependencies = `sagaPort` + `ObjectMapper` (nothing else).
- Apply methods take primitives, return new state.
- Worker shell holds worker-driven side-effect collaborators (JdbcTemplate + raw outbox).
- Handler shells hold inbox-driven side-effect collaborators (here: `WorkOrderRepository` + `OutboxPort` for shortage emission, `MakeToOrderShortageRecoveryQueryPort` for candidate finding).
- Inheritable logger via `getClass()` on the abstract base; concrete subclasses don't redeclare.

### Things noticed (not blockers)

- `MakeToOrderSagaWorker` holds raw `JdbcTemplate` to write to `manufacturing.outbox_message` — pre-existing pattern in this service, different from sales which uses `OutboxPort`. Left as-is to keep slice scope small; could be unified in a follow-up.
- The `decideUnpark` logic moved from `GoodsReceivedHandler` to `JdbcMakeToOrderSagaManager.decideUnpark` (private). Unchanged behaviour.
- `WorkOrderOperationService.advanceSagaToCompleted` is now a one-liner wrapping `manager.applyManufacturingCompleted`. Could be inlined; kept for readability of the calling code.

Slice C (`PurchaseToPaySagaManager`) is the natural next step.

---

## 2026-05-10 — §2.9 Slice A second-pass refactor: slim manager + generic port type

Two follow-up refinements to Slice A, shipped together. Both were prompted by the user noticing residual coupling.

### Pass 1: slim manager to saga-state-only

`JdbcSalesOrderFulfilmentSagaManager` now holds **only** `SalesOrderFulfilmentSagaPort` (inherited as `sagaPort`) + `ObjectMapper`. Removed: `SalesOrderRepository`, `SalesOrderHeaderStatusProjection`, `SalesOrderShippingService`, `OutboxPort`, `JdbcTemplate`. The manager's apply methods take saga-relevant primitives (UUID + small fields, never inbox payload types) and return the saga's new state (`String`); callers gate side effects on the return value.

**Side effects redistributed:**
- Worker shell `SalesOrderFulfilmentSagaWorker` gained `JdbcTemplate` + `OutboxPort` + `ObjectMapper` and the worker-driven advance methods (`requestStockReservation`, `requestManufacturing`). `@Scheduled poll()` calls `manager.drain(BATCH_SIZE, workerId, this::advance)`.
- Abstract `SagaManager<S>.drain` changed from `(int, String)` to `(int, String, Consumer<S> advanceFn)`. Dropped abstract `advance(S)`. `MakeToOrderSagaWorker` and `PurchaseToPaySagaWorker` updated to pass `this::advance` callbacks (no business-logic change in those services for Slice A — mechanical update only).
- `StockReservedHandler` ← `SalesOrderHeaderStatusProjection` (calls `markStatus(SO, "in_fulfilment")` after manager).
- `ManufacturingDispatchedHandler` ← `SalesOrderHeaderStatusProjection` (calls `markStatus(SO, "rejected")` if manager returns `"stock_reservation_failed"`).
- `ShipmentPostedHandler` ← `SalesOrderShippingService` (calls `recordShipped(...)` if manager returns `"goods_shipped"`).
- `CustomerPaymentReceivedHandler` ← `SalesOrderHeaderStatusProjection` (calls `markStatus(SO, "completed")` if manager returns `"completed"`).
- `InventoryCancellationAppliedHandler` + `ManufacturingCancellationAppliedHandler` ← new `SalesOrderCompensationEmitter` (calls `emitCompensated(SO)` if manager returns `"compensated"`).

**New service** `SalesOrderCompensationEmitter` in `sales.application` — single-method service holding `SalesOrderRepository` + `OutboxPort` + `ObjectMapper`; reads `SalesOrder.cancelledAt` and emits `SalesOrderCompensated`. Lives in `application/` (NOT `application/inbox/` — the inbox package is handler-only by the existing convention).

### Pass 2: generic port type on the abstract base

`SagaManager<S extends SagaInstance>` → `SagaManager<S extends SagaInstance, P extends SagaPort<S>>`. Now holds one `protected final P sagaPort` (no longer `private final SagaPort<S> port`); the concrete subclass parameterises `P` with its concrete saga port type, so saga-specific methods like `SalesOrderFulfilmentSagaPort.findBySalesOrderId` are reachable directly via the inherited field without redeclaring it on the subclass.

- `JdbcSalesOrderFulfilmentSagaManager` extends `SagaManager<SalesOrderFulfilmentSaga, SalesOrderFulfilmentSagaPort>` — dropped its duplicate `SalesOrderFulfilmentSagaPort sagas` field; references rewritten from `sagas.X` to `sagaPort.X` (16 call sites).
- `MakeToOrderSagaWorker` extends `SagaManager<MakeToOrderSaga, MakeToOrderSagaPort>` — same pattern, constructor parameter renamed.
- `PurchaseToPaySagaWorker` extends `SagaManager<PurchaseToPaySaga, PurchaseToPaySagaPort>` — same.

### Inheritable logger consolidated

While Pass 1 was in flight: `protected final Logger log = LoggerFactory.getLogger(getClass())` on `SagaManager` base. The three concrete subclasses dropped their `private static final Logger log = ...` declarations and the `org.slf4j.*` imports. Log lines from the base report under the concrete class name (e.g. `JdbcSalesOrderFulfilmentSagaManager`) automatically.

### Smoke

`mvn test` on the full reactor — 11 modules green. Sales-service: 83 tests (4 more than the prior baseline of 79). Manufacturing + purchasing: unchanged tests, still green. `SalesApplicationTests` (Spring DI smoke) verified the new wiring resolves correctly — confirming the new `SalesOrderCompensationEmitter` bean is autowired into both ack handlers and the slim manager constructor doesn't have unmet dependencies.

### Patterns captured to CLAUDE.md

Added a "Saga manager class shape" sub-section under "Saga / process manager" capturing the post-§2.9 architectural rules so Slices B/C inherit them:
- Manager dependencies (`sagaPort` + `ObjectMapper` only; never outbox/JDBC/projection/cross-aggregate ports).
- Apply method signatures (primitives in, `String` new-state out).
- Worker shell responsibilities (worker-driven advance + side effects).
- Inbox handler responsibilities (dedupe + manager call + side-effect gating + recordProcessed).
- Shared cross-handler emitter pattern (one-method service in `application/`, not `application/inbox/`).
- Generic abstract base (`SagaManager<S, P>` with `protected final P sagaPort` + inheritable logger).
- Test rebalance (manager tests = saga-state only; handler tests = side-effect verification).

### Things noticed / kept as known trade-offs

- **`SalesOrderService.SagaNotFoundException` ↔ `SalesOrderFulfilmentSagaManager.SagaNotFoundException`** — two-line translation in `cancel()` to preserve the existing controller `@ExceptionHandler` mapping. Tidy-up candidate for a later slice; not load-bearing.
- **JdbcTemplate stays in the Worker shell** (worker-driven sales-order-line reads). The architectural rule "no JdbcTemplate in `application/`" still holds — the worker shell is in `infrastructure/saga/`.

---

## 2026-05-09 — §2.9 Slice A polish: workerId moved to Worker shells

Tightening follow-up to Slice A. Question raised after Slice A landed: why does `SagaManager<S>` still hold a `workerId` field/accessor? After the rename from `SagaPollingWorker` → `SagaManager`, a "manager" shouldn't carry "worker" identity — that identity belongs to the polling driver (the Worker shell).

### What changed

- **`shared.infrastructure.saga.SagaManager<S>`**:
  - Removed `sagaName` constructor parameter.
  - Removed `workerId` field + the `workerId()` accessor.
  - Changed `drain(int)` → `drain(int batchSize, String workerId)`. Caller passes the id; the lease-claim SQL stamps it into `lease_owner` exactly as before.
  - Updated Javadoc: "identity is the worker's concern, not the manager's".
- **`sales.application.saga.SalesOrderFulfilmentSagaManager`** interface — `drain(int)` → `drain(int, String workerId)`.
- **Sales Worker shell** holds its own `workerId` field (initialised once at construction time as `"sales.fulfilment-worker@" + jvmRuntimeName`); passes to `manager.drain(BATCH_SIZE, workerId)`.
- **Manufacturing + Purchasing workers** (Slice-B/C not yet shipped — still concrete subclasses of `SagaManager<S>`) — same pattern: each holds its own `workerId` field and calls `drain(BATCH_SIZE, workerId)` from `poll()`. Their existing `workerId()` log refs swapped for the field.
- **`JdbcSalesOrderFulfilmentSagaManager`**: `super(...)` no longer takes the saga name; the per-saga log lines that used `workerId()` had the `[{}]` prefix dropped (the abstract base still logs `[<workerId>] claimed N saga(s)` once per drain, which is enough).

### Why

After the §2.9 Slice A rename, `workerId` inside a class named `SagaManager` was a residual: a "manager" doesn't have a "worker id" — that's a polling-driver concept. The polling driver IS the `*SagaWorker` shell. Identity built where it's used aligns naming with role; future non-polling drivers (admin-triggered drain, integration-test drain) bring their own id without inheriting a vestigial worker concept.

### Naming choice

Considered `pollerId` (class-name-neutral) vs `workerId` (matches the concrete class names). User preferred `workerId` for consistency with `*SagaWorker` — symmetry over abstraction. Adopted.

### Smoke

`mvn test` (full reactor) — all 11 modules green. Sales-service: 79 tests. Manufacturing-service: ~unchanged. Purchasing-service: 40 tests.

---

## 2026-05-09 — §2.9 Slice A: SalesOrderFulfilmentSagaManager — single-class state-machine truth

Single-class refactor consolidating sales-fulfilment saga logic into one `SagaManager` class per the agreed §2.9 design. Shipped end-to-end including production code, test rebalance, and diagram refresh. 79 sales-service tests green.

### What changed

- **Renamed** `shared.infrastructure.saga.SagaPollingWorker<S>` → `SagaManager<S>` (same shape: `drain(int)`, per-saga REQUIRES_NEW + reload-on-retry; only the role-name changed). Updated extends clause in all 3 worker classes (sales/manufacturing/purchasing) and Javadoc references in `SagaInstance` + `SagaPort`.
- **Added** `sales.application.saga.SalesOrderFulfilmentSagaManager` — interface with 12 orchestration verbs:
  - lifecycle: `insertStarted`, `requestCompensation`
  - worker: `drain(int)`
  - inbox-driven: `applyStockReserved`, `applyWorkOrderCreated`, `applyWorkOrderManufacturingCompleted`, `applyManufacturingDispatched`, `applyShipmentPosted`, `applyCustomerInvoiceCreated`, `applyCustomerPaymentReceived`, `applyInventoryCancellationApplied`, `applyManufacturingCancellationApplied`
- **Added** `sales.infrastructure.saga.JdbcSalesOrderFulfilmentSagaManager` — impl extending the renamed `SagaManager<S>`. Holds every transition the saga can take. Injects the existing `SalesOrderFulfilmentSagaPort` as a private collaborator (row-CRUD stays small + reusable; not merged in). Folds in the former `SagaDataIO.read` / `SagaDataIO.write` as private methods, and the former `SagaCompensationCompletionService.completeIfReady` as `completeCompensationIfReady` (private, called from both ack handlers' apply methods).
- **Deleted**:
  - `sales.application.SagaCompensationCompletionService` (logic folded into manager)
  - `sales.application.inbox.SagaDataIO` (logic folded into manager)
  - `SagaCompensationCompletionServiceTest` (coverage moved to manager test)
- **Worker shell** `SalesOrderFulfilmentSagaWorker` shrunk from ~230 lines (with 2 advance methods + `parseShortage` helper + `writeOutbox`) to ~25 lines: `@Scheduled poll() { manager.drain(BATCH_SIZE); }`.
- **Inbox handler shells** — all 9 shrunk to ~50 lines each: `@Component`, `EVENT_TYPE`, `CONSUMER_NAME`, dedupe + payload deserialise + `manager.applyXxx(payload)` + `recordProcessed`. No business logic.
- **`SalesOrderService` swap** — replaced direct `SalesOrderFulfilmentSagaPort` injection with `SalesOrderFulfilmentSagaManager`. `placeOrder` calls `manager.insertStarted(...)`; `cancel` calls `manager.requestCompensation(...)`. Existing `SalesOrderService.SagaNotFoundException` kept for the controller's existing `@ExceptionHandler` mapping; manager throws its own exception which `cancel` catches + rethrows as the existing type (one-line translation). Could be tidied later by importing the manager's exception in the controller.

### Test rebalance

- **New** `JdbcSalesOrderFulfilmentSagaManagerTest` (~25 transition tests in 9 nested classes) — `InsertStarted`, `RequestCompensation`, `ApplyStockReserved` (full / partial / failed / no-saga), `ApplyWorkOrderCreated` (first WO advances, subsequent registers, sub-assembly skipped), `ApplyWorkOrderManufacturingCompleted` (last completes saga, partial holds, sub-assembly skipped), `ApplyManufacturingDispatched` (all-rejected → stock_reservation_failed; any-accepted stamps expected count), `ApplyShipmentPosted` (advances + delegates to shipping; unrelated state ignored), `ApplyCustomerInvoiceCreated` (happy + ignored), `ApplyCustomerPaymentReceived` (full / partial / from-invoice-paid / unrelated), `ApplyCancellationApplied` (single ack noop, both acks → compensated + emit `SalesOrderCompensated`, no-saga warns).
- **Existing handler tests rewritten** as shell-smoke (3 tests each):
  - `StockReservedHandlerTest`, `CustomerPaymentReceivedHandlerTest`, `ManufacturingDispatchedHandlerTest`, `WorkOrderManufacturingCompletedHandlerTest` — verify dedupe + manager-call + recordProcessed only. The substantive transition assertions moved into the manager test.
- **Other tests unchanged**: `FulfilmentSagaDataTest`, `SalesOrderTest`, `SalesOrderShippingServiceTest`, `SalesApplicationTests` (Spring DI smoke — verifies the new manager bean wires correctly).
- **Total**: 79 tests green (up from 53 pre-refactor; the rewrite added ~25 manager tests, removed ~6 from the deleted SagaCompensationCompletionServiceTest, and the handler-test rewrites compressed ~15 transition assertions into ~10 shell-smoke ones).

### Patterns reinforced from §2.5 Phase A

The Phase A test patterns carry directly into the manager test:
- Real-aggregate fixtures: `sagaInState(state, data)` helper builds a `SalesOrderFulfilmentSaga` via the public constructor, reusing `FulfilmentSagaData.none()` builders.
- `ArgumentCaptor<OutboxRow>` + `json.readValue(row.getPayload(), SalesOrderCompensated.class)` for typed payload assertions.
- `verifyNoInteractions(outbox)` to assert "didn't emit anything" branches.
- Same Mockito strict-stubbing discipline (no unused stubs).

### Smoke

`mvn -pl sales-service test` — 79 tests, 0 failures, 0 errors. `mvn -pl manufacturing-service,purchasing-service compile` — clean (the `SagaPollingWorker` → `SagaManager` rename only touched the extends clause + import). `mvn -pl shared-kernel,shared-infrastructure -am install -DskipTests` was needed once mid-refactor so sales-service could see the renamed class.

### Follow-ups

- **§2.9 Slice B** — `MakeToOrderSagaManager` (manufacturing-service). Same template applies; the manager replaces `MakeToOrderSagaWorker.advance` + `RawMaterialsReservedHandler` + `GoodsReceivedHandler` (un-park path) + the saga-completion call from `WorkOrderOperationService.advanceSagaToCompleted` + the saga-flip from `WorkOrderCancellationService.cancelForSalesOrder`.
- **§2.9 Slice C** — `PurchaseToPaySagaManager` (purchasing-service). Same shape.
- **§2.7 (saga state constants)** — now scoped to one manager class per saga (much smaller surface than before). Ship after all three Slice A/B/C land.

### Things noticed during the refactor (not blockers)

- The `SalesOrderService.SagaNotFoundException` ↔ `SalesOrderFulfilmentSagaManager.SagaNotFoundException` translation is mildly silly. Could collapse into one type imported from the manager interface — controller `@ExceptionHandler` import would change too. Not worth doing in this slice; flag for a tidy-up if the manager interface gets generally consumed by the API layer in future.
- `JdbcTemplate` is still injected into the manager (used by `requestStockReservation` + `requestManufacturing` to read sales-order lines). Per the architectural rule "no JdbcTemplate in `application/`", this is fine because the manager *impl* lives in `infrastructure/saga/`. The interface in `application/saga/` doesn't expose `jdbc`.

---

## 2026-05-09 — §2.5 Phase A: application-layer unit-test coverage (108 tests across 8 services)

Worked through dev-todo.md §2.5 Phase A start-to-end. All eight Phase A items shipped, every test green on first or second run. Application-layer unit-test count goes from ~80 (existing handlers + reverse-by-source + materials-cost rollup) to ~190.

### Per-service test counts

| # | Service | Tests | File |
|---|---|---|---|
| 1 | `manufacturing.BomEditService` | 22 | `BomEditServiceTest.java` |
| 2 | `inventory.StockReservationService` | 12 | `StockReservationServiceTest.java` |
| 3 | `manufacturing.WorkOrderOperationService` | 13 | `WorkOrderOperationServiceTest.java` |
| 4 | `finance.JournalEntryService` (postings + reverse) | 15 | `JournalEntryServicePostingsTest.java` |
| 5 | `finance.PaymentService` | 15 | `PaymentServiceTest.java` |
| 6 | `manufacturing.WorkOrderCancellationService` | 5 | `WorkOrderCancellationServiceTest.java` |
| 7 | `sales.SagaCompensationCompletionService` | 6 | `SagaCompensationCompletionServiceTest.java` |
| 8 | Lower-priority bundle (5 services) | 20 | `WorkOrderPrioritisationServiceTest`, `SupplierProductPriceServiceTest`, `ShipmentServiceTest`, `GoodsReceiptServiceTest`, `SalesOrderShippingServiceTest` |
| **Total** | | **108** | |

### Test patterns that emerged (carry into Phase B)

- **Real-aggregate fixtures**: build lightweight real instances via factory methods (`WorkOrder.release(...)`, `MakeToOrderSaga.attachedToWorkOrder(...)`, `SalesOrder.reconstitute(...)`) and assert on actual state changes (`wo.status()`, `saga.state()`, `pullPendingEvents()`). No need to mock aggregates — Mockito only mocks the ports.
- **OutboxRow capture pattern**: `ArgumentCaptor<OutboxRow>` + `json.readValue(row.getPayload(), EventType.class)` + typed accessor assertions. Cleaner than `JsonNode` field-name digging and survives Jackson naming-strategy changes. Encoded as a per-test-class helper (`capturedAck()`, `capturedEvent()`).
- **Strict-stub escape hatch**: `lenient().when(...)` for shared `@BeforeEach` stubs that not all tests consume (e.g. `glAccounts.byCode(any())` in `JournalEntryServicePostingsTest` — needed by posting tests, irrelevant to zero-total-skip and reverse-rejection tests).
- **`thenAnswer` for code-derived stubs**: turn need-to-stub-N-codes into one stub that builds return values from the input arg.
- **`Instant` bracketing for now()-fallback assertions**: `before = Instant.now() ... after = Instant.now()` and `assertThat(event.cancelledAt()).isBetween(before, after)` — verifies "stamped as current time" without freezing the clock or injecting a `Clock` collaborator.
- **Cascade-test fixture order**: when a child WO references a parent's id, build the parent first so its auto-generated `WorkOrderId` can be passed as the child's `parentWorkOrderId`. `WorkOrder.release()` generates a fresh id internally; can't pre-set.

### Two stubbing pitfalls hit + resolved

- `JsonNode.get("childWorkOrderId").asText()` returned null in the `WorkOrderOperationService` parent-completion test because Jackson 3's record-serialisation key didn't match my guess. Switched to typed deserialisation via `json.readValue(payload, SubAssembliesConsumed.class)` and asserted on typed accessors.
- Cross-stubbing `sagas.findByWorkOrderId(parentId)` with a random UUID failed because the test created the parent's `WorkOrder` *after* declaring the parent UUID — the WO's auto-generated id and the stub's UUID didn't match. Fix: create the parent WO first, then derive `parentId = parent.id().value()`.

### Smoke

`mvn -pl <service> test -Dtest=<TestClass>` per service: 108 tests, 0 failures, 0 errors. Total wall-clock to ship Phase A start-to-end: ~75 minutes including reading service collaborators, drafting tests, and the two debug-and-fix iterations.

### Follow-ups (none demo-blocking)

Captured in dev-todo.md §2.5:
- **Phase B** — ~10 inbox handlers without tests; same pattern templates apply directly.
- **Phase C** (deferred) — Testcontainers integration tests for the `Jdbc*` impls; separate slice if at all.
- **§2.5.1 Phase D** — in-memory end-to-end saga harness; gated on Phase A completion (now done).

---

## 2026-05-08 — §1.3 Slice C remainder: 9 placeholder screens shipped

Closes the remaining `Placeholder` routes from the Slice C C0 scaffolding. Six inventory screens (Mike's), one PR creation form (Tom's), and two standalone dashboards (sales-order 360 + AR/AP). The two explicitly-deferred items (BOM editor per §3.4, /system/users) stay deferred.

### Backend list endpoints (5 new, 0 schema changes)

- **purchasing** — new `SupplierController` at `GET /api/suppliers` + `GET /api/suppliers/{id}`. `SupplierRepository.findAll()` added; JDBC sorts by `supplier_code`. `SupplierResponse` DTO.
- **inventory** — `GoodsReceiptRepository.findAllHeaders()` (no lines) + `GET /api/goods-receipts`. List view shows headers; detail still loads lines.
- **inventory** — same shape for `ShipmentRepository.findAllHeaders()` + `GET /api/shipments`.
- **inventory** — new `StockReservationQueryPort` + `JdbcStockReservationQueryPort` (CQRS read for list view; aggregates header + line totals via `LEFT JOIN`-with-aggregate). New `StockReservationController` at `GET /api/stock-reservations`. Returns row-shape `(headerId, sourceSO|sourceWO, status, lineCount, requested/reserved/shortage totals, createdAt)`.
- **inventory** — new `StockMovementQueryPort` + `JdbcStockMovementQueryPort` (audit-list read with `LIMIT`). New `StockMovementController` at `GET /api/stock-movements?limit=N` (default 200, max 1000). The append-only `stock_movement` table is partitioned by month so a future tightening can scope the query to a date window.

### BFF route table

Added `/api/stock-reservations → inventory`. Other 4 endpoints already routed.

### Frontend (erp-web-ui)

- **/suppliers** — `Suppliers.tsx` — read-only list (code, name, status). Onboarding flow deferred.
- **/stock-items** — `StockItems.tsx` — list with reorder-policy columns. Reorder editing happens via Product Detail (Shape A authority).
- **/stock-reservations** — `StockReservations.tsx` — list with sourceable distinction (sales / work order), aggregate quantities, status pill. Read-only — reservations are saga-driven.
- **/goods-receipts** — `GoodsReceipts.tsx` — receipt headers list. Read-only — posting is a backend action.
- **/shipments** — `Shipments.tsx` — shipment headers list. Read-only — posting is a backend action.
- **/stock-movements** — `StockMovements.tsx` — append-only audit, capped at 200 most-recent. Direction column with up/down arrows; type humanised.
- **/purchase-requisitions** — `PurchaseRequisitionNew.tsx` — header + multi-line form with product picker (filtered to non-discontinued). Auto-approves at creation per existing PurchaseRequisitionService shape; redirects to `/purchase-orders` (the converted PO lands there).
- **/sales-orders/360** — `SalesOrder360Dashboard.tsx` — full 360 roll-up: order, stock, manufacturing, shipment, invoice, payment status side-by-side. Reads same `/api/sales-orders` endpoint as the regular list; the shape and visible columns differ.
- **/ar-ap** — `ArAp.tsx` — focused tile grid: Accounts Receivable, Accounts Payable, Cash Received, Cash Paid for the latest dashboard day. Quick-action links to customer-invoices / supplier-invoices pending review / payments / journal entries. Note: today's projection has zeros for `accounts_receivable` / `accounts_payable` (deferred per §2.1); the screen surfaces this transparently.

### Routes

`App.tsx` updated: 9 placeholders replaced with their real components. Remaining placeholder routes (`/purchase-orders/tracking`, `/atp`, `/material-shortages`, `/financial-dashboard`, `/boms`, `/system/users`) stay — they're tracked elsewhere or explicitly deferred.

### Verification

- All 12 Maven modules build clean (`mvn install -DskipTests`).
- 34 backend tests pass across inventory + purchasing.
- TypeScript clean (`tsc --noEmit`).

### Smoke checklist

- `mvn install -DskipTests` ; `docker compose up -d` ; bring up services with `SPRING_PROFILES_ACTIVE=kafka` ; `npm run dev` in erp-web-ui.
- Click through each route: `/suppliers`, `/stock-items`, `/stock-reservations`, `/goods-receipts`, `/shipments`, `/stock-movements`, `/sales-orders/360`, `/ar-ap` — every page should render with seeded data.
- `/purchase-requisitions` form: pick a product, enter qty=1, click Create — gets redirected to `/purchase-orders` and the new PO from the auto-converted PR is visible.

### Follow-ups

- Goods-receipts and shipments lists show empty `lines` arrays for each row (the headers-only loader returns aggregates with `List.of()` lines). Cosmetic — wire a separate lightweight DTO if the list ever needs to render line counts inline.
- Supplier onboarding flow (create / update / status changes) — gated on a real user story.
- Goods receipt + shipment authoring forms — backend supports POST today; UI form layer not built (deferred per the same "backend has the endpoints but forms aren't built" rationale that's now closed for read views).
- BOM editor + /system/users remain deferred per §3.4 and §3 below.

---

## 2026-05-08 — §2.8 Slice D: BoM-walk rollup + parent recursion + currency-mismatch throw

Closes Slice D of the four-part pricing split (§2.8). The materialsCost engine now computes for manufactured items by walking the active BoM, sums components weighted by quantity + scrap factor, and recurses up the parent chain whenever a component's cost moves. Slice C's supplier-price path is preserved as the fallback for products without an active BoM.

### Routing rule: BoM wins

A product with an active BoM (per `bom_header.status='active'` runtime authority, `product_active_bom` projection co-existing) gets its materialsCost from the BoM rollup. Supplier prices for the *parent* product are ignored — the cost basis follows engineering structure, not procurement convenience. Supplier prices for *components* still flow: they update the leaf, which walks up.

Routing decision tree:
1. Active BoM present → BoM rollup.
2. No BoM, `is_purchased=true`, preferred supplier matches event → supplier-price path (Slice C).
3. No BoM, `is_purchased=false` → `inputs_missing` (manufactured-only without a BoM is undefined).
4. Ambiguous preferred supplier (0 or 2+) → `inputs_missing`.
5. Any component with null materialsCost → parent propagates as `inputs_missing` (all-or-nothing).

### Parent recursion

After applying any cost change, the engine queries `BomLookup.findParentProductIdsByComponent(productId)` and recursively recomputes each parent in the same transaction. Diamond-graph protection via a `Set<UUID> visited`; defensive `MAX_WALK_DEPTH=32` (BoM is acyclic by enforcement so it can't be load-bearing). No-op suppression on unchanged cost+currency+reason short-circuits the cascade — at-least-once redelivery doesn't ripple through the whole tree.

### Currency-mismatch policy (locked 2026-05-08)

The BoM rollup throws `IllegalStateException` when components in one BoM are priced in different currencies. The throw rolls back the whole transaction so a half-applied state can't escape. Slice E adds `CurrencyConverter` integration when the throw actually fires in practice (today the showcase data is single-currency).

### Code

- `domain/BomLookup.java` + `infrastructure/persistence/JdbcBomLookup.java` — added `findParentProductIdsByComponent(UUID)`. SQL joins `bom_header.status='active'` × `bom_line` for distinct finished-product ids. Uses `bom_header.status` (always populated for active BoMs) rather than `product_active_bom` (which `BomEditService.activate` doesn't update — see §2.8 follow-up below).
- `application/MaterialsCostRollupService.java` — significant refactor: `onSupplierPriceChange` now routes through `bomLookup.findActiveByFinishedProductId` first (BoM wins). New `recomputeViaBom(productId, reason)` public entry point + private overload with `visited` + `depth` for the parent walk. Private `applyAndWalk` centralises projection write, event emission, no-op suppression, and parent walk.
- `application/inbox/BomActivatedHandler.java` — cross-service activation path: after the projection apply, calls `rollup.recomputeViaBom(payload.aggregateId(), "bom_activated")`.
- `application/BomEditService.java` — in-service activation path: after `markActive` + cycle check, calls `rollup.recomputeViaBom(header.finishedProductId(), "bom_activated")`. The constructor now takes the rollup service.

### Tests

`MaterialsCostRollupServiceTest` — 13 unit tests across 4 nested classes:

- **SupplierPricePath** (5 tests): preferred-match emits, non-preferred skips, ambiguous-preferred → inputs_missing, BoM-present skips supplier event, manufactured-only-no-BoM skips silently.
- **BomRollup** (6 tests): single-level happy path with two components, scrap-factor uplift applied per line, missing component cost propagates inputs_missing, component with null cost (inputs_missing reason) also propagates, cross-currency throws, no-active-BoM is a no-op (doesn't blow away an existing supplier-derived row).
- **ParentWalk** (1 test): supplier-price change on a raw material walks up to a parent that has the raw material as a component; emits two `ProductMaterialsCostComputed` events with correct reasons (`supplier_price_change` for the leaf, `child_materials_cost_changed` for the parent).
- **NoOpSuppression** (1 test): unchanged cost+currency+reason skips the apply / emit / parent walk entirely.

Total manufacturing tests: 55 (42 prior + 13 new); reactor build green across all 12 modules.

### Smoke checklist (manual; not exercised on a fresh boot yet)

- `docker compose down -v ; docker compose up -d postgres`
- `mvn -pl shared-kernel,shared-infrastructure -am install -DskipTests ; mvn -pl manufacturing-service spring-boot:run` with `SPRING_PROFILES_ACTIVE=kafka` and the rest of the services up.
- Activate a BoM via `POST /api/boms/{id}/activate`. Then `GET /api/products/{finishedProductId}/materials-cost` should return the rolled-up sum + AUD; `manufacturing.ProductMaterialsCostComputed` reason `bom_activated` lands on `manufacturing.events`.
- Change one of the BoM's components' supplier price via `POST /api/supplier-product-prices` for the preferred supplier. Two events land: leaf gets `supplier_price_change`, parent gets `child_materials_cost_changed`.
- Author a price for a non-preferred supplier of a leaf — no event chain.
- For a finished product with no rolled-up cost (e.g. raw_material with no preferred), the UI's "Materials Cost" cell shows "—" with hint "no preferred supplier".
- For a sub-assembly chain (FG-CABINET-001 → SA-CABINET-DOOR-PANEL → MDF panel etc.), updating the leaf's supplier price walks all the way up; check both the leaf and the FG show the new cost and `manufacturing.events` carries one event per affected level.

### Follow-ups noted in dev-todo

- **Slice E (cross-currency rollup via CurrencyConverter)** — gated on the throw actually firing. Today's demo dataset is single-currency.
- **`product_active_bom` drift on in-service activation** — `BomEditService.activate` flips `bom_header.status` but doesn't write to `product_active_bom`. Slice D works around it by using `bom_header.status` for the parent-walk SQL, but the projection itself drifts. Cleanest fix: have `BomEditService.activate` also write the projection. Out of scope for Slice D since the workaround is robust.
- **BoM deactivation flow** — when an explicit deactivate command lands (currently only "swap by activating a sibling" exists), add `bom_deactivated` as a reason and a routing path that flips back to supplier-price / inputs_missing.

---

## 2026-05-08 — §2.8 Slice C: materialsCost rollup engine for purchased items (manufacturing-owned)

Closes Slice C of the four-part pricing split (§2.8). Computes `materialsCost` for purchased items off the preferred supplier's price; emits a public event so other consumers can react. **Locked 2026-05-08:** the field lives on `manufacturing.product_materials_cost`, not on the `Product` aggregate — the rollup engine and the data of record live in the same service, preserving product-service's producer-only architectural contract.

### Why ownership moved out of product-service

Original plan: add `materialsCost` to `Product`, have manufacturing emit `ProductMaterialsCostComputed`, have product-service consume it via an `applyMaterialsCost` method. That broke the invariant that product-service is upstream Open Host (producer-only) — once it has even one cross-service inbox handler the invariant is gone for good.

Revised design: manufacturing owns the table, the engine, and the public event. The UI fetches `GET /api/products/{id}/materials-cost` from a manufacturing endpoint; the BFF stitches it client-side via a parallel React Query. product-service stays untouched.

The general rule landed in design-notes.md → "Computed values live with the engine that computes them, not the master aggregate".

### Schema (Liquibase changeset + v3.sql baseline)

- `manufacturing.product_materials_cost(product_id PK, materials_cost NUMERIC NULL, currency_code CHAR(3) NULL, reason VARCHAR(40), captured_at, updated_at)`. Nullable `materials_cost`+`currency_code` together: when `reason='inputs_missing'` both are null and the UI renders "—".
- `manufacturing.product_approved_vendor(product_id, supplier_id, supplier_code, supplier_name, preferred BOOL, PK on (product_id,supplier_id))`. Mirrors the purchasing-side projection — duplication is the accepted cost of cross-schema isolation. Partial index on `preferred=true` for the rollup engine's preferred-supplier lookup.
- Both backed by `shared.set_updated_at()` triggers; both idempotent (`IF NOT EXISTS`, `CREATE OR REPLACE TRIGGER`).
- `db/northwood_erp_v3.sql` baseline updated with the same shape — fresh-volume boots get the tables without running the changeset.

### Manufacturing application code

- `application/inbox/ApprovedVendorListChangedPayload.java`, `ApprovedVendorListChangedHandler.java`, `ProductApprovedVendorProjection.java` — projects `product.ApprovedVendorListChanged` into the new table. `findPreferredSupplierId(productId)` returns empty when 0 or 2+ rows are flagged preferred (both ambiguous to the rollup).
- `infrastructure/persistence/JdbcProductApprovedVendorProjection.java` — replace-all semantics, mirrors the purchasing JDBC adapter.
- `application/inbox/ProductMaterialsCostProjection.java`, `infrastructure/persistence/JdbcProductMaterialsCostProjection.java` — owns the rollup output. Upsert on `apply(...)`; `findByProductId(...)` for the read path.
- `application/inbox/SupplierProductPriceChangedPayload.java`, `SupplierProductPriceChangedHandler.java` — drives the rollup off `purchasing.SupplierProductPriceChanged`. Standard inbox-dedupe-deserialise glue; routing in `MaterialsCostRollupService`.
- `application/MaterialsCostRollupService.java` — the engine. Reads `is_purchased` from `ProductReplenishmentProjection`, reads preferred supplier from `ProductApprovedVendorProjection`. Routing rules:
  - `is_purchased=false` → ignore (Slice D's job for manufactured items).
  - 0 or 2+ preferred suppliers → emit `reason='inputs_missing'`, materialsCost null.
  - Preferred supplier matches the event's supplier → emit `reason='supplier_price_change'` with the new unit price + currency.
  - Preferred supplier doesn't match the event's supplier → ignore (event isn't authoritative).
- `domain/events/ProductMaterialsCostComputed.java` — public contract. Pure read-side concern, emitted directly from the service (no aggregate state mutation), mirrors `WorkOrderPriorityChanged`.
- `application-kafka.yml` — added `purchasing.events` to `subscribe-topics`.
- `application-bom-cycle-detector` already used. `ProductActiveBomProjection` gained `findActiveBomId(productId)` for Slice D's later use; not consulted yet in Slice C.

### Read path

- `api/ProductMaterialsCostController.java` — `GET /api/products/{id}/materials-cost`. Returns 404 when the product has never been rolled up; the UI treats 404 the same as `reason='inputs_missing'`.
- `erp-web-ui-bff/.../ProductMaterialsCostProxyController.java` — `@GetMapping("/api/products/{id}/materials-cost")` more-specific than the generic `/api/**` proxy, so Spring's mapping-precedence picks it. Calls manufacturing directly. Why a one-off proxy rather than a `RouteTable` entry: the route table is pure prefix-based but materialsCost is a *suffix* under `/api/products/{id}/...`. When a second suffix-routed endpoint shows up we'll generalise.
- `erp-web-ui/.../ProductDetail.tsx` — second React Query fetches materialsCost in parallel with the product master. New `Materials Cost` field in the Pricing section with a hint line ("from preferred supplier" / "no preferred supplier" / "no rollup yet").

### Currency policy

Single-currency in Slice C: the rollup adopts whatever currency the supplier price is in (no combining → no chance of mismatch). Slice D's BoM rollup will throw on cross-currency combinations until a Slice E adds `CurrencyConverter` integration. Locked 2026-05-08.

### Documented limitation: vendor-flip recompute deferred

A `product.ApprovedVendorListChanged` that switches the preferred supplier does *not* immediately trigger a recompute — the cost auto-corrects on the next `SupplierProductPriceChanged` from the new preferred. Acceptable lag for the showcase; tightening alternative listed in design-notes.md.

### Smoke checklist (manual; not yet exercised on a fresh boot)

- `docker compose down -v ; docker compose up -d postgres`
- `mvn -pl shared-kernel,shared-infrastructure -am install -DskipTests`
- `mvn -pl manufacturing-service spring-boot:run` → confirm Liquibase applies the new changeset; tables created on first boot of a fresh volume.
- Bring up the rest of the services with `SPRING_PROFILES_ACTIVE=kafka`.
- `POST /api/supplier-product-prices` (preferred supplier of a purchased product) → confirm `manufacturing.product_materials_cost` row populated; `manufacturing.ProductMaterialsCostComputed` lands on `manufacturing.events`.
- `GET /api/products/{id}/materials-cost` returns 200 with the new cost.
- Open `/products/{id}` in the UI → "Materials Cost" cell shows the value alongside Standard Cost; hint reads "from preferred supplier".
- Author a price for a *non*-preferred supplier → no event, no cost change.
- Author a price for a product with no preferred (or 2+ preferred) → `reason='inputs_missing'`, cost null, UI shows "—" with hint "no preferred supplier".

### Follow-ups (already in dev-todo)

- Slice D: extend the same engine to manufactured items (BoM walk + recursive parent recompute via the same `ProductMaterialsCostComputed` event). Adds the cross-currency-throw bound; integrating `CurrencyConverter` is a separate Slice E.
- Bringing the vendor-flip recompute path online (acceptable-lag tightening alternative in design-notes.md) — only if the lag becomes a real concern.

---

## 2026-05-08 — Customer promoted from reference data to a full aggregate

Closes the long-standing `/customers` placeholder in the ERP UI. `sales.customer` was schema-only with one seed row and no domain code; this slice gives it the same aggregate / repository / service / controller / UI shape every other domain entity in the codebase already has.

### Domain (sales.domain)

- `CustomerId` — strongly-typed identifier mirroring `ProductId`.
- `Customer` aggregate — fields: `customerCode` (immutable identity), `name`, `email`, `phone`, `billingAddress`, `shippingAddress`, `status` (enum `ACTIVE | INACTIVE | BLOCKED`), `version`. Static factories `register(...)` (emits `CustomerRegistered`) and `reconstitute(...)`. Mutations: `changeName`, `changeContact`, `changeBillingAddress`, `changeShippingAddress`, `deactivate`. No-op suppression on every mutation (per §2.9). Inactive/blocked customers can't be edited (operational invariant).
- Five domain events in `events/`: `CustomerRegistered`, `CustomerNameChanged`, `CustomerContactChanged`, `CustomerAddressChanged` (with `addressType` discriminator), `CustomerDeactivated`.

### Schema

No migration needed — `sales.customer` already had `version`, `created_by`, `last_modified_by`, `status` CHECK from §1.2 B2. The aggregate maps onto the existing columns.

### Application + infrastructure

- `CustomerRepository` port (findById / findByCode / findAll / save).
- `JdbcCustomerRepository` follows the established repo template — `CurrentUserAccessor` injection, INSERT writes `created_by` + `last_modified_by` + outbox `actor_user_id`; UPDATE writes `last_modified_by` + bumps version with optimistic-locking guard; outbox INSERT carries `actor_user_id`. `DuplicateCustomerCodeException` for the unique constraint hit.
- `CustomerService` (application layer) — thin pass-through to aggregate intent methods. `CustomerNotFoundException` translated to 404 by the controller.
- `CustomerController` exposes 8 endpoints under `/api/customers`: `POST` register, `GET /{id}`, `GET /` list, `PUT /{id}/name`, `PUT /{id}/contact`, `PUT /{id}/billing-address`, `PUT /{id}/shipping-address`, `POST /{id}/deactivate`. All mutating endpoints `@PreAuthorize("hasRole('sales_clerk')")`.
- DTOs: `CustomerResponse`, `RegisterCustomerRequest`, `ChangeCustomerNameRequest`, `ChangeCustomerContactRequest`, `ChangeCustomerAddressRequest`, `DeactivateCustomerRequest`.

### UI (erp-web-ui)

- `routes/sales/Customers.tsx` — list page with a DataGrid (code / name / email / phone / status pill) and **New customer** action button.
- `routes/sales/CustomerDetail.tsx` — detail page with five action buttons opening focused dialogs: Rename, Contact, Billing address, Shipping address, Deactivate. The Rename dialog explicitly tells the user "snapshot-only — historical orders keep the old name."
- `routes/sales/CustomerNew.tsx` — registration form route, Sales-clerk-gated.
- App.tsx wires three routes (`/customers`, `/customers/new`, `/customers/:id`); the existing `/customers` placeholder is removed. Sidebar already had the `Customers` link under Sales — no change there.

### Locked design decision: snapshot-only

`SalesOrder.place(...)` snapshots `customer_id` + `customer_code` + `customer_name` at placement time and never refreshes them. `Customer.changeName(...)` emits `CustomerNameChanged` but no consumer projects the change back onto historical orders or onto reporting's `sales_order_360_view.customer_name`. That's audit-correct ERP behaviour (the order shows what was on it at placement; the customer's current name is a separate concern).

Documented in three layers per the project's documentation discipline:
- `Customer` class Javadoc + `CustomerNameChanged` event Javadoc spell out the policy.
- A block comment on `SalesOrder`'s `customerId/customerCode/customerName` fields cross-references the policy.
- New section in `design-notes.md` → "Snapshotted reference data" lists the snapshotted fields, the rationale (audit correctness, parity with `created_at`-style invariants), and three named tightening alternatives in escalation order if a name-rippling use case ever appears (1: directory-view projection; 2: re-project to existing rows; 3: dual snapshot+current columns). Lives next to the silent-fallbacks index because both are durable behaviour-encoded-as-data decisions a future reader could mistake for bugs.

### Verification

- `mvn clean install -DskipTests` green across all 12 modules.
- `mvn -pl sales-service test` green — 86 tests; no existing test exercised the customer entity, so this slice didn't break anything but also doesn't add new aggregate-level test coverage. Worth folding into §2.5 Phase A when that picks up.
- SPA TypeScript not checked from this session — components compile shape-wise but `npm run dev` against the live stack will catch any runtime/JSX issues.

### Smoke checklist (run by the user)

```powershell
# Pre-seeded customer is "CUST-001 — Sydney Home Living". A fresh customer:
docker compose down -v ; docker compose up -d postgres kafka keycloak
mvn clean install -DskipTests
mvn -pl sales-service spring-boot:run                                  # 8082
mvn -pl reporting-service spring-boot:run                              # 8087  (for the 360 cross-check)
mvn -pl erp-web-ui-bff spring-boot:run                                 # 8089
cd erp-web-ui ; npm run dev                                            # 5174
```

In the browser (logged in as Sarah / `sales_clerk`):

1. Sidebar → **Sales → Customers** — list shows CUST-001 (the seed).
2. Click **New customer** — register `CUST-002` "Acme Hardware" with an email + billing address.
3. Open the new customer's detail page; click **Rename** — change to "Acme Hardware Pty Ltd". Toast confirms snapshot semantics.
4. (Optional) Place a sales order against CUST-001 ahead of time, then rename CUST-001. Reload the order's detail / 360 view — `customer_name` still shows the original. ✅ snapshot-only confirmed.
5. **Deactivate** CUST-002 with a reason. Status pill flips to `inactive`; all action buttons except deactivate become disabled.

### Follow-ups

- **Reject orders against inactive/blocked customers** ✅ shipped same-day (Option 1 from the triage). `CustomerLookup.CustomerSummary` gained a `status` field; `JdbcCustomerLookup` SQL selects it; `SalesOrderService.placeOrder` throws new `CustomerInactiveException` when status != `'active'`; `SalesOrderController`'s exception handler maps it to HTTP 409 (alongside `OrderNotCancellableException`). No test impact (no existing test mocks `CustomerLookup`). Smoke check: deactivate CUST-001 then `POST /api/sales-orders` → 409 with body "Customer CUST-001 is inactive; cannot accept new orders".
- **Customer aggregate tests** missing — when §2.5 Phase A picks up, fold a `CustomerTest` into the queue (no-op suppression on each `change*`, status-guards reject inactive edits, factory-emits-`CustomerRegistered`, etc.).
- **`BLOCKED` status has no API path today** — the aggregate enum supports it but no controller endpoint sets it. Add a `POST /{id}/block` when a credit-hold use case lands; today there's no consumer.
- **Customer events have no consumers yet** — `CustomerNameChanged` etc. flow to the outbox but no service subscribes. The audit-log viewer (Slice D) shows them on the timeline by virtue of the generic outbox query, which is enough to demo "change tracked + actor stamped." A future customer-directory projection in reporting would change that.

---

## 2026-05-08 — §2.8 Slice B: finance standard-cost projection + COGS sourcing

Closes the ⏳ user-story criterion ("finance projects standard cost for COGS"). Finance now subscribes to `product.StandardCostChanged`, maintains a local `product_standard_cost` projection, and reads from it at COGS posting time instead of trusting whatever `unitCost` the warehouse clerk typed onto the shipment line. Net effect: GL captures finance's authoritative cost for every shipment.

### Schema (Liquibase, idempotent)

`finance-service/src/main/resources/db/changelog/changes/2026-05-08-add-product-standard-cost-projection.sql`:
- `CREATE TABLE IF NOT EXISTS finance.product_standard_cost (product_id UUID PRIMARY KEY, standard_cost NUMERIC(18,6) NOT NULL, currency_code CHAR(3) NOT NULL DEFAULT 'AUD', captured_at TIMESTAMPTZ NOT NULL DEFAULT now())`
- `CREATE OR REPLACE TRIGGER trg_product_standard_cost_updated_at` (mirrors the `sales.product_pricing` shape).
- Seed 9 rows from baseline product values (`FG-TABLE-001` 320.00, `RM-BOARD-001` 80.00, …, `RM-DRAWER-RUNNER-001` 14.00) so day-1 the projection is fully populated; subsequent `StandardCostChanged` events update individual rows.

### Projection (application/inbox/ + infrastructure/persistence/)

- `ProductStandardCostProjection` interface — `findStandardCost(productId): Optional<BigDecimal>`, `apply(productId, cost, currency)`.
- `JdbcProductStandardCostProjection` — INSERT … ON CONFLICT DO UPDATE upsert. Mirrors `JdbcProductValuationClassProjection` in shape.

### Inbox handler

- `StandardCostChangedHandler` (`application/inbox/`) subscribed to `product.StandardCostChanged`.
- `StandardCostChangedPayload` (consumer-side wire record). Inbox dedupe via `inbox_message`.
- Finance's Kafka subscription already includes `product.events` (added when `ValuationClassChangedHandler` was wired) — no profile change needed.

### Cost-source switch

- `ShipmentPostedCogsHandler` injects `ProductStandardCostProjection`. Per inbound shipment line: lookup standard cost; on hit, use it; on miss, fall back to event-stamped `unitCost` and increment a per-shipment counter for a single DEBUG summary log.
- Documented as silent-fallback row #7 in `design-notes.md` per the rule. The fallback fires only on a fresh-volume race before the seed runs, or for a brand-new product whose `StandardCostChanged` hasn't yet propagated to finance — both rare in practice.

### Verification

- `mvn clean install -DskipTests` green across all 12 modules.
- `mvn -pl finance-service test` green — 56 tests; no existing finance test exercises the COGS handler so the cost-source switch is mechanical.
- Smoke not yet run against fresh DB volume — natural moment is the next Demo 7 walkthrough where shipments fire COGS journals. The Liquibase changeset is `IF NOT EXISTS`-idempotent + `ON CONFLICT DO NOTHING` for the seed; existing volumes upgrade cleanly.

### Follow-ups

- **Smoke verification.** Drive Demo 3.1 (place + ship) on a fresh volume; confirm `SELECT debit_amount FROM finance.journal_entry_line jel JOIN finance.journal_entry_header jeh ON jeh.journal_entry_header_id = jel.journal_entry_header_id WHERE jeh.source_module = 'finance' AND jeh.source_document_type = 'shipment_cost'` matches `SUM(qty * standard_cost)` from the product-master view.
- **Coverage gap for new products.** A product created via `POST /api/products` will have `standard_cost` on the aggregate but no row in `finance.product_standard_cost` until a subsequent `StandardCostChanged` fires (which only happens on a *change*). Two options to close this: (a) emit `StandardCostChanged` once on creation in `Product.register(...)` so the projection seeds on day-1; (b) subscribe finance to `ProductCreated` too (but that event doesn't carry cost today; would need extending). Today the silent fallback handles this case correctly because the shipment-line `unitCost` will equal the just-set `standard_cost`. Worth (a) when the next price-related slice lands.
- **§2.8 Slice C now ready** — extend the schema with `materials_cost` + rollup engine for purchased items only. The standard-cost projection pattern carries over directly.

---

## 2026-05-08 — §2.7: extract `ApprovedVendor` as a domain VO

Closes the only DDD-layering smell flagged in the post-`*Projection`-rename audit. The audit had raised two outlier `domain.events.*` imports in product-service:

- `ProductController` constructed `ApprovedVendorListChanged.ApprovedVendor` records as a transport into the application service (controller reaching into a domain-event class).
- `JdbcApprovedVendorRepository` returned + accepted `ApprovedVendorListChanged.ApprovedVendor` (infrastructure adapter importing an event subtype).

Both were case (a): the type was being used as a transport, not unused. The smell ran deeper — the **repository port itself** (`ApprovedVendorRepository`) and the **aggregate's mutation method** (`Product.emitApprovedVendorListChanged`) also took `ApprovedVendorListChanged.ApprovedVendor` as parameter type, so the event class was leaking into every layer.

**Fix:** promote `ApprovedVendor` to a top-level domain VO at `com.northwood.product.domain.ApprovedVendor`. The event references the VO; nobody else imports the event.

Files touched:
- New `com.northwood.product.domain.ApprovedVendor` (record).
- `events.ApprovedVendorListChanged` — drops the nested `ApprovedVendor` record, references the new VO via import.
- `domain.ApprovedVendorRepository` — drops the events-import; `findForProduct` and `replaceFor` use `ApprovedVendor`.
- `infrastructure.persistence.JdbcApprovedVendorRepository` — drops the events-import; row mapper + signatures use `ApprovedVendor`.
- `domain.Product.emitApprovedVendorListChanged` — parameter type updated; the `events.ApprovedVendorListChanged` import on this file stays (it emits the event — legitimate).
- `application.ProductService` — drops the events-import; `setApprovedVendors` parameter and `sameVendorSet` helper use `ApprovedVendor`. The Javadoc cross-reference to `ApprovedVendorListChanged` switched to a fully-qualified `{@link}` so removing the import didn't break the doc.
- `api.ProductController` — drops the events-import; the controller maps `request.vendors()` into `ApprovedVendor` records directly.
- `test.ProductTest` — `new ApprovedVendor(...)` constructor calls; the events-import stays (test asserts on the emitted event class).

After: only `Product` (emitter) and `ApprovedVendorListChanged` (definition) and `ProductTest` (asserts the emitted event) import the event class. Controller, repository port, JDBC impl, and application service are event-import-free, matching the rest of the codebase.

### Verification

- `mvn clean install -DskipTests` green across all 12 modules.
- `mvn -pl product-service test` green — 57 tests, including the 3 `ApprovedVendorList` nested tests in `ProductTest`. No production code path changed semantics; this was a pure type-relocation.
- Final import audit: `grep -rn "events.ApprovedVendorListChanged" product-service/**/*.java` returns 3 hits — `Product.java` (emitter), `ProductTest.java` (test asserts the emitted type), `ProductService.java` (Javadoc fully-qualified link). All legitimate.

### Follow-ups

- None remaining for §2.7.
- Possible future polish: same VO-extraction pattern could apply to other event-class nested records if any grow into transport-shaped types. None do today; flag if a new event introduces a nested record that gets used outside the event class itself.

---

## 2026-05-08 — §2.1 (first bullet): open_purchase_orders_count on production_planning_board

Closes the long-standing 0 on `production_planning_board.open_purchase_orders_count`. Shortage-driven POs now propagate their source work-order id through to reporting's `purchase_order_tracking_view`, which reporting handlers query whenever a PO event arrives to recompute the open count and write it to the planning board.

### Wire shape

`PurchaseRequisition` already carried `sourceWorkOrderId` (set when a `RawMaterialShortageDetected` triggers PR auto-creation). The slice extends the chain so the field flows all the way to reporting:

1. **`purchasing.PurchaseOrder.fromRequisition`** factory takes a new `sourceWorkOrderId` parameter (read from `pr.sourceWorkOrderId()` in `PurchaseOrderService.convertFromRequisition`). Null for manual PRs that aren't shortage-driven.
2. **`purchasing.events.PurchaseOrderCreated`** record gains `UUID sourceWorkOrderId` between `purchaseRequisitionHeaderId` and `currencyCode`. Additive nullable — Jackson 3 default deserialisation tolerates the new field on existing consumers (finance + reporting/atp + reporting/dashboard + reporting/shortage handlers don't read it; only reporting/po-tracking does).
3. **`reporting.purchase_order_tracking_view`** gets a `source_work_order_id UUID NULL` column via Liquibase changeset `2026-05-08-add-source-work-order-id-to-po-tracking.sql`. Idempotent (`ADD COLUMN IF NOT EXISTS`).
4. **`reporting.PurchaseOrderTrackingProjection`** — `createFromPurchaseOrder` accepts the new field (writes it on INSERT; ON CONFLICT clause uses `COALESCE(EXCLUDED.source_work_order_id, ...)` so a stub-row write doesn't blank a prior populated value). Two new query methods: `findSourceWorkOrderForPo(poId): Optional<UUID>` and `countOpenForWorkOrder(woId): int`.
5. **`reporting.ProductionPlanningProjection`** — new `setOpenPoCount(woId, count, occurredAt)` method. Order-tolerant `INSERT … ON CONFLICT` so a PO event arriving before the WO row is created leaves a stub that the late `WorkOrderCreated` upsert backfills.
6. **Three reporting handlers** wire the recompute: `PurchaseOrderCreatedHandler` (po-tracking variant; uses `payload.sourceWorkOrderId()` directly), `GoodsReceivedHandler`, `SupplierPaymentMadeHandler` (look up via `findSourceWorkOrderForPo` since they only know the PO id).

### What "open" means

`po_status NOT IN ('received', 'paid', 'cancelled')`. The count for a given WO updates on three events:
- **PurchaseOrderCreated** — increment when the new PO carries `sourceWorkOrderId`.
- **GoodsReceived** — recompute when the PO transitions to `'received'` (count drops) or back to `'partially_received'`.
- **SupplierPaymentMade** — recompute when the PO transitions to `'paid'` (the terminal state for a settled PO; count drops).

PurchaseOrderCancelled would also recompute, but that event doesn't exist today (purchasing has no PO cancellation path); add the handler when it does.

### Verification

- `mvn clean install -DskipTests` green across all 12 modules.
- `mvn test` green across the 9 services touched (`PurchaseOrderTest`'s 14 calls to `fromRequisition` updated to pass `null` for `sourceWorkOrderId` — mechanical; covers the test contract that null is allowed for non-shortage flows).
- Smoke not yet run against fresh DB volume — natural moment is the next Demo 5.2 walkthrough (raw-material shortage path) where shortage-driven POs auto-create. The Liquibase changeset is `IF NOT EXISTS`-idempotent; existing volumes upgrade cleanly.

### Follow-ups

- **Smoke verification** — drive Demo 5.2 through to PO creation, then `SELECT open_purchase_orders_count FROM reporting.production_planning_board WHERE work_order_id = ?` — should read 1 immediately after PO creation, drop to 0 once goods receipt + payment posts. Worth confirming on the next end-to-end run.
- **PO cancellation handler** — when purchasing grows a `PurchaseOrderCancelled` event (or status flips via some other path), add a corresponding reporting handler that calls `findSourceWorkOrderForPo + setOpenPoCount`. Not needed today.
- **Consumer payload backfill** — finance / reporting-atp / reporting-dashboard / reporting-shortage `PurchaseOrderCreatedPayload` records don't include `sourceWorkOrderId` (they don't read it; Jackson 3 ignores). Optional to align them for consistency in a later cleanup pass.

---

## 2026-05-08 — §2.11: Master Data sidebar module (Products entry)

Trivial nav fix. `erp-web-ui` already had `/products` (list) and `/products/:id` (detail with pricing / reorder / make-vs-buy / discontinue dialogs) wired in `App.tsx`, but `Sidebar.tsx`'s `MODULES` array had no entry pointing to them — the screens were reachable only by typing the URL.

Added a new **Master Data** module between `Home` and `Sales`, icon `BookOpen`, with one item: `Products → /products`. Sits at the top of the rail (data of record before the operational modules that consume it) and scales naturally if UoMs / Currencies / Warehouses / Customers / Suppliers ever get wired forms.

Not under **System** because that module is for sysadmin concerns (Users, Audit Log). Catalogue management is Emma's persona — `catalog_manager`, a business user, not `sysadmin` — so the rail-level grouping reflects the access boundary the role gates already enforce.

`Sidebar.tsx` only — `BookOpen` icon import + 7-line module entry.

---

## 2026-05-08 — §1.4 Slice D: security demo polish + audit log viewer

Closes §1 (Security + Operational UI demo). Audit log + persona switcher + 403 tooltips + demo-script rewrite, all in one push.

### Audit endpoint per service (shared-infrastructure)

- `shared-infrastructure/.../audit/AuditEntry.java` — wire shape: `{outboxMessageId, sequenceNumber, sourceService, aggregateType, aggregateId, eventType, actorUserId, correlationId, occurredAt}`. No payload — privacy + size.
- `shared-infrastructure/.../audit/JdbcAuditQueryAdapter.java` — service-agnostic SQL against unqualified `outbox_message`; the per-service `search_path` resolves to the local table. Filterable by `aggregateId` / `from` / `to` / `limit` (default 200, capped at 1000). `sourceService` is sourced from `spring.application.name` with the `-service` suffix stripped.
- `shared-infrastructure/.../audit/AuditController.java` — `GET /api/audit?aggregateId=&from=&to=&limit=`. Authenticated (any user — auditor included) per the existing OAuth2 filter chain.
- `shared-infrastructure/.../audit/AuditAutoConfiguration.java` registered in `META-INF/spring/...AutoConfiguration.imports`. All 7 services pick it up uniformly.

### BFF aggregator (`erp-web-ui-bff`)

- `AuditAggregatorController.java` at `/api/audit` (more specific than `ProxyController`'s `/api/**`, so Spring's mapping precedence picks it). Fans out to all 7 services in parallel via `HttpClient.sendAsync`, merges by `occurredAt` desc, returns one timeline. Forwards the OIDC session's Bearer token onto each upstream request.
- Failure tolerance: per-service errors are logged at DEBUG and that service contributes an empty list — partial timeline is preferred over a 500 because one service is slow.

### UI viewer + nav (`erp-web-ui`)

- `routes/system/AuditLog.tsx` — table view with timestamp / service chip / event type / aggregate (type + id) / actor. Filterable by aggregate id query param; sidebar entry **System → Audit Log** (was a placeholder). The route was a `Placeholder` in App.tsx → real component now.
- "View audit" action button on `SalesOrderDetail` linking to `/system/audit-log?aggregateId=<id>` for the demo's centerpiece flow. Same pattern can be added to PO detail / WO detail / journal entry detail when they need it.
- Per-row "system" placeholder for actor when `actor_user_id` is null (saga-driven events).

### Role-aware UI (`UserContext` + `ActionButton`)

- New `lib/UserContext.tsx` — React provider that fetches `/api/me` once on mount and exposes `{ me, hasRole(role) }` to descendants. Wraps the app from `main.tsx`.
- `ActionButton` now consumes the context: when `requiresRole` is set and the user lacks the role, renders disabled with tooltip `"Requires role: <role>"`. Existing per-button `requiresRole` annotations from Slice C (added speculatively, tooltip-only at the time) light up automatically — 16 buttons across product / sales / purchasing / manufacturing / finance routes.
- `AppBar` switched from its own `/api/me` fetch to the shared context. No double-load.
- Permissive while loading: until `/api/me` resolves, buttons render enabled (so first paint isn't all-disabled).

### Persona switcher (AppBar dropdown)

- 13-persona dropdown in `AppBar.tsx` next to the user chip. On select: `POST /logout` then `window.location = /oauth2/authorization/keycloak?login_hint=<username>`. Keycloak's login form opens with the username pre-filled; user types the password (== username in demo realm), continues. Fully seamless ROPC server-side switching deferred — would require enabling `directAccessGrantsEnabled` on the BFF client + a dedicated `/api/dev/switch-persona` endpoint that does ROPC and rebuilds the session, ~1 day of plumbing for ~1 second of UX gain.
- Current persona is highlighted as disabled in the dropdown so accidental self-re-login doesn't flash a logout cycle.

### demo-script.md updates

- New "13 demo personas" table near the top, immediately after the Keycloak setup mention. Each persona named with their roles + canonical actions.
- §4.1 (cancel order): reframed as a security-demo moment. Sarah hovers Cancel → tooltip "Requires role: sales_manager" → switch to sales-mgr → succeeds. Curl example shows the 403-vs-200 split.
- New **Demo 8 — Security: roles, audit, and persona-aware UI** at the end (5 sub-sections: login flow, role-gated cancel, audit timeline, finance reverses journal, acceptance criteria). 3-4 minute walkthrough that drops into Demo 7 cleanly.
- One-time setup updated to include `keycloak` in the `docker compose up -d` line.

### Verification

- `mvn clean install -DskipTests` green across all 12 modules.
- Full unit-test suite green across all 8 services + shared-infrastructure.
- No TypeScript/Vite check run from this session — the components compile shape-wise but a `npm run dev` against the actual ERP stack would catch any runtime/JSX issues. User to verify in the smoke run below.

### Smoke checklist (run by the user)

```powershell
docker compose down -v
docker compose up -d postgres kafka keycloak
mvn clean install -DskipTests
# Boot product + sales + finance + reporting + erp-bff + erp-web-ui
mvn -pl product-service spring-boot:run                                # 8081
mvn -pl sales-service spring-boot:run                                  # 8082
mvn -pl finance-service spring-boot:run                                # 8086
mvn -pl reporting-service spring-boot:run                              # 8087
mvn -pl erp-web-ui-bff spring-boot:run                                 # 8089
cd erp-web-ui ; npm run dev                                            # 5174
```

In the browser:

1. Visit `http://localhost:5174` cold → Keycloak login. Sign in as `sarah` / `sarah`. Top-right shows "Sarah Sales · sales_clerk".
2. Open a sales order detail. Hover **Cancel order** → tooltip "Requires role: sales_manager", button disabled.
3. Click **Switch persona** (top-right) → **Sales Manager**. Logged out; Keycloak shows username `sales-mgr` pre-filled. Type `sales-mgr` for password. Now Cancel is enabled.
4. Cancel the order. Click **View audit**. Timeline shows the placement (actor=`sarah`) above the cancellation (actor=`sales-mgr`).
5. Switch to **Auditor** / `auditor`. Read every screen — works. Try any action button — disabled with tooltip.
6. Switch to **Daniel** (finance_manager). Reverse a journal. Audit log shows the reverse with actor=`daniel`.

### Follow-ups

- **Seamless ROPC persona switching** — would require enabling `directAccessGrantsEnabled` on the `northwood-bff` client + a `/api/dev/switch-persona` endpoint that runs ROPC server-side, validates the response, and rebuilds the `OAuth2AuthorizedClient` + `OAuth2AuthenticationToken` in the existing session. ~1 day of plumbing. Worth it only if the demo's "type the password" beat starts feeling slow.
- **Audit "View audit" link** is on SalesOrderDetail only today. Adding the same link to PO / WO / JournalEntry / SupplierInvoice detail pages is ~5 minutes each; pull forward when a demo step calls for it.
- **Audit log on the demo BFF** isn't wired (it's only on `erp-web-ui-bff`). The technical-demo SPA stays anonymous per dev-todo §1; if the technical demo wants the audit feed, mirror `AuditAggregatorController` to `demo-web-ui-bff`.
- **The audit endpoint exposes outbox metadata, not the payload.** A future "expand row" UX showing the JSON payload would need a new endpoint variant or query flag — payload includes things like prices and customer names that may need redaction depending on the auditor's clearance.
- **`ActionButton` permissive-while-loading.** First paint, before `/api/me` resolves, all `requiresRole` buttons render enabled. The race window is the BFF's /api/me round-trip (typically <100ms). If a click slips through, the underlying API call still 403s — the gate is enforcement, not the UI hint. Acceptable today; if it gets noticed, change the default to disabled-and-loading.
- **§1 Security + Operational UI demo is COMPLETE.** All four slices (A, B1+B2, C, D) shipped. Next priority is open: pick from §2 (polish) or a new direction.

---

## 2026-05-08 — §1.2 Slice B2: actor stamping (envelope + repository + reporting)

Closes the audit-trail half of Slice B. Architectural shift locked 2026-05-08: actor lives at the **envelope/repository layer**, not on aggregates. Aggregates stay clean (no `actor` parameter on factories or mutation methods); repositories read `CurrentUserAccessor` and stamp `created_by` / `last_modified_by` on aggregate header rows + `actor_user_id` on the outbox row. The wire envelope carries the actor to consumers; reporting projects it as `last_modified_by` on `sales_order_360_view` and `purchase_order_tracking_view`.

The decision flipped because (a) treating actor as audit metadata, not domain semantics, fits stronger DDD layering (domain doesn't know about users); (b) ~6h of work vs ~8h with no aggregate-test churn; (c) the demo outcome is identical.

### Schema (Liquibase changesets per service, idempotent against v3.sql baseline)

15 aggregate header tables get `created_by VARCHAR(64) NULL` + `last_modified_by VARCHAR(64) NULL`:

- product: `product`
- sales: `sales_order_header`, `customer`
- inventory: `stock_reservation_header`, `goods_receipt_header`, `shipment_header`
- manufacturing: `work_order`, `bom_header`
- purchasing: `purchase_requisition_header`, `purchase_order_header`, `supplier_product_price`
- finance: `customer_invoice_header`, `supplier_invoice_header`, `payment`, `journal_entry_header`

7 outbox tables get `actor_user_id VARCHAR(64) NULL`. 2 reporting tables get `last_modified_by`:

- `reporting.sales_order_360_view`
- `reporting.purchase_order_tracking_view`

All columns nullable forever (locked 2026-05-08). Seed-data rows stay NULL; saga-driven mutations stay NULL. Files:

- `product-service/src/main/resources/db/changelog/changes/2026-05-08-add-actor-audit-columns.sql`
- `sales-service/src/main/resources/db/changelog/changes/2026-05-08-add-actor-audit-columns.sql`
- `inventory-service/src/main/resources/db/changelog/changes/2026-05-08-add-actor-audit-columns.sql`
- `manufacturing-service/src/main/resources/db/changelog/changes/2026-05-08-add-actor-audit-columns.sql`
- `purchasing-service/src/main/resources/db/changelog/changes/2026-05-08-add-actor-audit-columns.sql`
- `finance-service/src/main/resources/db/changelog/changes/2026-05-08-add-actor-audit-columns.sql`
- `reporting-service/src/main/resources/db/changelog/changes/2026-05-08-add-last-modified-by.sql`

Each file uses `ALTER TABLE IF EXISTS ... ADD COLUMN IF NOT EXISTS ...` so the changeset no-ops cleanly on a fresh DB once `v3.sql` is rebaked. v3.sql rebake deferred — captured below.

### Envelope + outbox

- `EventEnvelope` (record) gains `actorUserId` between `causationId` and `occurredAt`. Existing 14 test-side constructions updated to pass `null`.
- `OutboxRow` gains `actorUserId` field + accessor. `OutboxRow.pending(...)` factory takes it as required parameter.
- `JdbcOutboxAdapter` SQL: `INSERT` and `SELECT` both include `actor_user_id`.
- `OutboxPublisher` forwards `row.getActorUserId()` onto the `EventEnvelope` it builds before publishing.
- Result: a Kafka consumer's `@KafkaListener` receives an envelope where `envelope.actorUserId()` is the user who triggered the originating event (or `null` for saga/system writes).

### Repository-side stamping (11 JdbcXxxRepository)

Every aggregate repository now injects `CurrentUserAccessor` and reads `currentUser.currentUsername().orElse(null)` once per `save(...)`:

- INSERT path: writes `created_by` + `last_modified_by` (both = actor on first persist).
- UPDATE path: writes `last_modified_by`.
- Outbox INSERT: writes `actor_user_id`.

Repos updated: `JdbcProductRepository`, `JdbcSalesOrderRepository`, `JdbcStockReservationRepository`, `JdbcGoodsReceiptRepository`, `JdbcShipmentRepository`, `JdbcWorkOrderRepository`, `JdbcBomEditRepository`, `JdbcPurchaseRequisitionRepository`, `JdbcPurchaseOrderRepository`, `JdbcSupplierProductPriceRepository`, `JdbcSupplierInvoiceRepository`, `JdbcCustomerInvoiceRepository`, `JdbcPaymentRepository`, `JdbcJournalEntryRepository`. (14 files — `JdbcBomEditRepository` and `JdbcSupplierProductPriceRepository` don't write to outbox themselves; they only stamp the aggregate row.)

### Direct OutboxRow.pending callers (CQRS-style emitters that don't go through an aggregate repo)

`OutboxRow.pending(...)` requires `actorUserId` now, so the 8 direct call sites either inject `CurrentUserAccessor` (user-driven) or propagate from the inbound envelope (inbox handler) or pass `null` (saga-driven):

- `SupplierProductPriceService.appendOutbox` — injects `CurrentUserAccessor`.
- `WorkOrderPrioritisationService.setPriority` — injects `CurrentUserAccessor`.
- `WorkOrderOperationService.completeOperation` (and the `SubAssembliesConsumed` emit) — injects `CurrentUserAccessor`.
- `ManufacturingRequestedHandler.appendOutbox` — propagates `envelope.actorUserId()`.
- `RawMaterialsReservedHandler.emitShortageDetected` — propagates `envelope.actorUserId()`.
- `WorkOrderCancellationService` — `null` actor (saga-driven via inbox handler; propagation through the service signature is a B2 follow-up).
- `StockReservationService` (cancel ack) — `null` actor (saga-driven; same follow-up).
- `SagaCompensationCompletionService` — `null` actor (pure saga finishing step).

### Reporting projections

- `SalesOrder360Projection` interface: every method gains `String actorUserId` parameter.
- `JdbcSalesOrder360Projection`: each upsert SQL adds `last_modified_by` to the INSERT clause; the ON CONFLICT DO UPDATE clause uses `last_modified_by = COALESCE(EXCLUDED.last_modified_by, sales_order_360_view.last_modified_by)` so a system-driven event with NULL actor doesn't blank a prior user-driven update. 6 handlers updated to pass `envelope.actorUserId()`: `SalesOrderPlacedHandler`, `SalesOrderCompensatedHandler`, `WorkOrderManufacturingCompletedHandler`, `ShipmentPostedHandler`, `CustomerInvoiceCreatedHandler`, `CustomerPaymentReceivedHandler`.
- `PurchaseOrderTrackingProjection` + `JdbcPurchaseOrderTrackingProjection`: same pattern. 4 handlers updated: `PurchaseOrderCreatedHandler`, `GoodsReceivedHandler`, `SupplierInvoiceApprovedHandler`, `SupplierPaymentMadeHandler`.

### Verification

- `mvn clean install -DskipTests` green across all 12 modules.
- Full unit-test suite green across all 8 services + shared-infrastructure (the `KeycloakRealmRoleConverterTest` from B1 still passes; nothing regressed). 14 test-side `EventEnvelope` constructions updated mechanically (all passing `null` actor since the test fixtures don't simulate authenticated requests).

### Smoke checklist (run by the user)

```powershell
docker compose down -v ; docker compose up -d postgres keycloak kafka
mvn clean install -DskipTests
# Boot at minimum product + sales + reporting; finance for the Daniel-reverses demo.
mvn -pl product-service spring-boot:run        # 8081
mvn -pl sales-service spring-boot:run          # 8082
mvn -pl finance-service spring-boot:run        # 8086
mvn -pl reporting-service spring-boot:run      # 8087
mvn -pl erp-web-ui-bff spring-boot:run         # 8089
cd erp-web-ui ; npm run dev                    # 5174
```

Then in the browser:

1. Login as `emma` / `emma`. Edit a product price → 200. Open psql or a query: `SELECT sku, last_modified_by FROM product.product WHERE last_modified_by = 'emma';` should show the row.
2. Login as `sarah`. Place a sales order. `SELECT order_number, created_by, last_modified_by FROM sales.sales_order_header WHERE created_by = 'sarah';` shows the new row.
3. Login as `sales-mgr`. Cancel the order. The same row's `last_modified_by` flips to `sales-mgr`.
4. `GET /api/sales-orders/{id}/360` (reporting view) should show `last_modified_by = "sales-mgr"`.
5. Login as `daniel`. POST a journal reversal. `SELECT journal_number, last_modified_by FROM finance.journal_entry_header WHERE last_modified_by = 'daniel';` shows the reversed entry.
6. Outbox row's actor_user_id flows: `SELECT event_type, actor_user_id FROM sales.outbox_message ORDER BY sequence_number DESC LIMIT 5;` should show actor populated for user-driven events.

### Follow-ups

- **`v3.sql` rebake deferred.** The Liquibase changesets are idempotent against the current v3.sql (uses `IF NOT EXISTS`), so existing + fresh DBs both end up with the columns. v3.sql rebake is a separate slice for cleanliness — pull forward whenever the next structural change to v3.sql lands so they batch.
- **Saga-driven actor propagation (3 sites).** `WorkOrderCancellationService.cancelForSalesOrder`, `StockReservationService` (cancel ack), and `SagaCompensationCompletionService` currently pass `null` actor. The originating actor *is* present in the inbound envelope; threading it through (e.g. `cancelForSalesOrder(salesOrderHeaderId, reason, actorUserId)`) is mechanical but invasive. Holds because the demo path doesn't need it (sales-mgr's cancel directly flows actor on `SalesOrderRepository.save` already; the saga-side acks just confirm completion). ~1h slice if/when the audit-log viewer needs it.
- **Aggregate-side actor remains a possible future refactor.** Today's repository-layer stamping is fine for "audit metadata". If the domain ever needs to invariant-check based on actor (e.g. "self-approval not allowed: creator can't be the approver"), promote actor onto the aggregate then. Not on the radar today.
- **No application-layer tests for actor flow yet.** §2.5 Phase A tests would naturally absorb a "save() with mocked CurrentUserAccessor returning emma → assert outbox row's actor = emma" assertion per repository — but that requires testing `Jdbc*Repository`, which means Testcontainers (Phase C). Holds.
- **Reporting projection actor blanking.** The `COALESCE(EXCLUDED.last_modified_by, target.last_modified_by)` pattern preserves user-driven actors against subsequent system writes. Audit-log viewer (Slice D) should still consume from the **outbox event log**, not from these aggregated views, since the view only captures the *latest* actor — Slice D needs full history.

---

## 2026-05-08 — §1.2 Slice B1: per-endpoint authorization (realm-role mapping + @PreAuthorize)

Slice B was split (locked 2026-05-08): B1 today wires every mutating endpoint with `@PreAuthorize` + a JWT converter that surfaces Keycloak realm roles as Spring authorities; B2 (next) adds actor-stamping columns + event payloads + reporting projection columns.

### Realm-role mapping

Spring Security's default `JwtAuthenticationConverter` only reads scope-shaped claims (`scope` / `scp`). Keycloak surfaces realm roles under `realm_access.roles`. Without bridging, `@PreAuthorize("hasRole('catalog_manager')")` always fails on a perfectly valid Keycloak JWT.

- `shared-infrastructure/.../security/KeycloakRealmRoleConverter.java` — wraps the default `JwtAuthenticationConverter`. Pulls `realm_access.roles`, prefixes each with `ROLE_`, concatenates onto the scope-derived authorities. So a token carrying `realm_access.roles = ["catalog_manager"]` + `scope = "openid profile email"` produces authorities `{ROLE_catalog_manager, SCOPE_openid, SCOPE_profile, SCOPE_email}`.
- `OAuth2ResourceServerSecurityConfig` rewires its filter chain to `.jwt(jwt -> jwt.jwtAuthenticationConverter(new KeycloakRealmRoleConverter()))` and adds `@EnableMethodSecurity` so `@PreAuthorize` is processed.
- 6 unit tests in `KeycloakRealmRoleConverterTest`: prefix-shape match, scope coexistence, missing claim, empty list, principal-name fall-through to `sub`. All green.

### @PreAuthorize across 32 mutating endpoints (6 services)

| Service | Endpoint | Role |
|---|---|---|
| product | POST /api/products | catalog_manager |
| product | PUT /api/products/{id}/sales-price | catalog_manager |
| product | PUT /api/products/{id}/standard-cost | catalog_manager |
| product | PUT /api/products/{id}/reorder-policy | catalog_manager |
| product | PUT /api/products/{id}/make-vs-buy | catalog_manager |
| product | POST /api/products/{id}/discontinue | catalog_manager |
| product | PUT /api/products/{id}/valuation-class | catalog_manager |
| product | PUT /api/products/{id}/active-bom | catalog_manager |
| product | PUT /api/products/{id}/approved-vendors | catalog_manager |
| sales | POST /api/sales-orders | sales_clerk |
| sales | POST /api/sales-orders/{id}/cancel | sales_manager |
| inventory | POST /api/goods-receipts | warehouse_clerk |
| inventory | POST /api/shipments | warehouse_clerk |
| manufacturing | POST /api/work-orders/{id}/operations/{seq}/complete | production_planner |
| manufacturing | POST /api/work-orders/{id}/operations/{seq}/skip | production_supervisor |
| manufacturing | POST /api/work-orders/{id}/priority | production_planner |
| manufacturing | POST /api/boms | production_planner (class-level) |
| manufacturing | POST /api/boms/{bomHeaderId}/lines | production_planner (class-level) |
| manufacturing | DELETE /api/boms/lines/{bomLineId} | production_planner (class-level) |
| manufacturing | POST /api/boms/{bomHeaderId}/activate | production_planner (class-level) |
| purchasing | POST /api/purchase-orders/{id}/approve | purchasing_manager |
| purchasing | POST /api/purchase-requisitions | purchasing_clerk |
| purchasing | PUT /api/supplier-product-prices | purchasing_manager |
| finance | POST /api/supplier-invoices | accountant |
| finance | POST /api/supplier-invoices/{id}/manual-approve | finance_manager |
| finance | POST /api/supplier-invoices/{id}/reject | finance_manager |
| finance | POST /api/journal-entries/{id}/reverse | finance_manager |
| finance | POST /api/journal-entries/reverse-by-source | finance_manager |
| finance | POST /api/payments | accountant |
| finance | POST /api/payments/customer | accountant |
| finance | POST /api/payments/multi | accountant |
| finance | POST /api/payments/customer/multi | accountant |

Reporting is GET-only — no `@PreAuthorize` needed. `anyRequest().authenticated()` from the shared filter chain (Slice A) already gates them at "any authenticated user", which includes `auditor`.

The realm seed (Slice A) gives manager-tier users both their own role and the underlying clerk role — `sales-mgr` carries both `sales_manager` and `sales_clerk`, so `@PreAuthorize("hasRole('sales_clerk')")` matches without needing `hasAnyRole(...)`. Promotion is one direction (manager can do clerk things), never the reverse.

### CurrentUserAccessor wired (consumed by B2)

- `shared-infrastructure/.../security/CurrentUserAccessor.java` — exposes `Optional<String> currentUsername()`. Returns the Keycloak `preferred_username` claim from the JWT principal, falling back to `Authentication.getName()` (the `sub` UUID) for non-JWT auth. `Optional.empty()` for actuator-allow-list paths and for non-HTTP threads (outbox publisher, saga worker, Liquibase). Registered as a `@Bean` from `OAuth2ResourceServerSecurityConfig` — not `@Component`, because shared-infrastructure isn't auto-scanned by services (the user-level CLAUDE.md gotcha).
- B1 doesn't consume it yet — that's B2's job. Wiring it now keeps the next slice's footprint focused on the schema + aggregate changes.

### Verification

- `mvn clean install -DskipTests` green across all 12 modules.
- `mvn -pl shared-kernel,product-service,sales-service,inventory-service,manufacturing-service,purchasing-service,finance-service,reporting-service test` + `mvn -pl shared-infrastructure test` — all green. shared-infrastructure picked up the 6 new `KeycloakRealmRoleConverterTest` tests.

### Smoke checklist (run by the user)

```powershell
docker compose down -v ; docker compose up -d postgres keycloak kafka
mvn clean install -DskipTests
# Boot product-service + sales-service at minimum (other services optional for the role demos)
mvn -pl product-service spring-boot:run        # 8081
mvn -pl sales-service spring-boot:run          # 8082
mvn -pl erp-web-ui-bff spring-boot:run         # 8089
cd erp-web-ui ; npm run dev                    # 5174
```

In the browser:

1. Login as `emma` / `emma`. Hit "Edit pricing" on a product → 200, price updates. (catalog_manager has the role.)
2. Logout. Login as `sarah` / `sarah`. Try "Edit pricing" → expect 403. (sales_clerk lacks catalog_manager.)
3. Login as `sarah`. Place a sales order → 200. (sales_clerk has the role.)
4. Try `POST /api/sales-orders/{id}/cancel` (curl with sarah's session cookie) → 403.
5. Logout, login as `sales-mgr` / `sales-mgr`. Cancel the same order → 200. (sales_manager has the role.)
6. Login as `auditor` / `auditor`. Read endpoints all work; any mutating endpoint → 403.

Or via curl with a token (via `curl -d 'grant_type=password&...' http://localhost:8090/realms/northwood/protocol/openid-connect/token` against client `northwood-bff`):

```
curl -H "Authorization: Bearer $TOKEN" -X POST http://localhost:8081/api/products/.../discontinue
```

### Follow-ups

- **B2 ready to start.** Schema migrations (created_by/last_modified_by columns), aggregate factory + mutation method signatures gain `actor`, event payloads gain `actorUserId`, reporting projections (sales_order_360, purchase_order_tracking) project `last_modified_by`. Per-aggregate work — natural to ship in two halves (write-side first, projection-side second) but bundling is fine for one focused push.
- **Standard-cost endpoint role unsettled.** Today PUT /api/products/{id}/standard-cost is `catalog_manager`. Once §2.8 Slice B (finance standard-cost projection) lands, finance has a real stake — promote to `hasAnyRole('catalog_manager','finance_manager')` if the demo wants Daniel editing cost. Captured here so it doesn't get lost.
- **No application-layer or controller-layer @PreAuthorize tests yet.** §2.5 Phase A planned controller-level tests would naturally absorb an "annotation-asserting" test per controller (assertion: `getAnnotation(PreAuthorize.class).value()` matches expected role). Worth ~1 day when §2.5 picks up. Without it, accidentally removing `@PreAuthorize` would compile clean and silently dispatch unauthorized.
- **403 storytelling pending.** Today an unauthorized request gets a plain 403 with no body. Slice D's "tooltip on disabled action button" lands the demo polish. The backend doesn't need to change for Slice D — Slice D adds frontend role-awareness.

---

## 2026-05-08 — §1.1 Slice A: Keycloak realm + OAuth2 wiring

Every service + the ERP BFF now require a valid Keycloak JWT on every endpoint (allow-list: `/actuator/health`, OpenAPI, Swagger UI). No role enforcement yet — Slice B picks that up. The session model is option (a) per dev-todo §1: the BFF holds the access token in a server-side session and forwards it as Bearer on each upstream call; tokens never reach the browser.

### Keycloak realm + docker-compose

- `db/keycloak/northwood-realm.json` — realm `northwood` with 13 realm roles (catalog_manager, sales_clerk, sales_manager, warehouse_clerk, warehouse_manager, production_planner, production_supervisor, purchasing_clerk, purchasing_manager, accountant, finance_manager, auditor, sysadmin) and 13 demo users (one per role, username = password):
  - emma → catalog_manager; sarah → sales_clerk; sales-mgr → sales_manager + sales_clerk; mike → warehouse_clerk; warehouse-mgr → warehouse_manager + warehouse_clerk; linda → production_planner; production-sup → production_supervisor + production_planner; tom → purchasing_clerk; purchasing-mgr → purchasing_manager + purchasing_clerk; olivia → accountant; daniel → finance_manager + accountant; auditor → auditor; sysadmin → sysadmin.
  - Manager-tier users carry both their tier and the underlying clerk role so Slice B's `@PreAuthorize("hasRole('sales_clerk')")` checks hit on a sales-mgr request without an explicit OR.
- Two clients: `northwood-bff` (confidential, secret `northwood-bff-secret`, redirect `http://localhost:8089/login/oauth2/code/keycloak`) + `northwood-spa` (public PKCE, reserved for a future direct-flow swap).
- `docker-compose.yml` — Keycloak 26.0 on host port 8090, dev mode, realm import on first start, named volume `northwood-keycloakdata`. Bootstrap admin = `admin` / `admin`.

### Service-side resource-server

- `shared-infrastructure/pom.xml` — `spring-boot-starter-oauth2-resource-server` added once; all 7 services pick it up transitively.
- `shared-infrastructure/.../security/OAuth2ResourceServerSecurityConfig.java` — `@AutoConfiguration` registered in `META-INF/spring/...AutoConfiguration.imports`. Conditional on `spring.security.oauth2.resourceserver.jwt.issuer-uri` so existing unit tests that boot without the property continue to work. Filter chain: stateless session, CSRF disabled, JWT validation, `permitAll` on `/actuator/health[/**]`, `/actuator/info`, `/v3/api-docs`, `/v3/api-docs/**`, `/v3/api-docs.yaml`, `/swagger-ui`, `/swagger-ui/**`, `/swagger-ui.html`; everything else `authenticated()`.
- All 7 service `application.yml` files: `spring.security.oauth2.resourceserver.jwt.issuer-uri = ${KEYCLOAK_ISSUER_URI:http://localhost:8090/realms/northwood}`.

### ERP BFF: OAuth2 client + token forwarding

- `erp-web-ui-bff/pom.xml` — `spring-boot-starter-oauth2-client` only (no resource-server starter — option (a) means the BFF only accepts session-cookie traffic).
- `erp-web-ui-bff/src/main/resources/application.yml` — `spring.security.oauth2.client.registration.keycloak` (client-id `northwood-bff`, client-secret env-overridable) + provider `keycloak` issuer-uri.
- `BffSecurityConfig` (`security/`) — separate filter chain since the BFF doesn't depend on shared-infrastructure. `oauth2Login(Customizer.withDefaults())` for the OIDC code flow, `/logout` invalidates the session, CSRF disabled. Two unauthenticated-request strategies via `defaultAuthenticationEntryPointFor`: `/api/**` returns 401 (so the SPA's fetch wrapper can detect and redirect — fetch can't follow a 302 to a cross-origin login page); everything else falls back to the default `oauth2Login` redirect.
- `BffAccessTokenAccessor` (`security/`) — pulls the current user's access token out of `OAuth2AuthorizedClientService` keyed on `OAuth2AuthenticationToken.getAuthorizedClientRegistrationId()` + principal name.
- `ProxyController` — constructor takes the accessor, calls `tokens.currentAccessToken()` and stamps `Authorization: Bearer <jwt>` on every upstream `HttpRequest.Builder`. `FILTERED_HEADERS` extended to include `authorization` and `cookie` so the browser's incoming session cookie + any stray Authorization header don't leak upstream.
- `MeController` (`security/`) — `GET /api/me` returns `{ username, fullName, roles }`. Roles surfaced from the JWT's `realm_access.roles` claim (so Slice B's `@PreAuthorize("hasRole('catalog_manager')")` matches what the SPA sees). 401 when no session — trips the SPA redirect.

### SPA login flow + AppBar

- `erp-web-ui/src/lib/api.ts` — `apiGet` / `apiPost` / `apiPut` now `credentials: "include"` (so the JSESSIONID cookie travels) and detect 401, calling `window.location.href = "/oauth2/authorization/keycloak"` to redirect to Spring Security's OIDC entry point. A `redirecting` guard prevents multiple in-flight 401s from stacking redirects.
- `erp-web-ui/vite.config.ts` — proxy now also forwards `/oauth2/*`, `/login/*`, `/logout` to the BFF on :8089 so the OIDC redirect chain hits the same origin as `/api/*`.
- `erp-web-ui/src/components/layout/AppBar.tsx` — fetches `/api/me` on mount; user chip shows `{fullName ?? username}` + first non-default role. New `<form method="post" action="/logout">` with a `LogOut` icon button at the right edge.

### Verification

- `mvn clean install -DskipTests` green across all 12 modules.
- `mvn -pl shared-kernel,product-service,sales-service,inventory-service,manufacturing-service,purchasing-service,finance-service,reporting-service test` green — no pre-existing test broke (the `OAuth2ResourceServerSecurityConfig` is `@ConditionalOnProperty` on issuer-uri, which the test profile doesn't set, so the filter chain doesn't materialize and old behaviour is preserved).
- Spring Security 7 (Boot 4.0.5) gotcha hit: `org.springframework.security.web.util.matcher.AntPathRequestMatcher` is gone. Replacement is `org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher.withDefaults().matcher("/api/**")`. Captured below as a follow-up worth folding into user CLAUDE.md.

### Smoke checklist (run by the user — Docker is up the user, not me)

```powershell
docker compose down -v
docker compose up -d postgres keycloak kafka
# Wait for Keycloak readiness (10–30s); admin console at http://localhost:8090/ (admin/admin).
mvn clean install -DskipTests
# Boot the seven services + ERP BFF (one terminal each, or IntelliJ run config):
mvn -pl product-service spring-boot:run         # 8081
mvn -pl sales-service spring-boot:run           # 8082
# ... and so on for inventory/manufacturing/purchasing/finance/reporting
mvn -pl erp-web-ui-bff spring-boot:run          # 8089
cd erp-web-ui ; npm run dev                     # 5174 → BFF :8089
```

Then in the browser:

1. Visit http://localhost:5174 — should redirect to Keycloak's login page (look for `localhost:8090/realms/northwood` in the URL).
2. Sign in as `emma` / `emma`. Land back on the SPA home; AppBar shows "Emma Catalog" + role `catalog_manager`.
3. Hit `GET /api/products` — should return 200 with the seed products.
4. Hit `GET /api/sales-orders` — should also return 200 (any authenticated user can read in Slice A).
5. Click the logout icon — back to Keycloak login. Hit `/api/products` again — see the SPA bounce back to login.
6. `curl http://localhost:8081/api/products` (no Authorization header) → 401. Add `Authorization: Bearer <token-from-Keycloak>` → 200.

### Follow-ups

- **Spring Security 7 `AntPathRequestMatcher` removal** is a Boot-4 / Security-7 gotcha worth folding into the user-level CLAUDE.md alongside the existing "Jackson 3 is the default" note. Worth catching the next time a Boot 4 service needs a security config.
- **Demo BFF stays anonymous on purpose** — the demo SPA tells the technical-architecture story (Saga Console, event drawer) and isn't in scope for the security demo. If we ever want to demo "anonymous user can read a public catalogue", that's where it'd land.
- **Per-row authorization** (e.g. "salespeople only see their own customers") explicitly out of scope per Slice B § dev-todo §1.2 won't-touch list. Leave for a much later slice if any demand surfaces.
- **Keycloak admin console exposure** — currently anyone with `localhost:8090` can hit it via admin/admin. Fine for laptop, never production. Slice D's audit-log viewer might want a stricter "internal only" story.
- **§1 Slice B ready to start** — every endpoint is now authenticated, so attaching `@PreAuthorize("hasRole('sales_manager')")` to `POST /api/sales-orders/{id}/cancel` etc. is the natural next move. Realm roles are already surfaced in the JWT under `realm_access.roles`, but Spring Security's default JwtAuthenticationConverter maps only scope claims to authorities — Slice B will need a small `JwtAuthenticationConverter` that pulls `realm_access.roles` and prepends `ROLE_` so `hasRole(...)` works. Add that to the shared SecurityConfig.

---

## 2026-05-07 — §2.10 + §2.8 Slice A + §2.9: rename ProductService, split pricing event, no-op suppression

Three coordinated refactors landing together because they touch overlapping surface (the product aggregate, its application service, the controller, and the ERP/demo UI dialogs):

### §2.10 — Rename `ProductCatalogService` → `ProductService`

`product-service` was the lone module whose application service didn't follow the `<AggregateName>Service` convention used by ~12 other services. Mechanical rename: class file, Spring bean name, inner `ProductNotFoundException`, two import sites in `ProductController` + field name `catalog` → `service`.

### §2.8 Slice A — Split `ProductPricingChanged` into single-facet events

Replaces one combined event (`ProductPricingChanged` carrying `newSalesPrice` only — cost was silently dropped) with two single-facet events that match the existing style (`MakeVsBuyChanged`, `ReorderPolicyChanged`, etc.). Different stewards and consumers can now subscribe independently, and §2.8 Slice B (finance standard-cost projection) becomes straightforward.

**Wire shape:**
- `product.SalesPriceChanged(productId, oldSalesPrice, newSalesPrice, currencyCode, occurredAt)` — emitted from `Product.changeSalesPrice(...)`.
- `product.StandardCostChanged(productId, oldStandardCost, newStandardCost, currencyCode, occurredAt)` — emitted from `Product.changeStandardCost(...)`.
- `ProductPricingChanged` retired entirely — class deleted.

**API shape:** `PUT /api/products/{id}/pricing` retired; replaced by `PUT /api/products/{id}/sales-price` (body `{salesPrice, currencyCode}`) and `PUT /api/products/{id}/standard-cost` (body `{standardCost, currencyCode}`). Two new request DTOs (`ChangeSalesPriceRequest`, `ChangeStandardCostRequest`); `ChangePricingRequest` deleted.

**Sales-side rename cascade:**
- `ProductPricingChangedHandler` → `SalesPriceChangedHandler` (consumer name unchanged: `sales.product-pricing-projector`).
- `ProductPricingChangedPayload` → `SalesPriceChangedPayload`.
- `ProductPricingProjection` → `SalesPriceProjection` (interface, `application/inbox/`).
- `JdbcProductPricingProjection` → `JdbcSalesPriceProjection` (impl, `infrastructure/persistence/`); method renamed `applyPricing` → `applySalesPrice`. Underlying `sales.product_pricing` table unchanged — no migration needed.

**UI updates** (no separate dialogs yet — that's a later UI polish slice):
- `erp-web-ui/.../ProductDetail.tsx` PricingDialog now issues two PUT calls, gated by `Number(salesPrice) !== Number(product.salesPrice)` etc., so unchanged values don't fire spurious calls.
- `demo-web-ui/.../Products.tsx` PricingPanel mirrors the same shape via new `changeProductSalesPrice` + `changeProductStandardCost` command helpers (`api/commands.ts`); `ChangePricingRequest` type-decl replaced with two single-facet types (`api/types-commands.ts`).

**Comment / doc updates:** v3.sql (×2), sales-side projection changeset comment, `application-kafka.yml` subscribe-topic comment, `manufacturing/.../MakeVsBuyChangedHandler` cross-reference, `user-stories.md` Story 1.2 (acceptance criteria + Trigger + Events), `demo-script.md` Demo B + §1.2 curl block.

### §2.9 — No-op suppression on `change*` aggregate methods

Closes the inconsistency where `Product.changeMakeVsBuy(true, false)` against current state `(true, false)` still emitted `MakeVsBuyChanged`, bumped `version`, and wrote an outbox row. Existing precedent: `SupplierProductPriceService.setPrice` already uses `BigDecimal.compareTo` to suppress no-op duplicates.

**Pattern landed in 8 places** (rejection invariants first, no-op check last):
- `Product.changeSalesPrice` / `changeStandardCost` / `changeMakeVsBuy` / `changeReorderPolicy` / `changeValuationClass` / `activateBom` — aggregate-side.
- `ProductService.setApprovedVendors` — service-side (vendor list lives in a sibling table, not aggregate state); `sameVendorSet` helper does set comparison via the `ApprovedVendor` record's auto-equals.
- `StockItem.applyReorderPolicy` + `inventory.StockItemProjection.applyReorderPolicy` — both sides; the projection-side check is what actually gates `repository.save()`, the aggregate-side is defense-in-depth.

**Equality semantics:** `BigDecimal.compareTo == 0` for numerics (handles scale-insensitive equality — `100.00` ≡ `100.0`). `Money` got a new `Money.equalsByValue(other)` instance method since the record's default `equals` uses `BigDecimal.equals` which is scale-sensitive — using the default would have caused a no-op call with a differently-scaled BigDecimal (e.g. `100` vs `100.00`) to spuriously pass through. `Objects.equals` for nullable UUIDs (`activeBomId`).

**DEBUG logging at the application layer.** Each `ProductService.change*` and `setReorderPolicy` / `setValuationClass` / `activateBom` / `setApprovedVendors` method logs `"... product_id=... ignored — value(s) unchanged ..."` at DEBUG before short-circuiting. Aggregate stays SLF4J-free (preserves the pure-domain rule). Same DEBUG log added to `inventory.StockItemProjection.applyReorderPolicy`. Matches the existing inbox-handler "skipping already-processed" pattern.

**Test coverage added** (in `ProductTest`):
- Per-method `no_op_emits_nothing_when_value_matches` for sales-price, standard-cost, make-vs-buy, reorder-policy, valuation-class, active-bom.
- `no_op_ignores_BigDecimal_scale` for sales-price, standard-cost, reorder-policy.
- `rejection_runs_before_no_op_check` for sales-price, standard-cost.
- `invariant_runs_before_no_op_check` for make-vs-buy, reorder-policy, valuation-class.
- New `MoneyTest$EqualsByValue` nested class (5 cases: same value, scale-insensitive, amount diff, currency diff, null).
- New `MoneyTest$Equality.equals_is_scale_sensitive` documents why `equalsByValue` exists.

### Verification

- `mvn clean install -DskipTests` green across all 12 modules.
- `mvn -pl shared-kernel,product-service,sales-service,inventory-service,manufacturing-service,purchasing-service,finance-service,reporting-service test` green — no pre-existing test broke.
- Aggregate test count for product-service went 31 → 57 (+26 from no-op + scale-insensitive + invariant-ordering coverage); shared-kernel +5 from `EqualsByValue` nested class.
- Smoke not yet run against fresh DB volume — Slice B's finance projection work would be a natural moment to do `docker compose down -v ; docker compose up -d postgres ; mvn -pl product-service spring-boot:run` and confirm v3.sql + new event names line up at boot.

### Follow-ups noted

- **Per-row `version` bump on Slice A's split is unchanged** — calling sales-price + standard-cost from the UI in one user click bumps `version` twice. Fine for now (each call is a distinct API mutation), but if it becomes annoying we can offer a combined-PUT later.
- **Separate edit forms** (per the §2.8 master plan): both UIs still have a single Pricing dialog with both fields. The "two separate dialogs" UI refactor is captured implicitly in §2.8 Slice C/D's UI work.
- **§2.5 / §2.5.1 unit-test coverage**: §2.9 added aggregate-side tests but the new `ProductService` no-op + log behaviour has no service-layer tests (no `ProductServiceTest` exists). When §2.5 Phase A picks up `BomEditService`, it should also cover `ProductService` — the no-op + log paths are easy mocks (mock `ProductRepository`, assert no `save` invocation + log captured via Logback test appender if needed).

---

## 2026-05-06 — Refactor: rename `*ProjectionService` → `*Projection` + move to `application/inbox/`

Tightens the naming convention so the package layout encodes a real architectural distinction: **`*Projection` lives in `application/inbox/` and is event-driven; `*Service` lives in `application/` and is command-side orchestration**. Closes a loose end from the post-Phase-2 review where it became clear that every `*ProjectionService` interface was consumed exclusively by `*Handler` classes — the suffix was misleading and the location understated the coupling.

**What moved:**

- 15 interfaces renamed `*ProjectionService` → `*Projection` and moved from `<service>/application/` to `<service>/application/inbox/`. Sales (2), manufacturing (2), purchasing (3), finance (2), reporting (6).
- 1 concrete class renamed `inventory.application.StockProjectionService` → `inventory.application.inbox.StockItemProjection` (kept concrete — its impl delegates through `StockItemRepository` and has no JDBC of its own, so the standard interface+adapter split adds no value here).
- 15 JDBC impls in `<service>/infrastructure/persistence/` renamed `Jdbc*ProjectionService` → `Jdbc*Projection`. Stayed in the same package.
- ~60 caller files (handlers + handler tests + 2 finance services with same-package usages + a couple of finance-side tests) had imports + class-name references updated.

**Rule baked into CLAUDE.md naming-conventions** (added as a sixth row in the suffix table, with a sharper "*Projection*-only-for-handlers" sentence): a `*Projection` is consumed only by `*Handler` classes in the same `inbox/` package. If any non-handler caller ever needs to access the same table, it goes through a separate class (`*Lookup` for value reads, `*QueryPort` for whole-row reads, `*Writer` for non-event-driven writes). Prevents the projection interface from drifting into a multi-purpose access port over time.

**Implementation note worth remembering:** PowerShell's `Set-Content -Encoding UTF8` writes a UTF-8 **BOM** by default; `javac` rejects the BOM with `illegal character: '﻿'`. The bulk-rename script needed a follow-up pass using `[System.IO.File]::WriteAllBytes` (no BOM) to strip it from 89 java files. For future bulk-edit scripts on Windows: prefer `[System.IO.File]::WriteAllText($path, $content, [System.Text.UTF8Encoding]::new($false))` over `Set-Content -Encoding UTF8`.

**Verification:**
- `mvn install -DskipTests` — all 12 modules SUCCESS.
- `mvn test` — full reactor green.
- `Grep ProjectionService **/*.java` → 0 hits across the entire codebase.
- `application/inbox/*.java` now contains both handlers and projections side-by-side (the visual point); no `application/*ProjectionService.java` files remain.

---

## 2026-05-06 — Refactor: Phase 2 — lift JdbcTemplate out of `BomEditService` + `StockReservationService`

Closes the architectural rule "no JDBC anywhere in `application/`". Phase 2 took the two heavy-JDBC orchestration services Phase 1 deliberately deferred and pushed their state-mutation logic behind ports. Every file under `*.application.*.java` is now `JdbcTemplate`-free.

**BomEditService refactor:**

- New `BomEditRepository` (interface in `domain/`) carrying the editorial CRUD on `bom_header` + `bom_line`: `insertHeader`, `findHeader`, `nextLineNumber`, `insertLine`, `findHeaderIdByLineId`, `deleteLine`, `countLines`, `markActive`, `findComponentProductIds`. `HeaderRow` record carries header identity + status. Sits next to the existing `BomLookup` and `BomCycleDetector` ports — same domain area.
- `JdbcBomEditRepository` (infra/persistence) implements all nine methods.
- `BomEditService` constructor swaps `JdbcTemplate jdbc` for `BomEditRepository edits`. The cycle-detector + business logic (active-BOM-not-editable, draft-only-activate, partial-unique-active enforcement) stay in the application service unchanged.

**StockReservationService refactor:**

- Extended `StockBalanceWriter` (interface in `application/`) with two new methods:
  - `tryReserveOnHand(warehouseId, productId, quantity) → boolean` — atomic conditional UPDATE that bumps `reserved_quantity` only if `on_hand_quantity - reserved_quantity >= quantity`. Returns false when the row doesn't exist or another reservation has consumed the head-room.
  - `releaseReserved(warehouseId, productId, quantity)` — decrement `reserved_quantity` (used by the release / cancel-prior paths).
- New `StockBalanceLookup` (interface in `application/`) — `findAvailableQuantity(warehouseId, productId)` returns `on_hand - reserved`, defaults to 0 when no row. Separate interface keeps the writer as writes-only per convention.
- Extended `StockReservationRepository` (domain) with the lifecycle queries the release flows need: `findActiveHeaderIdForSalesOrder`, `findActiveHeaderIdForWorkOrder`, `findAnyHeaderIdForWorkOrder` (any status — for retry-cancel), `findWarehouseIdForHeader`, `findReservedLines` (returns `ReservedLineSnapshot(productId, reservedQuantity)`), `markReleased`, `deleteHeaderAndLines`. The aggregate is still write-once-immutable; status transitions land directly on the row via these methods.
- `StockReservationService` constructor swaps `JdbcTemplate jdbc` for the four ports (`StockReservationRepository`, `StockBalanceWriter`, `StockBalanceLookup`, `WarehouseLookup`) plus `OutboxPort` for the `SalesOrderCancellationApplied` ack INSERT. The two release paths (`releaseForSalesOrder`, `releaseForWorkOrder`) now share an `unwindReservation(headerId)` helper that loops `findReservedLines` → `releaseReserved` → `markReleased`. The `cancelPriorReservationFor` retry path is the same shape but ends with `deleteHeaderAndLines` instead of `markReleased`.

**Verification:**
- `mvn install -DskipTests` — all 12 modules SUCCESS.
- `mvn test` — full reactor green (sales 86 / inventory 21 / manufacturing 42 / purchasing 34 / finance 56 / reporting 1, plus shared-kernel + shared-infrastructure).
- `Grep JdbcTemplate **/application/**/*.java` → 1 hit, comment-only (`{@code JdbcTemplate}` reference in `SalesOrderShippingService` javadoc). Zero actual imports or usages anywhere in the application layer.

**Architectural rule now mechanically enforceable across the codebase:** every JDBC call lives under `infrastructure/persistence/`. Updated CLAUDE.md naming-conventions to drop the "two exceptions remain" caveat the Phase-1 entry needed.

**Smoke-boot deferred** — pure refactor, no schema or event-shape change. The reservation flows in particular have meaningful behavior (atomic `tryReserveOnHand`, retry-cancel-then-recreate) that unit-test mocks don't fully exercise; a fresh-volume + Kafka run before the next demo would be valuable but isn't blocking.

---

## 2026-05-06 — Refactor: lift JdbcTemplate out of every `application/*Service` (Phase 1)

Extension of the morning's inbox-handler refactor. Same architectural rule — JDBC lives only in `infrastructure/`, not in `application/` — applied to every `*Service` / `*Writer` / `*ProjectionService` that previously held a `JdbcTemplate` field. Twenty-six files refactored across six services; two heavy-JDBC orchestration services (`BomEditService`, `StockReservationService`) deferred to Phase 2.

**Pattern applied:**

- **Pure data-access classes** (~19 files: writers + projection services with no orchestration) → converted to interface in `application/` (same class name preserved so existing imports/Spring wiring keep resolving) + `Jdbc*` impl in `infrastructure/persistence/`.
- **Orchestration services with stray JDBC** (~7 files) → kept the service in `application/`, pushed each stray call behind a small focused collaborator: `WarehouseLookup` for value queries, `StockBalanceWriter.decrementOnHandAndReleaseReserved(...)` for the shipment-side balance write, `WorkOrderRepository.findCompletedChildren(...)` / `findActiveIdsForSalesOrder(...)` for cross-aggregate queries, `OutboxPort.appendPending(...)` for ad-hoc outbox INSERTs, and per-aggregate `findPaymentSnapshot(...)` methods on the invoice repositories for the trigger-maintained `paid_amount` column.

**Newly created collaborators across all services:**

| Service | New interfaces (application) | New JDBC impls (infrastructure) |
|---|---|---|
| sales | (existing 2 services split) | `JdbcSalesOrderHeaderStatusProjectionService`, `JdbcProductPricingProjectionService` |
| inventory | `StockBalanceWriter`, `WipBalanceWriter`, `StockMovementWriter`, `WarehouseLookup` (moved from infra) | `JdbcStockBalanceWriter`, `JdbcWipBalanceWriter`, `JdbcStockMovementWriter`, `JdbcWarehouseLookup` |
| manufacturing | (2 projections split); extended `WorkOrderRepository` with `findCompletedChildren` + `findActiveIdsForSalesOrder` + `CompletedChild` record | `JdbcProductActiveBomProjectionService`, `JdbcProductReplenishmentProjectionService`; `JdbcWorkOrderRepository` extended |
| purchasing | (3 projections split); new `SupplierProductPriceRepository` (domain) + `ExistingPrice` / `PriceRow` records | `JdbcProductApprovedVendorProjectionService`, `JdbcPurchaseOrderReceiptProjectionService`, `JdbcPurchaseOrderPaymentProjectionService`, `JdbcSupplierProductPriceRepository` |
| finance | (2 projections split); extended `SupplierInvoiceRepository` + `CustomerInvoiceRepository` with `findPaymentSnapshot` + `PaymentSnapshot` records | `JdbcProductValuationClassProjectionService`, `JdbcPoLineFactsProjectionService`; `JdbcSupplierInvoiceRepository` + `JdbcCustomerInvoiceRepository` extended |
| reporting | (all 6 projections split) | `JdbcAvailableToPromiseProjectionService`, `JdbcMaterialShortageProjectionService`, `JdbcFinancialDashboardProjectionService`, `JdbcProductionPlanningProjectionService`, `JdbcPurchaseOrderTrackingProjectionService`, `JdbcSalesOrder360ProjectionService` |

**Special cases worth remembering:**

- **`SagaCompensationCompletionService`** (sales): had two stray JDBC calls — outbox INSERT for `SalesOrderCompensated` + a `SELECT cancelled_at FROM sales_order_header` lookup. Refactored to use `OutboxPort.appendPending(...)` and `SalesOrderRepository.findById(...).map(SalesOrder::cancelledAt)` — loading the full aggregate to read one field is fine here (low-throughput compensation completion path).
- **`PaymentService`** (finance): the `SupplierInvoice` / `CustomerInvoice` aggregates don't model `paid_amount` (it's maintained by the `maintain_allocation_totals` DB trigger). Solution: `findPaymentSnapshot(id)` on each invoice repository returning a narrow `PaymentSnapshot` record — repository owns the SQL, application service just consumes the snapshot. Same shape as `WorkOrderRepository.findCompletedChildren`.
- **`SupplierProductPriceService`** (purchasing): six JDBC calls including upsert-with-existence-check + the listing read. Extracted as a full `*Repository` (not `*ProjectionService`) since `supplier_product_price` IS the data of record per CLAUDE.md.

**Architectural rule now enforceable across the codebase:** every JDBC call in the source tree lives under `infrastructure/persistence/` (the only Phase-1-out-of-scope exceptions are `BomEditService` and `StockReservationService`, parked in dev-todo §2.5).

**Verification:**
- `mvn install -DskipTests` — all 12 modules SUCCESS.
- `mvn test` — full reactor green; 86 sales / 21 inventory / 42 manufacturing / 34 purchasing / 56 finance / 1 reporting boot test, plus shared-kernel + shared-infrastructure.
- `Grep JdbcTemplate **/application/*.java` → 2 hits, both Phase 2 candidates as expected.

**Smoke-boot deferred** — pure refactor (no schema change, no event-shape change, no SQL semantics change). Bundle with the next behavioural slice for fresh-volume verification.

---

## 2026-05-06 — Refactor: lift JdbcTemplate out of every inbox handler

Closed an architectural-consistency gap surfaced in conversation. Eleven inbox handlers across sales / inventory / manufacturing / purchasing held raw `JdbcTemplate` calls — duplicated `UPDATE … status = ?, version = version + 1` shapes, ad-hoc outbox INSERTs that bypassed the aggregate, and a saga-recovery query inlined as JDBC. After this slice **zero `JdbcTemplate` imports remain in any `*.application.inbox.*Handler.java`** across all services.

**New collaborators** (each absorbs a category of JDBC that was previously inline):

- **sales** — `SalesOrderHeaderStatusProjectionService.markStatus(orderId, status)` centralises the duplicated header-status UPDATE used by `StockReservedHandler`, `ManufacturingDispatchedHandler`, `CustomerPaymentReceivedHandler`. The same projection used to be inline in `ShipmentPostedHandler` too, but for shipment the cleaner path was to add a domain mutator: `SalesOrder.recordShipped(...)` flips status to `'shipped'` and emits `SalesOrderShipped` via `pendingEvents`; `SalesOrderShippingService.recordShipped(...)` is the thin app-service that loads / mutates / saves so the repository drains the event onto the outbox in the same txn.
- **inventory** — `StockBalanceWriter.bump(...)` and `WipBalanceWriter.bump(...) / decrement(...)` absorb the upsert SQL previously inlined in `WorkOrderManufacturingCompletedHandler` and `SubAssembliesConsumedHandler`. `WarehouseLookup.findIdByCode(...)` (new `*Lookup` per the five-suffix convention) replaces the inline `SELECT warehouse_id WHERE warehouse_code = ?`.
- **manufacturing** — `MakeToOrderShortageRecoveryQueryPort` + `JdbcMakeToOrderShortageRecoveryQueryPort` (new `*QueryPort`) hides the saga-vs-`work_order_material` join used in `GoodsReceivedHandler` to find shortage-parked sagas to un-park. `OutboxPort.appendPending(OutboxRow.pending(...))` is a new shared method on the existing port — used by `ManufacturingRequestedHandler` and `RawMaterialsReservedHandler` for the "saga emits an event but no aggregate is mutating" case (where routing through an aggregate would just churn `version`).
- **purchasing** — `PurchaseOrderReceiptProjectionService.recordReceipt(...)` and `PurchaseOrderPaymentProjectionService.markFullyPaid / addPartialPayment(...)` for the receipt + payment writes onto `purchase_order_header` / `purchase_order_line`. The PO aggregate doesn't currently model `received_quantity` or `paid_amount`, so these writes are CQRS-style rather than aggregate-driven; folding them into the aggregate is a separate slice.

**Architectural rule clarified** (worth remembering): handler layers never touch JDBC directly. Each JDBC call belongs in one of: a `*ProjectionService` (read-side denormalised replica), a `*Repository` / `*QueryPort` / `*Lookup` (per the five-suffix convention), an aggregate method drained via repository save (when the event is genuinely a state change on the aggregate), or `OutboxPort.appendPending(...)` (when the event is a saga-side observation with no aggregate to mutate).

**Verification:**
- `mvn install -DskipTests` — all 12 modules SUCCESS.
- `mvn test` — 308+ unit tests across the 7 services, all green. Constructor changes propagated into 4 handler tests (`CustomerPaymentReceived`, `StockReserved`, `ManufacturingDispatched`, `RawMaterialsReserved`) plus `GoodsReceived` (manufacturing) and `SupplierPaymentMade` (purchasing).
- `Grep JdbcTemplate **/application/inbox/*Handler.java` → 0 hits.

**Smoke-boot deferred** — pure refactor (no schema change, no event-shape change, no SQL change other than routing through the new collaborators), unit tests cover the handler-side logic. A fresh-volume boot is still recommended before the next demo run; bundling with the next behavioural slice is fine.

---

## 2026-05-06 — §1 Slice C1 + C5: Manufacturing operational UI + polish picks

Closed the last ERP-UI gap (Manufacturing) and shipped the four highest-value polish picks. Backend gained a small set of new endpoints to support the list-views the frontend needed.

**C1 — Manufacturing operational UI** (Linda):
- **Backend:** new `GET /api/work-orders/{id}` on manufacturing-service returning the full WorkOrder aggregate (header + materials + operations). Required because the existing reporting projection (production_planning_board) carries only high-level status — the operator-facing per-op state (sequence, status, planned/actual minutes) lives on the aggregate. New `WorkOrderResponse` DTO with nested `Material` + `Operation` records.
- **`/work-orders` list page** — reads from reporting's `production_planning_board` (existing list endpoint). Columns: WO #, product (sku + name), status pill, material status pill, completed/planned progress bar, priority pill, shortage count, updated-relative. Status filter dropdown (all / released / in_progress / completed / cancelled). Click-through to detail.
- **`/work-orders/:id` detail page** — reads from `/api/work-orders-cmd/{id}` (alias to manufacturing aggregate). Tabs: Overview / Materials / Operations / Audit. Operations tab has inline **Complete** + **Skip** buttons per row, gated on operation status (only `planned` / `in_progress` rows show actions). Page header has a **Set priority** button. Each action opens a focused dialog: Complete takes `actualMinutes` (with planned shown as a hint), Skip takes a reason, Set Priority takes a dropdown + reason. All POST via the BFF `-cmd` aliases.
- **`/production-board`** — three-column kanban view (Released / In Progress / Completed) reading the same projection. WO cards show priority pill, material status, progress bar, shortage count. Click navigates to detail.

**C5 — Polish picks**:
- **`/exchange-rate`** — ad-hoc rate lookup form. Two currency inputs (with datalist of common currencies), an effective-date input, a Look-up button. Wraps `GET /api/exchange-rate?from=&to=&date=`. Same-currency requests resolve to rate 1.0 client-side; 404 shows a clear "no rate on file" message instead of the raw error.
- **Generic `<Toast>` system** — `ToastProvider` + `useToast()` hook. Three tones (success / warn / error), success and warn auto-dismiss after 5s, errors persist until dismissed. Stacks bottom-right with slide-in animation. Wired into `main.tsx` wrapping the BrowserRouter. Retrofitted into `PendingReview` (success on approve/reject) and `SalesOrderDetail` (success on cancel) — most action paths can adopt it incrementally as needed.
- **`/customer-invoices`** — list view. New backend: `CustomerInvoiceRepository.findAll()` + `JdbcCustomerInvoiceRepository` impl + new `CustomerInvoiceController` (read-only — invoices are auto-generated from shipments) + new `CustomerInvoiceResponse` DTO. SPA list shows invoice #, customer, status, subtotal/tax/total. Click-through goes to the parent sales order's detail page (since the invoice is just a side-effect of the order).
- **`/payments`** — list view. New backend: `PaymentRepository.findAll()` + JDBC impl + `GET /api/payments` list endpoint on existing `PaymentController` (used to be by-id only). SPA list with direction filter (all / AR incoming / AP outgoing); columns include party, date, method, amount, status pills.

**BFF impact:** none — the new endpoints sit under existing route prefixes (`/api/work-orders`, `/api/customer-invoices`, `/api/payments`, `/api/exchange-rate`) that were already in the ERP BFF route table from C0.

**Verification:**
- `mvn -pl finance-service test` — all 56 tests still green after `findAll` additions.
- `mvn -pl manufacturing-service install` — clean.
- `npm run typecheck` — clean.
- `npm run build` — clean. Bundle 325 KB / 91 KB gzipped (was 294 / 85 after C2-C4; C1 + C5 added ~30 KB raw / ~6 KB gzipped across 7 net-new pages plus the ToastProvider system).

**Now functional in the ERP UI** — every persona has a working flow:
- **Sarah** — Sales Orders list + 360 detail with Cancel (sales_manager).
- **Mike** — Stock screens still placeholders; ATP / Material Shortage dashboards are reporting-side and work via existing list endpoints.
- **Linda** — Work Orders list + detail with Complete/Skip per op + Set Priority + Production Board.
- **Tom** — POs list + approve, Supplier Prices authoring.
- **Olivia** — Pending Review queue + invoice detail, Customer Invoices list, Payments list.
- **Daniel** — Journal Entries (lookup-by-id with reverse, reverse-by-source bulk), Exchange Rate lookup.
- **Emma** — Products list + detail with all four authoring actions.

**Remaining placeholders** (each is its own future slice if needed):
- `/customers`, `/suppliers`, `/stock-items`, `/stock-reservations`, `/goods-receipts`, `/shipments`, `/stock-movements` — Mike's screens; backend has the endpoints but the forms aren't built.
- `/purchase-requisitions` — PR creation form (non-trivial; lines, suggested suppliers, source-WO link).
- `/sales-orders/360`, `/ar-ap` — standalone dashboards on top of existing endpoints.
- `/boms` — explicitly low-priority (dev-todo §3.4).
- `/system/users`, `/system/audit-log` — gated on Slice A (auth) and Slice D (audit log).

**Smoke-test pending** (rolls into the existing dev-todo §2.5 set): full walkthrough of each persona flow in the browser against a fresh-volume + Kafka run.

## 2026-05-06 — §1 Slice C2 + C3 + C4: Finance, Purchasing+Product authoring, Sales cancel + polish

Filled in the operational ERP UI for the order-to-cash + procure-to-pay paths. C2/C3/C4 all shipped in one session (skipped C1 Manufacturing per user direction; can pick up later).

**C2 — Finance** (Olivia + Daniel):
- New foundation primitives: `<DetailLayout>` (title + status pill + action buttons + tabs strip), `<FormSection>` + `<Field>` + `<ReadOnlyField>` (labelled card with grid-arranged fields), `<ConfirmDialog>` (modal with optional inline `body` slot for reason inputs), `<TextInput>` / `<NumberInput>` / `<DateInput>` / `<TextArea>` / `<Select>`. Solid white surfaces, 1px borders, focus rings on brand color.
- `/supplier-invoices/pending-review` — manual-review queue. Each row shows internal #, supplier, total, line count, "3-way failed" pill, plus inline Approve/Reject buttons that open `<ConfirmDialog>` with reviewer + reason inputs. Posts to `/api/supplier-invoices/{id}/manual-approve` or `/reject`. List refreshes on success. `/supplier-invoices` redirects here since there's no general list endpoint.
- `/supplier-invoices/:id` — detail with tabs (Overview / Lines / Audit). Approve/Reject buttons inline near the status pill when status is `three_way_match_failed`.
- `/journal-entries` — two narrow workflows side-by-side because no list endpoint exists: **Lookup-by-id** (paste UUID, see header + balanced debit/credit lines + Reverse button) and **Reverse-by-source** (pick source-doc-type from dropdown + UUID + reason + posting date, posts `/api/journal-entries/reverse-by-source`). Both reverse actions guarded by `<ConfirmDialog>`.

**C3 — Purchasing + Product authoring** (Tom + Emma):
- `/products` — full catalog list reading `GET /api/products`. Columns: SKU, name, type, sales price, std cost, reorder pt/qty, valuation class, status pill.
- `/products/:id` — detail with four authoring action buttons in the header: Change Pricing, Reorder Policy, Make-vs-Buy, Discontinue. Each opens its own dialog with the relevant form. PUTs go via the new `/api/products-cmd/*` BFF alias to product-service. Dialogs disabled when status=`discontinued`. Showcases Emma's full Shape A authoring set.
- `/purchase-orders` — list reading from reporting's `purchase_order_tracking_view`. Columns: PO #, supplier, status, match status, ordered/received/outstanding amounts (color-coded), updated time.
- `/purchase-orders/:id` — detail reading the full aggregate via `/api/purchase-orders-cmd/{id}` (alias to purchasing-service for the aggregate, vs the projection on the bare path). Approve button visible only when status=`draft`; opens dialog with approver + reason inputs, posts `/api/purchase-orders-cmd/{id}/approve`. Demos the §1.2 PO draft/approve workflow.
- `/supplier-prices` — two narrow workflows: list-prices-by-supplier (lookup form, then DataGrid) and author-a-price (set/update form, PUTs `/api/supplier-product-prices`). Backend's §3.10 no-op suppression is invisible from the SPA but the feedback message references it.

**C4 — Sales cancel + cross-cutting polish** (Sarah):
- `/sales-orders/:id` — detail reading the 360 projection. Shows order header, fulfilment-progress pills (stock / manufacturing / shipment / invoice / payment), money totals, shortage warning when `hasShortage=true`. Cancel button in the header when status is cancellable (not `shipped`/`completed`/`cancelled`/`rejected`); opens dialog with reason input, posts `/api/sales-cmd/sales-orders/{id}/cancel` via the BFF alias. Server returns 409 if past `goods_shipped` regardless. The cancel dialog explains the saga compensation flow + hard-cancel WIP write-off.
- New `apiPut<T>` helper added to `lib/api.ts` (hoisted PUT/POST through a shared `apiWrite` so error handling is consistent across all mutating verbs).

**Backend wiring:** the new BFF (`erp-web-ui-bff`) already had `/api/products-cmd`, `/api/purchase-orders-cmd`, `/api/sales-cmd`, and `/api/work-orders-cmd` aliases in its RouteTable from C0, so no BFF changes were needed for any C2/C3/C4 write endpoint.

**Skipped / deferred** with placeholders explaining why:
- `/customer-invoices` — no list endpoint on backend; auto-generated from shipments; users don't author. Nothing meaningful to show.
- `/payments` — no list endpoint; existing demo SPA has working forms.
- `/exchange-rate` lookup — trivial, would mostly mirror the backend's `/api/exchange-rate` shape; left as placeholder.
- `/ar-ap` dashboard, `/sales-orders/360` standalone view — left as placeholders.
- `/purchase-requisitions` creation form — possible follow-up (PR creation form is non-trivial: lines, suggested suppliers, source-WO link).
- BOM editor (`/boms`) — explicitly low-priority per dev-todo §3.4.
- `/customers`, `/suppliers`, `/stock-items`, `/stock-reservations`, `/goods-receipts`, `/shipments`, `/stock-movements`, `/work-orders` (Manufacturing) — placeholders. `/work-orders` is the entirety of C1 (Manufacturing); deliberately skipped per user direction, can pick up later.

**Build:**
- `npm run typecheck` — clean.
- `npm run build` — clean. Bundle 294 KB / 85 KB gzipped (C0 was 237/74; C2-C4 added ~57 KB raw / ~11 KB gzipped — modest given 7 net-new pages).
- Reactor build unchanged (no Java touched).

**Cross-cutting** that landed naturally as part of the form-heavy screens but aren't fully realised yet (could capture as a small polish slice if needed):
- Generic 4xx error toast — every mutation handles errors via `<ConfirmDialog>` body slot today, sufficient for now.
- Optimistic-locking conflict UI — backend uses `version` columns but no ERP UI screen surfaces this concretely yet (no edit-while-someone-else-edits scenario in the shipped flows). Defer until needed.

**Smoke-test pending** for live demo: bring up postgres + Kafka + 7 services + new BFF + Vite dev server, then walk through:
1. Sarah lists Sales Orders, drills into one, cancels it (only when not past shipped) → saga walks compensation.
2. Olivia sees an invoice on Pending Review, approves it with a reason → P2P saga advances + GL posts.
3. Daniel pastes a journal entry id, hits Reverse → posted → reversed.
4. Tom approves a draft PO; opens supplier prices, sets a price; observes no-op suppression on second submit.
5. Emma changes a product's pricing; observes sales-side projection update.

Captured under dev-todo §2.5 smoke-test gaps.

## 2026-05-06 — Hot-fixes from first fresh-volume boot + lessons captured

User attempted a fresh-volume boot of multiple services and surfaced two bugs that unit tests had missed. Both fixed; CLAUDE.md updated with three new prescriptive rules.

**Fix 1 — `PurchaseToPaySaga.ALL_STATES` had defensive `compensating`/`compensated` entries** that no transition code produces. The §2.1 `SagaStateInvariantChecker` (boot-time CHECK-vs-code reconciliation) caught this exactly as designed: purchasing's schema CHECK doesn't allow those states, so boot aborted with a clear delta. Cleanest fix was removing the dead entries — purchasing has no PO-cancellation flow today; if/when one lands, both `ALL_STATES` and the schema CHECK extend in the same slice. Sales + manufacturing legitimately use the states (cancel-order shipped earlier today) and their CHECKs include them.

**Fix 2 — `2026-05-06-add-per-class-gl-accounts.sql` had two adjacent bugs:**
- Omitted `gl_account_id` from the INSERT, falling back to the column default `shared.uuid_generate_v7()` which calls unqualified `gen_random_bytes()`. Per-service `search_path = finance, shared` excludes `public` (where pgcrypto lives), so the default fails. v3.sql gets away with the same shape because `psql` runs without the `connection-init-sql` SET. Classic gotcha already in CLAUDE.md — the changeset shipped against the rule because the dev-done backfill skipped a fresh-volume smoke test.
- The fix initially used `account_id` as the column name; the actual PK is `gl_account_id` (the `<table_name>_id` convention spelled in full, not truncated). Caught when the corrected INSERT hit the DB.

**Lessons added to CLAUDE.md:**
- *Saga section:* "Only model states the code actually writes" — the defensive copy-paste anti-pattern, with the purchasing example.
- *Reference data section:* three new rules — don't copy `v3.sql` INSERT shape into Liquibase (different default-expression behaviour); verify the PK column name against v3.sql (don't guess by truncating); smoke-boot a service against a fresh volume on every Liquibase changeset (`docker compose down -v ; up -d postgres ; mvn -pl <service> spring-boot:run` — 30 seconds, catches both this episode's bug classes and the existing idempotency traps).

The smoke-boot rule directly addresses dev-todo §2.5 "Smoke-test gaps" by giving it a routine the contributor runs before shipping rather than a backlog item.

## 2026-05-06 — §1 Slice C0: erp-web-ui scaffolding + ERP shell + first wired route

Foundation for the new operational ERP SPA. Two new top-level projects, sibling to the existing technical demo SPA (renamed to `demo-web-ui` / `demo-web-ui-bff` 2026-05-06 in a follow-up cleanup):

- **`erp-web-ui-bff/`** — new Maven module (12th in the reactor). Spring Boot 4, port **8089**. Mirrors `demo-web-ui-bff`'s shape: `ProxyController` (path-prefix → service routing), `RouteTable`, `ErpBffTargets`, `EventsAggregatorController` (subscribes to all `*.events` topics under `@Profile("kafka")` and fans out to SSE clients). Skipped from the demo BFF: `SagaAggregatorController` (saga console is a demo feature, not operational). Distinct Kafka consumer-group `erp-web-ui-bff` so it runs alongside the demo BFF.
- **`erp-web-ui/`** — new Vite + React 18 + TypeScript project, port **5174**. Independent `package.json` and `vite.config.ts`; not in the Maven reactor. Vite proxies `/api/*` → `http://localhost:8089` (the new BFF).

**Visual identity** — Fiori-with-Odoo-restraint, light-themed and corporate. Tailwind 4 `@theme` tokens in `src/index.css`: deep corporate blue primary (`#1E3A8A`), cool grays, 1px borders over shadows, status colors (info/success/warn/error/neutral) that map identically across modules so a `cancelled` order and `cancelled` PO render the same. Inter font, 14px body, 13px in tables for density. Tabular-nums on monetary columns. Slate-800 sidebar; the rest of the surface is white-on-light-gray.

**ERP shell** — three-region layout that every route inherits:
- `<AppBar>` (h-14): brand mark + global search + notifications bell with badge + user menu (anonymous "Sarah Chen / Sales Clerk" until Slice A's auth lands).
- `<Sidebar>` (w-60): module-grouped nav rail (Home / Sales / Purchasing / Inventory / Manufacturing / Finance / Reporting / System) with collapsible groups; SAP Fiori "module rail" pattern. Active routes get a brand-blue background. Sales / Manufacturing / Finance auto-expand because they're the demo flow's hot paths.
- `<Breadcrumb>`: every route's `<PageHeader>` includes a Home › Module › Page trail. Clickable.
- `<AppShell>` outlet wraps everything in a flex layout with overflow-managed regions.

**UI primitives** — copy-paste components shaped for ERP density:
- `<DataGrid>` — generic table component with column descriptors, numeric-right-align, fixed-width columns, click-to-navigate rows, skeleton loading state, custom empty state. ~36px row height.
- `<StatusPill>` — pill with 1px border + tinted bg + tone-colored text. `statusForOrder(status)` helper centralises the domain-status → tone mapping (draft/pending → info, in-flight → warn, completed/paid → success, cancelled/rejected → error).
- `<PageHeader>` — title + description + breadcrumb + right-aligned action buttons in a consistent layout.
- `<ActionButton>` — primary / secondary / danger / ghost variants. `requiresRole?: string` prop is plumbed through as a tooltip today; Slice B will hook it into role-aware visibility without changing call sites.
- `<Breadcrumb>` — clickable trail with chevron separators.

**One real route — `/sales-orders`** (the look-and-feel reference for C1–C4): reads `GET /api/sales-orders` proxied through the new BFF to reporting-service's `sales_order_360_view` projection. Renders 7 columns (order number, customer, status pill, fulfilment-progress dot indicator, total, outstanding amount, relative timestamp). The fulfilment-progress dots are filled/hollow per stage (reserved → manufactured → shipped → invoiced → paid) — concrete demo of the cross-context CQRS projection without being saga-console-ish. Loading skeleton, empty state, error banner all wired. Click navigates to `/sales-orders/:id` (placeholder until C4).

**Routing scaffold** — every module sub-page from the navigation tree exists in `App.tsx`. Most render `<Placeholder>` with the breadcrumb pre-filled; the placeholder explicitly names the slice that will replace it. Unknown paths redirect to `/`. The home page is a 4-tile module launcher (Sales / Manufacturing / Purchasing / Finance) sized for quick demo navigation.

**`lib/api.ts`** — minimal `apiGet<T>` / `apiPost<T>` wrappers + `ApiError`. No baseURL — Vite dev proxy + production same-origin both resolve `/api/*` to the BFF.

**Build verification:**
- `mvn install -DskipTests` — clean across all 12 modules (was 11; new `erp-web-ui-bff` slots in cleanly between `web-ui-bff` and… nothing else).
- `npm run typecheck` — clean.
- `npm run build` — Vite production build succeeds; bundle 237 KB / 74 KB gzipped.
- `npm run dev` — Vite dev server starts on `http://localhost:5174` in ~280ms with no startup errors.

**Project decisions locked in this slice** (paired with the project memory written when the user signed off the plan):
- Demo SPA `web-ui` runs on port 8080 BFF unchanged. ERP BFF picked **8089** (avoids 8080–8087 service ports, leaves 8088 free).
- Aliased command paths under `-cmd` prefixes (`/api/sales-cmd`, `/api/work-orders-cmd`, `/api/purchase-orders-cmd`, `/api/products-cmd`) so the SPA can issue writes against the owning service while reads continue to hit reporting on the same `/api/<entity>` path. Mirrors the demo BFF's convention; keeps the route table alphabetically scannable.
- Anonymous SPA today — login wall is a Slice A retrofit that wraps `<AppShell>`. The `<AppBar>` user menu is a static placeholder.
- No shared component package between `web-ui` and `erp-web-ui` — clean copy, evolve independently.

**Smoke test pending** for live demo: bring up postgres + Kafka + all 7 services + the new BFF, run `npm run dev` in `erp-web-ui/`, navigate to `http://localhost:5174/sales-orders`, verify the list renders and clicks navigate. Captured in dev-todo §1.5 "Smoke-test gaps."

Next: C1 — Manufacturing operational UI (work orders list + detail with operation-complete + skip + setPriority + cancel).

## 2026-05-06 — §3 Slice E: exchange-rate lookup endpoint (§3.8) + dev-todo cleanup + CLAUDE.md compaction

**§3.8 `GET /api/exchange-rate?from=…&to=…&date=…`** — ad-hoc rate lookup on finance. Wraps `CurrencyConverter.rate(...)`; returns `{fromCurrency, toCurrency, rate, effectiveDate}`. 404 on `RateNotFoundException`. Same-currency requests pass through with rate `1.0` and `effectiveDate = date`. Trivial endpoint — useful when the UI eventually shows a live rate banner; for now it makes the converter behaviour visible from the outside.

**dev-todo §3 cleanup** — backfilled dev-done entries for the three "bigger §3 items" shipped earlier this session (§3.1 ProductCreated → manufacturing seeding, §3.2 + §3.6 per-class GL accounts, §3.3 sub-assembly WIP consume) — code was already in main but the dev-done write-ups weren't yet recorded. Trimmed dev-todo §3 to leave only genuinely deferred items (renumbered to §3.1–§3.5: reporting follow-ups, work-order `material_status`, soft-cancel WIP, CurrencyConverter scheduled importer, smoke-test gaps). All other §3.x items shipped across Slices A–E.

**CLAUDE.md compaction** — file reduced from 50KB/155 lines to 27KB/141 lines (46% smaller). Cuts: shipped-date references that belong in dev-done, repeated implementation-classname lists in `partial for X` paragraphs, redundant prose in saga descriptions. Architecture invariants, naming conventions, schema rules, and "things that bit us in production" all preserved. The build-status table now points to `partial` per service with terse 1-paragraph summaries focused on capability boundaries rather than every wired class.

`mvn install -DskipTests` clean across all 11 modules.

## 2026-05-06 — §3 Slice D: ATP product backfill (§3.4) + supplier-price no-op suppression (§3.10)

**§3.4 ATP stub product rows** — `available_to_promise_view` previously used `(pending)` placeholders for rows seeded by `inventory.StockReserved` / `inventory.RawMaterialsReserved` (those events don't carry product identity). Now subscribed to `product.ProductCreated` via a new ATP handler `atp.ProductCreatedHandler` (consumer name `reporting.atp.product-created`). New `AvailableToPromiseProjectionService.recordProductCreated(productId, sku, name, occurredAt)` upserts identity unconditionally — backfills existing stub rows and pre-creates a zero-quantity row when no reservation has happened yet. The reporting bus is already subscribed to `product.events`, so no `application-kafka.yml` change. New `ProductCreatedPayload` DTO.

**§3.10 supplier_product_price no-op suppression** — `SupplierProductPriceService.setPrice` now uses `BigDecimal.compareTo` to skip the UPDATE + version bump + outbox emit when the price hasn't changed (`100.00 == 100.0` is correctly treated as equal). The early-return path logs at INFO so suppression is visible in dev. First-time inserts (no existing row) always emit. Previously every call wrote and emitted an event regardless.

**Deferred from this slice (rolling into §3 Slice E):** `open_purchase_orders_count` on `production_planning_board` — the WO ↔ PO graph wiring requires extending the `PurchaseOrderCreated` event with a nullable `sourceWorkOrderId`, adding a `source_work_order_id` column to `purchase_order_tracking_view`, and recomputing the count on each PO event. Captured as a clearly-scoped follow-up rather than rushed mid-slice.

`mvn -pl reporting-service,purchasing-service test` clean. Smoke-test path: register a new product, place an order containing it; verify the ATP row's `product_sku`/`product_name` is the real value (not `(pending)`); call `setPrice` twice with the same value, observe second call logs the suppression and emits no event.

## 2026-05-06 — §3 bigger items: ProductCreated → manufacturing (§3.1) + per-class GL accounts (§3.2 + §3.6) + sub-assembly WIP consume (§3.3)

Three bigger §3 items shipped together as a setup for the polish slices. Backfill of dev-done entries — code already in main; this captures the rationale.

**§3.1 Newly registered products immediately sourceable** (option (b) — registration follow-up). Manufacturing now subscribes to `product.ProductCreated` via a new `ProductCreatedHandler` (consumer name `manufacturing.product-created`). On each new product event the handler calls `ProductReplenishmentProjectionService.seedDefaultsFromProductType(productId, productType)` which seeds `manufacturing.product_replenishment` with sensible defaults derived from the product type (raw_material → purchased only; finished_goods / sub_assembly → manufactured only; trading_goods → both). `ManufacturingRequestedHandler`'s gate that previously rejected newly-registered products now finds a row and accepts/rejects on the same rules as legacy products. Local `ProductCreatedPayload` DTO. The make-vs-buy command path still works for the unusual case where the operator wants to override the type-derived default.

**§3.2 + §3.6 Per-product-class GL accounts**. Three new GL accounts seeded via `db/changelog/changes/2026-05-06-add-per-class-gl-accounts.sql`: `1210` Raw Materials Inventory, `1220` Finished Goods Inventory, `5200` Raw Materials COGS (also added to `db/northwood_erp_v3.sql` for fresh installs). New constants `RM_INVENTORY_CODE`, `FG_INVENTORY_CODE`, `MATERIALS_COGS_CODE` on `JournalEntryService`. `JournalEntryService` now consults `ProductValuationClassProjectionService.findValuationClass(productId)` (new public read-method) on every receipt/shipment line: `raw_materials → 1210/5200`, `finished_goods` and others → `1220/5000`. New `LineCost(productId, amount)` record. `postGoodsReceived` and `postShipmentCost` now take `List<LineCost>` and produce one balanced multi-debit (or multi-debit/multi-credit) journal per receipt/shipment — keeping it as one journal per source-document (not N journals) so the source-document → journal mapping stays 1:1 for reversal. New helpers `postMultiDebit` (one CR + many DRs) and `postMultiDebitMultiCredit` (many DRs + many CRs); existing single-pair helpers untouched. Hardcoded fallback to `1200`/`5000` when a product has no projection row (chosen over fail-loudly: a missing valuation_class is informational drift, not a posting blocker).

**§3.3 Sub-assembly WIP consume path (option (a))**. New event `manufacturing.SubAssembliesConsumed(parentWorkOrderId, items[ConsumedItem(childWorkOrderId, productId, quantity)])` emitted by `WorkOrderOperationService.emitSubAssembliesConsumedIfParent` alongside a parent WO's `WorkOrderManufacturingCompleted`. Lists each immediate child's product + completedQuantity (consumed by the parent). Inventory's new `SubAssembliesConsumedHandler` decrements `inventory.wip_balance.on_hand_quantity` per item — so wip_balance no longer grows monotonically. (Recursive consumption — sub-assembly's own children — is handled implicitly: each child WO's own completion fires its own SubAssembliesConsumed, so the chain unwinds level by level.) `wip_balance.average_cost` left at 0 for now; that needs a costing decision (LIFO/FIFO/avg) and isn't on the demo critical path.

`mvn test` clean across all 11 modules at the time of merge. dev-todo §3.1 / §3.2 / §3.3 / §3.6 entries removed.

## 2026-05-06 — §3 Slice C: setPriority command (§3.5)

**§3.5 `setPriority(workOrderId, priority)` command** — operator-facing re-prioritisation of an open work order, surfacing on `production_planning_board.priority`.

- New event `manufacturing.WorkOrderPriorityChanged(workOrderId, priority, reason, occurredAt)`.
- New service `WorkOrderPrioritisationService` (manufacturing) — pure CQRS read-side slice: validates WO exists + priority is one of `{low, normal, high, urgent}`, then writes the event straight to the outbox. The WO aggregate **does not** track priority today (no manufacturing decision flow consumes it; if one ever does — e.g. a release-order scheduler — bring priority onto the aggregate then). Reuses the lightweight outbox-INSERT pattern already in `WorkOrderCancellationService`.
- New endpoint `POST /api/work-orders/{id}/priority` body `{priority, reason}` returning 204. `SetPriorityRequest` DTO with `@NotBlank priority` + bounded `@Size(max=500) reason`. 404 if the WO doesn't exist; 409 if the priority value is invalid.
- New reporting handler `BoardWorkOrderPriorityChangedHandler` + `WorkOrderPriorityChangedPayload` DTO. Calls a new `ProductionPlanningProjectionService.recordPriorityChanged` upsert that overwrites `priority` unconditionally (latest user-driven priority wins). The new method follows the existing order-tolerance pattern: late `WorkOrderPriorityChanged` arriving before the seed creates a stub row carrying the priority; the late seed's `ON CONFLICT DO UPDATE SET …` doesn't list `priority`, so the previously-set priority survives.
- dev-todo §3.4 entry "`priority` hardcoded to `'normal'` on `production_planning_board`" + §3.5 first bullet retired.

`mvn -pl manufacturing-service,reporting-service test` clean. No DB migration needed — `production_planning_board.priority` already existed in v3 with the right CHECK + default.

Smoke test (deferred, captured under §3.9): start all 7 services + Kafka, place an order to release a WO, `POST /api/work-orders/{id}/priority` body `{"priority":"urgent","reason":"customer escalation"}`, hit `GET /api/work-orders/{id}/board` → `priority: "urgent"` within ~1s.

## 2026-05-06 — §3 Slice B: cancel-flow polish

**§3.8 Reporting projection of `WorkOrderCancelled`** — production_planning_board now reflects cancelled WOs. New `BoardWorkOrderCancelledHandler` consumes `manufacturing.WorkOrderCancelled` and calls `ProductionPlanningProjectionService.recordWorkOrderCancelled` which unconditionally flips `work_order_status='cancelled'` (overrides the otherwise pending-only adoption rule — cancellation is terminal regardless of where the WO was). Same `INSERT … ON CONFLICT DO UPDATE` upsert pattern used by every other board handler. New `WorkOrderCancelledPayload` DTO in reporting's inbox package.

**§3.9 Sub-assembly cascade on parent WO cancel** — turned out to be a non-bug. Verified in `WorkOrderReleaseService.java:124` that child WOs are released with `command.salesOrderHeaderId()` (the parent's), so they share the sales order id with the parent. `WorkOrderCancellationService.cancelForSalesOrder`'s query `WHERE sales_order_header_id = ? AND status NOT IN ('completed', 'closed', 'cancelled')` already finds parent + every active descendant in one shot. Each is cancelled independently in the same txn. Closed without code change.

## 2026-05-06 — §3 Slice A: cleanups + cosmetic fixes

Four small items bundled:

**§3.13 Delete unused `shared.domain.Address` VO** — file + unit test gone; CLAUDE.md `shared-kernel` example list trimmed (`Money, Quantity, Sku, DomainEvent`). No service ever referenced `Address`; customer/supplier addresses are plain text columns on the schema.

**§3.14 Delete `InProcessEventBus`** (recommendation b) — file gone; residual mentions scrubbed from CLAUDE.md and demo-script.md. The codebase has grown up Kafka-only and the bus class was dead code (never instantiated as a bean). The `messaging/` package now holds only the bus-agnostic port (`EventPublisher`, `EventEnvelope`, `InboxEnvelopeHandler`); the Kafka adapter lives in `messaging/kafka/`.

**§3.4 `last_event_type` ordering nuance on sales_order_360_view** — replaced the unconditional `last_event_type = EXCLUDED.last_event_type` (×6 sites) with a `CASE` guard that only adopts the new value when `EXCLUDED.last_event_at` is later than the current row's `last_event_at` (or the current is null). Prevents an out-of-order replay (events from two topics interleaved) from reflecting "last UPDATE applied" when the user expected "most recent by `occurredAt`."

**§3.5 `Skipped` operation status** — operator-facing skip command. New `WorkOrderOperation.markSkipped()` (package-private), `WorkOrder.skipOperation(sequence, reason, noPendingChildren)` aggregate method enforcing the same sequence-ordering invariant as complete. `WorkOrderOperationService.skipOperation` reuses the same WO-completion cascade (saga + parent chain). New `POST /api/work-orders/{id}/operations/{sequence}/skip` body `{reason}` returning 204. From the WO state machine's perspective skipped == completed: it doesn't gate later operations and a skipped last op closes the WO. Skipped ops emit no `OperationCompleted` (they didn't produce the standard outcome event); the WO-level `WorkOrderManufacturingCompleted` still fires when the whole WO is done. Doc note: `allOperationsCompleted()` now treats both `completed` and `skipped` as done.

Reactor build clean; full `mvn test` clean across all 11 modules. dev-todo §3 trimmed: 3.13 / 3.14 / the two sub-bullets gone; 3.10 / 3.11 / 3.12 renumbered to 3.8 / 3.9 / 3.10 after the cancel-polish slice removed §3.8 + §3.9.

## 2026-05-06 — Real `/events` aggregator on the BFF (§2.3)

Phase 1's synthetic event drawer is gone — the SPA's `EventDrawer` now streams real events from the Kafka bus via the BFF.

**BFF side** (`web-ui-bff`):
- Added `org.springframework.kafka:spring-kafka` dependency. Spring Boot's `KafkaAutoConfiguration` auto-enables `@KafkaListener` processing.
- New `application-kafka.yml` with consumer config (group `web-ui-bff`, `auto-offset-reset=latest` — the drawer is a real-time peek, not an audit log) and `northwood.events.subscribe-topics` listing all six `*.events` topics (product / sales / inventory / manufacturing / purchasing / finance — reporting is inbox-only and emits nothing).
- New `EventsAggregatorController` (`@Profile("kafka")`):
  - `@KafkaListener(topics = "#{'${northwood.events.subscribe-topics}'.split(',')}")` decodes each `EventEnvelope` JSON into a flat `EventRow {eventId, eventType, sourceService, aggregateType, aggregateId, occurredAt, receivedAt}`. `sourceService` derived from topic prefix (`sales.events` → `sales`).
  - `GET /api/events` exposes `SseEmitter`. Each consumed Kafka record fans out to every connected SPA client. Subscribers tracked in `CopyOnWriteArrayList`; auto-removed on completion / timeout / error.
  - Malformed records skipped with a debug log (no broker poison-pill stalls).

**SPA side** (`web-ui`):
- `EventDrawer.tsx`: swapped the synthetic `setInterval` generator for `new EventSource("/api/events")` listening on the named `event` channel. Wire shape mapped (`sourceService` → `service`). Empty-state copy updated to "drive a flow … to see them stream in."

`mvn clean install -DskipTests` green across all 11 modules; `npm typecheck` clean.

Live exercise deferred — needs Kafka + at least one event-emitting service running. Place a sales order with the BFF + sales-service up under `kafka` profile, and the drawer should immediately render `sales.SalesOrderPlaced`, `sales.StockReservationRequested`, etc.

## 2026-05-06 — Kafka error handler + retry + DLT (§2.2)

When an inbox handler throws today, Spring Kafka logs the error and silently advances to the next message — no retry, no audit trail. Real production needs bounded retry then a dead-letter destination. Wired:

- New `DefaultErrorHandler` bean in `KafkaMessagingAutoConfiguration` (under `@Profile("kafka")`). Spring Boot's auto-configured `ConcurrentKafkaListenerContainerFactory` picks it up and applies it to every `@KafkaListener` (today: just `KafkaInboxDispatcher`).
- Retry policy: `FixedBackOff(0L, 3)` — 3 immediate in-process retries, no backoff. Most failures here are transient (DB lock contention, brief outbound hiccups); the inbox idempotency guard handles the at-least-once delivery cleanly.
- After exhausting retries, `DeadLetterPublishingRecoverer` publishes the failed record to `<originalTopic>.dlt` (e.g. `sales.events.dlt`) keeping the original partition. Topics auto-create on first publish (broker's `auto.create.topics.enable=true` from docker-compose). Logs a WARN at the publish point with offset + exception summary so failures are visible without inspecting the broker.

Persistent failures (poison pills, payload-shape mismatches, NPE bugs) flow to the DLT after the third attempt for human inspection via `kafka-console-consumer.sh --topic sales.events.dlt --from-beginning`. No DLT consumer wired today — log + Kafka topic suffice for the showcase. A `failed_message` table for SQL-side audit was scoped out as adding schema complexity without showcase payoff.

Reactor build clean. Smoke test deferred — exercise via a synthetic handler-throws scenario (parse-failure on a malformed envelope, etc.) the next time we're at a live cluster.

## 2026-05-06 — Startup-time saga-state invariant check (§2.1)

Catches the failure mode that bit us 2026-05-05 (`invoice_paid` saga state was written by code but missing from the v3.sql CHECK; mocked unit tests passed; a real partial customer payment would have crashed at INSERT). New mechanism in `shared-infrastructure`:

- `SagaStateInvariantCheck` interface — per-saga registration with `schemaName()`, `tableName()`, `columnName()` (default `saga_state`), `codeStates()`.
- `SagaStateInvariantChecker` runs on `ApplicationReadyEvent`. For each registered check, queries `pg_get_constraintdef` for every CHECK on the table, picks the one mentioning `saga_state`, regex-extracts the single-quoted literals, and asserts `codeStates ⊆ dbStates`. Throws on missing — boot fails fast with a clear message.
- `SagaInvariantsAutoConfiguration` (registered via `META-INF/spring/AutoConfiguration.imports`) publishes the checker bean and auto-collects every `SagaStateInvariantCheck` registered in any service.
- Each saga aggregate now declares `public static final Set<String> ALL_STATES` (sales / manufacturing / purchasing).
- Each owning service gains a `@Configuration` registering a `SagaStateInvariantCheck` bean — `SalesSagaInvariantsConfig`, `ManufacturingSagaInvariantsConfig`, `PurchasingSagaInvariantsConfig`.
- Services that don't own a saga (product, inventory, finance, reporting) get the checker bean too; with no `SagaStateInvariantCheck` beans, the verifier short-circuits silently.

Reactor build clean. Live exercise deferred to next service boot — the check runs at `ApplicationReadyEvent`, so a stale CHECK shows up in the boot log immediately.

## 2026-05-06 — PO draft / approve workflow (§1.2)

Schema's reserved `purchase_order_approved` saga state + `purchase_order.status='draft'` are now wired. New flow:

- **`PurchaseOrder.fromRequisition(...)`** takes `boolean autoApprove`. When true → status `'sent'`, emits both `PurchaseOrderCreated(status="sent")` AND `PurchaseOrderApproved` in the same write. When false → status `'draft'`, emits only `PurchaseOrderCreated(status="draft")`.
- **`PurchaseOrder.approve(approver, reason)`** aggregate method: flips draft → sent, emits `PurchaseOrderApproved`. Throws `PoNotApprovableException` (→ HTTP 409) if status is anything other than draft.
- **`PurchaseOrderService.convertFromRequisition(prId, autoApprove)`** plumbs the flag through. Saga is always inserted at `'started'`; for auto-approve it's flipped inline to `'purchase_order_approved'` in the same txn.
- **`PurchaseOrderService.approve(poId, approver, reason)`** loads PO, calls aggregate.approve, saves, then flips the P2P saga `started → purchase_order_approved` inline.
- **REST**: new `POST /api/purchase-orders/{id}/approve` body `{approver, reason}`. New `GET /api/purchase-orders/{id}` for read. New `PurchaseOrderResponse` + `ApprovePurchaseOrderRequest` DTOs.

Routing:
- `PurchaseRequisitionService.createManual` always passes `autoApprove=false` — manual PRs land at draft.
- `PurchaseRequisitionService.createForWorkOrderShortage` passes `autoApprove=northwood.purchasing.shortagePoAutoApprove` (default `true`). The make-to-order saga continues to flow without a human; flip the property to `false` to force human approval of every PO.

Saga worker simplified: previously handled `started → waiting_for_goods` in one transition. Now `started` is no longer worker-driven — `approve()` (auto or manual) flips it inline. Worker handles only `purchase_order_approved → waiting_for_goods`. New event `purchasing.PurchaseOrderApproved` emitted by both auto-approve and manual paths.

**Tests**: `PurchaseOrderTest` rewritten — old `FromRequisition` block split into `FromRequisition_AutoApprove` (8 tests, including new "emits both Created + Approved" + "Created event carries status='sent'") and `FromRequisition_Draft` (2 tests). New `Approve` nested class (2 tests: flips draft→sent + emits approved; rejects when already sent). 34 purchasing tests all green; full reactor build clean.

CLAUDE.md saga commentary + purchasing partial line updated; user-stories.md acceptance criterion flipped ⏳ → ✅; demo-script.md gained the manual-PR-then-approve flow; demo-script.md "out of scope" trimmed; dev-todo.md §1 demo-gap fillers section is now empty (all three §1 items — cancel-order/§3.7, price-variance, PO draft — shipped today).

## 2026-05-06 — Three-way match price-variance review (§1.1)

Extended `SupplierInvoiceService.recordInvoice`'s 3-way match from quantity-only to **quantity + price-variance**. Per-invoice-line `unitPrice` is compared against the matching `purchase_order_line_facts.unit_price`; relative variance > `northwood.finance.match.priceTolerancePercent` (default 2.0%) parks the invoice at `three_way_match_failed`.

Scope:
- New helper `priceVariesOutsideTolerance(invoiceUnit, poUnit)`: computes `abs(invoiceUnit - poUnit) / poUnit × 100`. Returns false (skip) when either side is null or PO unit_price is zero — typically seed data without a price; the quantity-only check still runs.
- `decideMatchOutcome` reorganised into two passes: per-invoice-line price check first (price is per-line, not aggregated), then per-PO-line aggregated quantity check (since multiple invoice lines can point at one PO line). First failure short-circuits.
- New constructor parameter `BigDecimal priceTolerancePercent` injected from `@Value("${northwood.finance.match.priceTolerancePercent:2.0}")`. Property added to `application.yml` under a new `northwood.finance.match.*` namespace with explanatory comment.
- Manual-review tooling shipped 2026-05-05 (`GET /api/supplier-invoices/pending-review` + `POST /{id}/manual-approve|reject`) needs no changes; the queue lights up automatically as price-variance failures arrive.

Tests: 7 new `SupplierInvoiceServiceMatchTest` cases — exact match, just-under, at-boundary (2.0% == 2.0% is ok), just-above (3% fails), supplier-charges-lower-also-fails (abs variance), zero-PO-price-skips-check, price-fail-takes-precedence-over-quantity-pass. 56 finance tests all green; full reactor `mvn test` clean.

CLAUDE.md finance partial line updated; user-stories.md acceptance criterion flipped 🚧 → ✅; demo-script.md gained instructions for staging a price-variance failure (post invoice with `unitPrice: 30` against a PO line at `25` → lands in three_way_match_failed → resolve via manual-approve or reject); demo-script.md "out of scope" trim. dev-todo.md §1.1 removed (the slot is now `1.1 PO draft / approve workflow`, the previously-§1.2).

## 2026-05-06 — Cancel an order mid-fulfilment (§1.1) + bulk GL reversal by source (§3.7)

Bundled slice covering the user-stories.md ⏳ on cancellation (Story 4.1) and the related GL reversal primitive. Eight new events, four inbox handlers, three new application services, one new REST endpoint, one new bulk-GL endpoint, ~15 source files.

**Cancel flow** (sales → inventory + manufacturing → ack-back to sales):

- **Sales**: `SalesOrder.cancel(reason)` aggregate method (cancellable up to `ready_to_ship`; rejects `shipped` / `completed` / `cancelled` / `rejected` with `OrderNotCancellableException` → HTTP 409). Header flips to `'cancelled'` + `cancelled_at = now()`. Emits `sales.SalesOrderCancellationRequested`. `SalesOrderService.cancel(...)` flips the fulfilment saga inline to `compensating`. New `POST /api/sales-orders/{id}/cancel` body `{reason}`. The cancel command and saga flip share one transaction.
- **Inventory**: new `SalesOrderCancellationRequestedHandler`. `StockReservationService.releaseForSalesOrder` decrements `stock_balance.reserved_quantity` for every line of the matching reservation, marks header `'released'`, then emits `inventory.SalesOrderCancellationApplied(salesOrderHeaderId, reservationsReleased)` — always, even with zero reservations to release.
- **Manufacturing**: new `SalesOrderCancellationRequestedHandler` + `WorkOrderCancellationService`. For every active WO (status NOT IN completed/closed/cancelled) tied to the sales order: `WorkOrder.cancel(reason)` aggregate method flips status to `'cancelled'` + emits `manufacturing.WorkOrderCancelled(workOrderId, parentWorkOrderId, salesOrderHeaderId)`; the matching `make_to_order_saga` flips to `'compensated'`. Then one `manufacturing.SalesOrderCancellationApplied(salesOrderHeaderId, workOrdersCancelled)` ack — always, even with zero WOs. Hard-cancel by design (WIP written off; soft-cancel parked as follow-up in §3.x).
- **Inventory (rm release)**: new `WorkOrderCancelledHandler` consumes `manufacturing.WorkOrderCancelled` and calls `StockReservationService.releaseForWorkOrder` to decrement raw-material `stock_balance.reserved_quantity` and mark the rm reservation `'released'`. No ack — manufacturing's sales-cancel ack is what the saga waits on.
- **Sales (saga completion)**: two new inbox handlers, `InventoryCancellationAppliedHandler` + `ManufacturingCancellationAppliedHandler`, each flips a Boolean in `saga.data` (`inventoryCancellationAcked`, `manufacturingCancellationAcked`). The shared `SagaCompensationCompletionService` checks both — when both true and saga still in `'compensating'`: transitions to `'compensated'`, emits `sales.SalesOrderCompensated(salesOrderHeaderId, cancelledAt)`. Idempotent against concurrent acks (the second handler sees state already past `compensating` and silently no-ops).
- **Reporting**: new `SalesOrderCompensatedHandler` consumes `sales.SalesOrderCompensated` and unconditionally flips `sales_order_360_view.order_status = 'cancelled'` (the only override of the otherwise pending-only adoption rule on `order_status`).

**`FulfilmentSagaData` extended** with two `Boolean` fields (boxed not primitive — Jackson would otherwise trip `FAIL_ON_NULL_FOR_PRIMITIVES` deserializing legacy saga.data blobs that lack the new fields). Compact constructor unboxes null to false.

**§3.7 bulk reversal**: `JournalEntryRepository.findPostedIdsBySource(docType, docId)` filters by `status='posted'` and excludes `source_document_type='journal_reversal'` (no reversal-of-reversal). `JournalEntryService.reverseBySourceDocument(docType, docId, reason, postingDate)` loops `reverseEntry` per id; all reversals atomic in one transaction. `POST /api/journal-entries/reverse-by-source` body `{sourceDocumentType, sourceDocumentId, reason, postingDate}` returns `{reversedCount, reversalEntryIds}`. Idempotent (already-reversed entries excluded by the find query). **Standalone primitive — not invoked by the cancel flow today** since cancel only reaches pre-GL states; future `cancelInvoice` / refund flows will be the consumers.

**Tests**: 7 new SalesOrder.cancel guards (cancellable from submitted / in_fulfilment; rejected from shipped / completed / cancelled / rejected; event payload checks) + 3 new JournalEntryService.reverseBySourceDocument tests (empty source, multi-entry batch, already-reversed filtering). 86 sales tests + 49 finance tests all green; full reactor `mvn test` clean across all 11 modules.

**Smoke test deferred to live demo run** — exercises require all 7 services + Kafka up + a sales order placed and advanced to `manufacturing_in_progress`, then `POST /api/sales-orders/{id}/cancel` and watch the saga walk `manufacturing_in_progress → compensating → compensated` in the Saga Console UI.

**Follow-ups** (added to dev-todo.md):
- Soft-cancel WIP path: instead of hard write-off, let in-progress WOs finish then scrap finished goods to a write-off bucket.
- Reporting: production-board projection of `WorkOrderCancelled` (today only the 360 view tracks the cancelled state).
- Sub-assembly cancellation: cascade `WorkOrderCancelled` from a parent to its sub-assembly children (today each WO is independent; a cascading recurse would mirror the existing parent-on-children completion gate).

## 2026-05-06 — Sweep changeset idempotency against the v3.sql baseline

After v3.sql loaded cleanly on a fresh volume, multiple service boots tripped on Liquibase changesets that were nominally "idempotent against the v3 baseline" (per CLAUDE.md's design pattern) but in practice had non-idempotent statements. The user hit three trigger-already-exists errors in succession (`trg_supplier_product_price_updated_at`, `trg_approved_vendor_updated_at`, `trg_wip_balance_updated_at`) — `CREATE TRIGGER` has no `IF NOT EXISTS` form in Postgres, and v3.sql had since been updated to create these triggers as part of the baseline.

Sweep applied to all changeset SQL files:

- **`CREATE TRIGGER` → `CREATE OR REPLACE TRIGGER`** (Postgres 14+ supports `OR REPLACE` for triggers; identical effect on a fresh DB whose trigger already matches; creates the trigger on a legacy DB that doesn't yet have it). 6 source files updated. The manufacturing routing changeset already used the older `DO $$ … EXCEPTION WHEN duplicate_object THEN NULL` idiom — left alone.
- **`purchasing/.../2026-05-04-add-supplier-product-price.sql` seed `ON CONFLICT`**: extended from 3-col `(supplier_id, product_id, currency_code)` to 5-col `(…, effective_from, min_quantity)`. Same drift root cause as the v3.sql fix earlier today: when the 2026-05-05 tier-effectivity slice extended the unique constraint to 5 columns, this seed's ON CONFLICT wasn't updated alongside.
- **`purchasing/.../2026-05-05-add-price-effectivity-and-tiers.sql` `ADD CONSTRAINT`**: prefixed with `DROP CONSTRAINT IF EXISTS supplier_product_price_unique_tier`, so the changeset cleanly handles both fresh-v3-baseline DBs (drops then re-adds the existing 5-col UK as a no-op) and pre-tier DBs (drops the autonamed 3-col UK on the line above, then adds the new 5-col UK).

Audited every other ON CONFLICT, CREATE TABLE, CREATE INDEX, ADD CONSTRAINT in changeset files — all use `IF NOT EXISTS` / `IF EXISTS` guards or `DO` blocks with `EXCEPTION WHEN`, or wrap structural changes in `DROP IF EXISTS` first.

User retried service boots after the sweep — Liquibase now no-ops cleanly against the v3 baseline across every service.

## 2026-05-06 — Fix `supplier_product_price` ON CONFLICT mismatch in v3.sql

After the docker-init-safety fix, a fresh boot got further into v3.sql but tripped on a real schema/seed drift: `purchasing.supplier_product_price` carries `UNIQUE (supplier_id, product_id, currency_code, effective_from, min_quantity)` (five columns, supporting tiered pricing + effective-date ranges), but the seed `INSERT … ON CONFLICT` listed only the first three. Postgres requires the `ON CONFLICT` column list to match a unique constraint *exactly* (subset isn't enough), so it rejects with `there is no unique or exclusion constraint matching the ON CONFLICT specification`.

This bug had been latent since the unique constraint was extended to include effective_from / min_quantity for the tiered-pricing slice. The DROP DATABASE / CREATE DATABASE / \connect block at the top of v3.sql would have surfaced it on any rerun against a fresh DB — but historically the script appears to have been loaded once and then left alone, so the broken INSERT never ran. The fix from the prior slice (auto-loading via docker-entrypoint-initdb.d on every fresh volume) made the breakage routine.

Fix: extended `ON CONFLICT (supplier_id, product_id, currency_code)` to `ON CONFLICT (supplier_id, product_id, currency_code, effective_from, min_quantity)`. The INSERT only specifies five columns; the defaulted `effective_from = '1970-01-01'` and `min_quantity = 0` participate in the conflict check.

Audit: swept all other `ON CONFLICT` clauses in v3.sql; the rest match their tables' unique constraints / unique indexes correctly. Only `supplier_product_price` had drifted.

## 2026-05-06 — Make `db/northwood_erp_v3.sql` docker-init safe

The 2026-05-05 docker-compose auto-seed mount fix (mounting v3.sql into `/docker-entrypoint-initdb.d/`) was incomplete: the script's first non-comment statement was `DROP DATABASE IF EXISTS northwood_erp; CREATE DATABASE northwood_erp; \connect northwood_erp`, designed for `psql -U postgres -f install.sql` where psql starts in the default postgres meta-DB. But the docker entrypoint creates `northwood_erp` via `POSTGRES_DB` env var and runs init scripts **while connected to it** — and Postgres rejects `DROP DATABASE` on the currently-open connection (`cannot drop the currently open database`).

Fix: removed the `DROP DATABASE / CREATE DATABASE / \connect` block from v3.sql. The script now assumes the database already exists and that you're already connected to it. Both invocation paths satisfy that:

- Docker init: postgres entrypoint creates the DB from `POSTGRES_DB` and connects before running scripts.
- Standalone psql: requires `createdb northwood_erp` first, then `psql -d northwood_erp -f install.sql` (one extra `-d` arg vs. the old `-f`-only command). The script's header was rewritten to document both paths.

User caught this on a fresh `docker compose down -v ; up -d postgres kafka` cycle — product-service then failed with `schema "product" does not exist` during Liquibase init, because v3.sql crashed on its first statement and never ran the per-schema sections that create `product`, `inventory`, etc.

Recovery: wipe the broken volume (`docker compose down -v`), bring postgres back up — the now-fixed v3.sql auto-loads cleanly.

## 2026-05-06 — Add `Jdbc` prefix to inbox/outbox auto-config class names

`InboxAutoConfiguration` / `OutboxAutoConfiguration` were inconsistent with the Kafka precedent: the same role on the messaging side is `KafkaMessagingAutoConfiguration` — `<technology><concern>AutoConfiguration` — even though the package itself already says `kafka`. The inbox/outbox auto-config classes lived in `inbox/jdbc/` and `outbox/jdbc/` after the previous slice but didn't carry the technology prefix on the class name.

Renamed:
- `InboxAutoConfiguration` → `JdbcInboxAutoConfiguration`
- `OutboxAutoConfiguration` → `JdbcOutboxAutoConfiguration`

Mechanical: 2 file renames + class declaration rewrites; 2 additional `.java` files updated (the JDBC adapter classes whose Javadoc `{@link …AutoConfiguration}` references needed to follow the rename); `META-INF/spring/AutoConfiguration.imports` updated with the new FQNs.

`mvn -pl inventory-service -am clean verify` green: 21 unit tests + `ReorderPolicyChangedSeamIT` against Testcontainers — proves the new FQNs resolve through Spring Boot's `AutoConfiguration.imports` machinery and the JDBC beans wire up correctly at runtime.

CLAUDE.md updated.

## 2026-05-06 — Move shared inbox/outbox JDBC adapters into `jdbc/` sub-packages

The previous slice consolidated 12 per-service adapters into 4 files in `shared-infrastructure/.../inbox/` and `outbox/`, putting ports and adapters in the same package. That made each package a small mix of port (interface + DTO) and JDBC adapter (implementation + auto-config), which the codebase already separates elsewhere — `messaging/` holds the port (`EventPublisher`), `messaging/kafka/` holds the Kafka adapter classes.

Applied the same pattern to inbox/outbox: created `inbox/jdbc/` and `outbox/jdbc/` sub-packages. Moved `JdbcInboxAdapter` + `InboxAutoConfiguration` into `inbox/jdbc/`, `JdbcOutboxAdapter` + `OutboxAutoConfiguration` into `outbox/jdbc/`. Updated package declarations and added explicit imports for the parent-package types (`InboxPort`, `InboxRow`, `OutboxPort`, `OutboxRow`) — same shape as the existing Kafka files.

Updated `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` with the new FQNs (`com.northwood.shared.infrastructure.inbox.jdbc.InboxAutoConfiguration`, `com.northwood.shared.infrastructure.outbox.jdbc.OutboxAutoConfiguration`).

Build + tests green: `mvn clean install -DskipTests` across all 10 modules; `mvn -pl inventory-service -am clean verify` runs 21 unit tests + the `ReorderPolicyChangedSeamIT` to completion against a Testcontainer — proves the new auto-config FQNs are loaded by Spring Boot's `AutoConfiguration.imports` machinery and the beans get registered correctly.

CLAUDE.md naming sub-section updated to note that the shared adapters live in `jdbc/` sub-packages, mirroring the `messaging/kafka/` pattern.

## 2026-05-05 — Consolidate inbox/outbox adapters into `shared-infrastructure`

The 12 per-service inbox/outbox adapter classes (`JdbcInboxAdapter` × 6, `JdbcOutboxAdapter` × 6) were near-identical — same SQL, same column names, only differing in the schema qualifier baked into table references (`inventory.inbox_message` vs `sales.inbox_message` vs …). And every service already sets `search_path = <service>, shared` on every Hikari connection via `connection-init-sql`. So the schema qualifier was redundant: dropping it makes the unqualified name resolve to `<service>.<table>` automatically.

Refactor: deleted the 12 per-service classes; published one shared `JdbcInboxAdapter` and one shared `JdbcOutboxAdapter` in `shared-infrastructure` (under `inbox/` and `outbox/` packages, next to their respective `*Port` interfaces). Each is registered as a Spring bean via a small `@AutoConfiguration` class (`InboxAutoConfiguration`, `OutboxAutoConfiguration`) listed in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`. Component scan from each service's `@SpringBootApplication` doesn't reach `com.northwood.shared.*`, so the auto-config publishing is essential.

Both auto-configs use `@ConditionalOnMissingBean` so a service can still register its own adapter for testing or specialisation. Producer-only services (product) get the `InboxPort` bean too; inbox-only services (reporting) get the `OutboxPort` bean too — both are dormant in those services because nothing wires up the consuming side (no inbox handlers / no `*OutboxConfig`).

Saga adapters intentionally stay per-service: each saga has a different state shape and different domain key columns, so the JDBC code is genuinely flow-specific.

`mvn clean install -DskipTests` green across all 10 modules; `mvn -pl inventory-service -am clean verify` green — the `ReorderPolicyChangedSeamIT` proves at runtime that `search_path` resolves the unqualified `inbox_message` / `outbox_message` correctly against `inventory.<table>`.

CLAUDE.md DDD layering tree updated: `messaging/` per service now contains only `<Service>OutboxConfig`; the JDBC adapters live once in shared-infrastructure. The naming sub-section now distinguishes "shared adapter" (inbox, outbox) vs "per-service adapter" (saga).

## 2026-05-05 — Drop redundant service prefix from outbox adapter class names

`Jdbc<Service>OutboxAdapter` (`JdbcSalesOutboxAdapter`, `JdbcPurchasingOutboxAdapter`, etc.) was structurally redundant — the package already says `com.northwood.<service>.infrastructure.messaging`, so the class name was duplicating information the package already carried. Inbox adapters had been `JdbcInboxAdapter` (no service prefix) since they were introduced; the asymmetry surfaced as a question after the inbox-package move.

Renamed all six `Jdbc<Service>OutboxAdapter` to `JdbcOutboxAdapter`. Each remains in its own per-service package, so the FQN is unique even though the simple name now collides with the other five — Spring identifies beans by FQN, not simple name, so no runtime issue.

Six file renames (filename + class declaration + constructor in each); zero external references needed updating because every `*OutboxConfig` injects via the `OutboxPort` interface, not the concrete class.

`mvn clean install -DskipTests` green across all 10 modules.

The saga adapters keep their flow prefix (`JdbcSalesOrderFulfilmentSagaAdapter`, `JdbcMakeToOrderSagaAdapter`, `JdbcPurchaseToPaySagaAdapter`) because the flow name is *meaningful* — a single service can own multiple sagas, so the prefix disambiguates *which saga*, not *which service*. The outbox prefix was just the service name twice; the flow prefix isn't.

## 2026-05-05 — Reorganise `infrastructure/` sub-packages by functional concern

The `infrastructure/` sub-packages organise classes by **functional concern**, not implementation technology — `persistence/` for domain aggregate CRUD, `messaging/` for the transactional outbox/inbox pattern, `saga/` for saga state + workers — even though all three end up implemented with JDBC.

Two asymmetries fixed:

- **Inbox adapter belonged in `messaging/`, not `persistence/`.** Inbox is half of the transactional outbox/inbox pattern; the outbox adapter was already in `messaging/` (alongside `*OutboxConfig`). Moved `JdbcInboxAdapter` to `infrastructure/messaging/` in all six consumer services (sales, purchasing, inventory, manufacturing, finance, reporting). Reporting had no `messaging/` package yet (it's inbox-only) — created the directory.
- **Saga adapter belonged in `saga/`, not `persistence/`.** Saga workers (`SalesOrderFulfilmentSagaWorker`, `MakeToOrderSagaWorker`, `PurchaseToPaySagaWorker`) already lived in `infrastructure/saga/`; the JDBC adapters that backed them sat in `infrastructure/persistence/`. Moved the three `Jdbc<Flow>SagaAdapter` classes into `infrastructure/saga/` next to their workers.

Mechanical: 9 file moves total (6 inbox + 3 saga), each with package declaration rewritten in place. No external code imports these concrete classes (everyone goes through `InboxPort` / `*SagaPort` via Spring DI), so no import statements elsewhere needed updating.

`mvn clean install -DskipTests` green across all 10 modules.

After this slice, the per-service `infrastructure/` layout is:
- `persistence/` — domain aggregate JDBC repositories only
- `messaging/` — `JdbcInboxAdapter` + `Jdbc<Service>OutboxAdapter` + `<Service>OutboxConfig`
- `saga/` — `Jdbc<Flow>SagaAdapter` + `<Flow>SagaWorker` (services that own a flow)

CLAUDE.md's DDD layering block updated to reflect the new tree.

## 2026-05-05 — Inbox/Outbox/Saga adapters renamed to `*Adapter`; service-side saga narrowings to `*Port`

The previous "Rename abstract ports to `*Port`" slice landed `InboxPort` / `OutboxPort` / `SagaPort` for the abstractions but kept `JdbcInboxRepository` / `Jdbc<Service>OutboxRepository` / `Jdbc<Flow>SagaRepository` for the implementations. That mixed vocabularies on the same role: "Repository" is a Spring/DDD term that fits *implementations and domain aggregates*; "Port" is the Hexagonal architectural term for *abstractions*. Pairing `*Port` (Hexagonal abstraction) with `Jdbc*Repository` (Spring impl) put two architectural vocabularies on one type pair, while the `*QueryPort` interfaces in reporting were already pure Hexagonal+CQRS without that mismatch.

Strict Hexagonal pairing instead: **Port (interface) + Adapter (class)**. Renamed:

- 6 × `JdbcInboxRepository` → `JdbcInboxAdapter` (one per service that consumes events)
- 6 × `Jdbc<Service>OutboxRepository` → `Jdbc<Service>OutboxAdapter` (product, sales, inventory, manufacturing, purchasing, finance)
- 3 × `Jdbc<Flow>SagaRepository` → `Jdbc<Flow>SagaAdapter` (sales fulfilment, make-to-order, purchase-to-pay)

Also renamed the **service-side saga interface narrowings** from `*Repository` to `*Port`, since their role is "abstract port over saga state" (they extend `SagaPort<S>` and add a domain-flavoured lookup like `findBySalesOrderId(...)`):

- `SalesOrderFulfilmentSagaRepository` → `SalesOrderFulfilmentSagaPort`
- `MakeToOrderSagaRepository` → `MakeToOrderSagaPort`
- `PurchaseToPaySagaRepository` → `PurchaseToPaySagaPort`

Adapter classes still carry Spring's `@Repository` annotation (for exception translation) — only the *class name* changed.

**Domain aggregate repositories untouched.** `ProductRepository`, `WorkOrderRepository`, `SalesOrderRepository`, `SupplierInvoiceRepository`, `JournalEntryRepository`, `PaymentRepository`, etc., plus their `Jdbc*Repository` impls, all stay as-is. Spring/DDD vocabulary on both sides of that pair, no mismatch to fix.

Mechanical sweep with word-boundary regex: 18 file renames, 45 `.java` files rewritten. CLAUDE.md naming sub-section rewritten to a **four-row table** capturing the role↔interface↔impl mapping (Port/Adapter for infrastructure machinery, Repository for domain CRUD, QueryPort for CQRS reads, Lookup for narrow value resolution).

`mvn clean install -DskipTests` green across all 10 modules; per-service `test-compile` green.

## 2026-05-05 — Bring `test-bootstrap.sql` back in sync with inventory's Liquibase changesets

`mvn clean install` (without `-DskipTests`) failed inside Failsafe's `ReorderPolicyChangedSeamIT` with `relation "inventory.stock_balance" does not exist`. The seam test boots inventory-service against a fresh Testcontainer with `test-bootstrap.sql` (a hand-written minimal schema), then Liquibase replays inventory's master changelog on top. The bootstrap had drifted: it covered `inventory.stock_item` + `inventory.inbox_message` + shared functions, but two changesets shipped after it referenced tables it didn't define — `2026-05-04-add-cabinet-sub-assembly-fixtures.sql` INSERTs into `inventory.stock_balance`, and `2026-05-05-add-wip-balance.sql` declares an FK to `inventory.warehouse(warehouse_id)`. Both blew up at boot.

Fix: added `inventory.warehouse` (with the seed warehouse row at UUID `…0020` that the cabinet INSERTs reference), `inventory.stock_balance`, and `inventory.outbox_message` (+ sequence + default partition) to `test-bootstrap.sql`. The outbox table isn't referenced by any changeset, but the `@Profile("kafka")`-gated `OutboxPublisher`'s `@Scheduled` drainer fires once per second after Spring starts, so its absence produced an alarming `relation inventory.outbox_message does not exist` ERROR-level stack trace each tick (test still passed because the scheduler swallows the exception). Adding the empty table silences the noise.

Also rewrote the bootstrap header comment so future readers know the rule: "when you add a new changeset that references a table not yet listed here, add the table here too."

`mvn clean install` (full reactor, all tests) green: `Tests run: 1, Failures: 0, Errors: 0` for the seam IT, all 10 modules SUCCESS.

## 2026-05-05 — Auto-seed Postgres from v3 baseline; tidy stale `InProcessEventBus` comment

`demo-script.md` claimed "Postgres seeds itself on first start from `db/northwood_erp_v3.sql`" but `docker-compose.yml` never mounted the file into `/docker-entrypoint-initdb.d/`, so a fresh data volume came up with an empty `northwood_erp` database. Liquibase's first delta changeset (`2026-05-04-add-cabinet-sub-assembly-fixtures.sql`) then blew up with `ERROR: relation "inventory.stock_balance" does not exist`. The DB had been working only because v3.sql had been loaded by hand at some point on the original volume; a `docker compose down -v` was enough to surface the gap.

Fix: added `./db/northwood_erp_v3.sql:/docker-entrypoint-initdb.d/01-northwood_erp_v3.sql:ro` to the postgres service's volume list. The official postgres image runs anything under that directory exactly once, on first boot of a fresh data volume — exactly the behaviour the docs already promised.

Bundled tidy: the kafka service's comment block still claimed *"InProcessEventBus stays as the default for dev / tests, so this broker is unused unless SPRING_PROFILES_ACTIVE=kafka"* — same misleading claim we cleaned up in CLAUDE.md and demo-script.md last slice. Rewrote it to match: no event bus is registered under the default profile at all; Kafka is required for cross-service flow.

Recovery for an existing container that still has an empty DB (no need to wipe the volume): `Get-Content db\northwood_erp_v3.sql -Raw | docker exec -i northwood-postgres psql -U postgres -d northwood_erp`. After that, Liquibase's bookkeeping tables get created on next service boot and only the actual deltas apply.

## 2026-05-05 — Rename abstract ports to `*Port` (keep `*Repository` for adapters and domain)

> **Partially superseded later the same day** — the inbox/outbox/saga **JDBC adapter classes** were subsequently renamed from `Jdbc*Repository` to `Jdbc*Adapter` (and the service-side saga interface narrowings from `*SagaRepository` to `*SagaPort`). See "Inbox/Outbox/Saga adapters renamed to `*Adapter`" entry above. The abstract `InboxPort` / `OutboxPort` / `SagaPort` names from this slice were retained.

The previous two slices renamed `*QueryPort` → `*Repository` for read+write ports based on Spring/JPA convention. Stepping back, that mixed two architectural vocabularies on the same file: "Repository" is a Spring/DDD term that fits *implementations and domain aggregates*, while "Port" is the Hexagonal Architecture term that fits *abstract contracts*. Since the reporting service already used `*QueryPort` for abstract read-only ports, the codebase had an inconsistency — read-side abstractions were "Port" but read+write abstractions were "Repository," even though both are at the same architectural level.

Renamed three abstract interfaces in `shared-infrastructure`:

- `inbox/InboxRepository` → `inbox/InboxPort`
- `outbox/OutboxRepository` → `outbox/OutboxPort`
- `saga/SagaRepository` → `saga/SagaPort`

Concrete adapter and domain-narrowing names **kept as `*Repository`** because they're either Spring data-access implementations (`JdbcInboxRepository`, `Jdbc<Service>OutboxRepository`) or service-side narrowings that add domain-flavoured lookups (`SalesOrderFulfilmentSagaRepository extends SagaPort<...>`). Word-boundary regex (`\bInboxRepository\b` etc.) handled the rename without touching the longer concrete names.

Also unified field names on the type names: `inboxRepository` → `inboxPort`, `outboxRepository` → `outboxPort` (Java convention is field name = camelCase type name).

Mechanical sweep: 3 file renames, 98 `.java` files rewritten. CLAUDE.md prose updated, plus a new **Naming conventions** sub-section under Outbox/Inbox so future readers see the three-suffix system (`*Port` for Hexagonal abstractions, `*Repository` for Spring/DDD data access, `*QueryPort` for read-only CQRS ports) without having to reverse-engineer it.

`mvn clean install -DskipTests` green across all 10 modules; per-service `test-compile` green.

## 2026-05-05 — Rename `SagaQueryPort` to `SagaRepository`

> **Superseded later the same day** — `SagaRepository` was renamed again to `SagaPort` (see "Rename abstract ports to `*Port`" entry above). Service-side `*SagaRepository` narrowings retained.

Same misnomer as the inbox/outbox ports were before — `SagaQueryPort` exposed `claimDue` (read-with-update via `FOR UPDATE SKIP LOCKED` + lease stamping), `save`, `insert`, `findBySagaId`. Half writes, half reads. Renamed to `SagaRepository`, matching the convention now used by inbox/outbox and by the three service-specific saga repositories that already extended it (`SalesOrderFulfilmentSagaRepository`, `MakeToOrderSagaRepository`, `PurchaseToPaySagaRepository`) — the abstract port was the odd one out.

Mechanical sweep: interface file renamed; 6 `.java` files updated (extends declarations, javadoc references, `SagaPollingWorker`'s constructor parameter and field type). CLAUDE.md prose updated. No field/parameter names were affected — `SagaPollingWorker` already named the field `port`, not `sagaQueryPort`.

`mvn clean install -DskipTests` green across all 10 modules.

## 2026-05-05 — Rename `InboxQueryPort` / `OutboxQueryPort` to `*Repository`

> **Partially superseded later the same day** — the abstract `InboxRepository` / `OutboxRepository` interfaces were renamed again to `InboxPort` / `OutboxPort` (see "Rename abstract ports to `*Port`" entry above). The concrete `JdbcInboxRepository` / `Jdbc<Service>OutboxRepository` adapter class names from this slice were retained.

Both ports were misnamed: "Query" conventionally means read-only (CQRS pairs it with "Command"), but each interface exposes writes (`recordProcessed`, `save`). Renamed to `InboxRepository` / `OutboxRepository`, which aligns with Spring/JPA convention for read+write data-access ports and matches the naming used by service-side aggregates (`SalesOrderRepository`, `WorkOrderRepository`, etc.).

Mechanical sweep across the reactor:
- 14 files renamed: the 2 interfaces + 6 `JdbcInboxQueryPort` impls + 6 `Jdbc<Service>OutboxQueryPort` impls (now `JdbcInboxRepository` / `Jdbc<Service>OutboxRepository`).
- 92 `.java` files updated (type references, field/parameter names: `inboxQueryPort` → `inboxRepository`, etc.).
- CLAUDE.md prose references updated.

`mvn clean install -DskipTests` green; per-service `test-compile` green.

Out of scope (not renamed): the read-only reporting ports (`SalesOrder360QueryPort`, `PurchaseOrderTrackingQueryPort`, `ProductionPlanningQueryPort`, `MaterialShortageQueryPort`, `AvailableToPromiseQueryPort`, `FinancialDashboardQueryPort`, `ApprovedVendorQueryPort`) — those are genuinely query-only and `*QueryPort` is honest for them. `SagaQueryPort` had the same misnomer issue (mixes `claimDue` reads with `save` / `insert` writes); renamed to `SagaRepository` in the next slice.

## 2026-05-05 — Extract `source-service` envelope-header name to a constant

`"source-service"` was a magic string repeated in three places: `OutboxPublisher` (writes the header), `KafkaEventPublisher` (reads it to derive the topic), and `ReorderPolicyChangedSeamIT` (faked the producer-side write). A typo at any one site would silently route to the wrong topic, since the reader only fails when the header is missing — not when it's misspelled.

Added `public static final String HEADER_SOURCE_SERVICE = "source-service"` on `EventEnvelope` (the wire format already shared between writer and reader, so no new file needed). Updated all three call sites; the error message in `KafkaEventPublisher` now interpolates the constant rather than hardcoding the header name a second time.

`mvn clean install -DskipTests` green across all 10 modules; `mvn -pl inventory-service clean test-compile` green for the seam test.

## 2026-05-05 — Move Kafka adapter classes into their own sub-package

`com.northwood.shared.infrastructure.messaging` previously mixed the bus-agnostic port (`EventPublisher`, `EventEnvelope`, `InboxEnvelopeHandler`, plus the dead-code `InProcessEventBus`) with the Kafka-specific adapter (`KafkaEventPublisher`, `KafkaInboxDispatcher`, `KafkaMessagingAutoConfiguration`). Split the three Kafka classes into a new `messaging.kafka` sub-package so the port/adapter boundary is visible from the package tree.

Moved with package declaration + import fixes (the three classes now explicitly import `EventEnvelope`/`EventPublisher`/`InboxEnvelopeHandler` from the parent package). Updated `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` to point at the new FQN of `KafkaMessagingAutoConfiguration`. No other code in the reactor referenced these classes by FQN — nothing else needed changing.

`mvn clean install -DskipTests` green across all 10 modules.

Open follow-up (§3.12 in dev-todo.md): decide the fate of the dead `InProcessEventBus` (wire it up as the actual default `EventPublisher`, or delete it). Independent of this slice.

## 2026-05-03 — Schema rename to singular + master-detail `_header` suffix

Tables, indexes, triggers, sequences, and partitions renamed across all 7 schemas. Per-service Liquibase changesets at `2026-05-03-rename-tables-to-singular.sql` use idempotent `ALTER ... IF EXISTS RENAME TO` so they no-op against fresh DBs and apply cleanly to existing dev DBs. `validCheckSum:ANY` added to the two 2026-05-02 changesets so existing dev DBs don't choke on the post-rename SQL bodies. JDBC repo SQL in product-service updated. Doc references in CLAUDE.md, README.md, and user-stories.md updated.

## 2026-05-03 — Column rename follow-up

PKs on `_header` tables renamed to `<header>_id`; FKs on related tables follow; soft cross-schema FKs renamed to match. `finance.gl_account.account_id` → `gl_account_id` plus consequential rename on `journal_entry_line`. Two bonus table renames: `manufacturing.bill_of_material_header` → `manufacturing.bom_header`; `manufacturing.work_order_header` → `manufacturing.work_order`.

`db/northwood_erp_v3.sql` rewritten in place — fresh DBs born with final names. Per-service idempotent Liquibase changesets `2026-05-03-rename-pk-fk-columns.sql`. Five PL/pgSQL trigger functions in `finance` recreated with updated bodies (column rename does not auto-update function bodies — see CLAUDE.md gotcha). Java SQL strings updated.

Smoke-tested: order placed, saga ran through `started → stock_reservation_requested → stock_reserved`, header projected to `in_fulfilment`.

## 2026-05-03 — Kafka stage as primary messaging backbone

Three decisions locked: (1) profile-switched bus, `InProcessEventBus` stays default, `KafkaEventPublisher` `@Profile("kafka")`; (2) wire format JSON via Jackson 3 (no schema registry); (3) topic naming per-aggregate-context, keyed by aggregate ID. Spring Boot 4.0.5 BOM manages spring-kafka 4.0.4 / kafka-clients 4.1.2.

Implemented as steps 1–7 of the Shape A initial slice:

1. `apache/kafka:4.1.2` (KRaft mode, single broker) added to `docker-compose.yml`. `spring-boot-starter-kafka` added to shared-infrastructure.
2. Re-introduced `reorder_point` / `reorder_quantity` on `product.product` as data of record. Backfill changeset `2026-05-03-add-reorder-to-product.sql`.
3. `setReorderPolicy` command + `ReorderPolicyChanged` event on product side. Aggregate method `Product.changeReorderPolicy(...)` (with discontinued-status guard + non-negative validation). New endpoint `PUT /api/products/{id}/reorder-policy`. Surfaced + fixed a pre-existing bug where `version=0` was being written on insert (changeset `2026-05-03-bump-seed-product-versions.sql`).
4. inventory-service flesh-out: domain (`StockItem` + `StockItemId` + `StockTrackingMode` + `StockItemRepository`), application (`StockProjectionService` + `ReorderPolicyChangedHandler` idempotent inbox + local payload DTO), infrastructure, api. Liquibase changeset `2026-05-03-bump-seed-stock-item-versions.sql` mirrors product's seed-version fix.
5. `KafkaEventPublisher` (publishes to `<service>.events`, keyed by aggregate ID, JSON envelope) + `KafkaInboxDispatcher` registered as profile-gated beans in `KafkaMessagingAutoConfiguration`. Per-service `application-kafka.yml`.
6. Single Testcontainers IT `ReorderPolicyChangedSeamIT` covering produce → consume → project, in inventory-service. Lifecycle via `Startables.deepStart(...)` + manual bootstrap (Testcontainers 1.20.4 `withInitScript` is broken). Failsafe wired in inventory-service pom under `integration-test`/`verify` so `mvn test` stays fast.
7. Side fix bundled: rename-triggers Liquibase changesets across all six services had `EXCEPTION WHEN undefined_object`; broadened to `WHEN undefined_object OR undefined_table`.

`docker-compose.yml` fix: single-broker overrides (`KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1` etc.) added — without these, internal topics fail to create and consumer groups silently hang on `FIND_COORDINATOR`. Wiped the kafka volume during the fix.

## 2026-05-03 — Saga workers, first slice (sales fulfilment)

Reusable base in `shared-infrastructure/.../saga/`: `SagaInstance`, `SagaQueryPort<S>`, `SagaPollingWorker<S>`. Sales-service flesh-out: `Customer` lookup port, `SalesOrder`/`SalesOrderLine` aggregate, `SalesOrderPlaced` + `StockReservationRequested` events, `JdbcSalesOrderFulfilmentSagaRepository` (claim-with-RETURNING + lease stamping), `SalesOrderService.placeOrder(...)`, `POST /api/sales-orders`. Sales saga worker polls `started` rows, emits `sales.StockReservationRequested`, transitions to `stock_reservation_requested`. Inventory side: `StockReservation` aggregate + service + handler. Sales-side `StockReservedHandler` consumes `inventory.StockReserved`.

Smoke-tested under kafka profile: `POST /api/sales-orders` → 201; saga advanced `started → stock_reservation_requested → stock_reserved`; `inventory.stock_balance.reserved_quantity` for FG-TABLE-001 went 0→1; both outboxes show events `published`.

Sales-side projection of saga progress onto `sales_order_header.status` (in/rejected) bundled here.

## 2026-05-03 — Manufacturing first slice (make-to-order saga, first transition)

Schema: `manufacturing.work_center`, `routing_header` (+ active-routing partial unique index), `routing_operation`, `work_order_operation`. Seed: `WC-ASSEMBLY` + active routing for FG-TABLE-001 with four operations (CUT 30, DRILL 20, ASSEMBLE 60, FINISH 45). Companion changeset adds `current_step` to `manufacturing.make_to_order_saga` and `purchasing.purchase_to_pay_saga`.

Manufacturing-service flesh-out: `WorkOrder` aggregate (header + materials + operations), `Routing` + `RoutingOperation`, `BomLookup` port, `WorkOrderCreated` event, `MakeToOrderSaga` (extends `SagaInstance` with `attachWorkOrder`), `MakeToOrderSagaRepository`. `WorkOrderReleaseService.release(...)` looks up active BOM + Routing, snapshots material requirements (qty × scrap factor) and operations. `ManufacturingRequestedHandler` consumes `sales.ManufacturingRequested`, filters by "has active BOM", inserts saga rows in `started`. `MakeToOrderSagaWorker` claims `started` rows, calls release, transitions to `work_order_created`.

Sales-side forward: new `ManufacturingRequested` event. Sales saga worker handles `stock_reserved → manufacturing_requested`.

End-to-end smoke 2026-05-03 against four services on kafka profile.

## 2026-05-03 — Operation completion + sales-saga reaction (manufacturing 2nd sub-slice)

`manufacturing.OperationCompleted` and `manufacturing.WorkOrderManufacturingCompleted` events. `WorkOrderOperation` mutable for completion via package-private `markCompleted`. `WorkOrder.completeOperation(sequence, actualMinutes)` enforces strict-increasing sequence ordering, transitions WO to `in_progress` on first op (sets `actual_start_at`) and `completed` on the last; emits `OperationCompleted` per op + `WorkOrderManufacturingCompleted` on the last.

`WorkOrder.reconstitute` factory + `WorkOrderRepository.findById` + `JdbcWorkOrderRepository` insert/update split. `POST /api/work-orders/{id}/operations/{sequence}/complete` (with `actualMinutes`) returns 204 / 404 / 409. When the WO transitions to `completed`, the local `make_to_order_saga` is advanced to `completed` in the same transaction.

Sales-side reaction: `WorkOrderCreatedHandler` advances `manufacturing_requested → manufacturing_in_progress`. `WorkOrderManufacturingCompletedHandler` single-hops past `manufacturing_completed` to `ready_to_ship`.

## 2026-05-03 — Multi-line saga reconciliation

Approach taken: incremental tracking via incoming events rather than upfront filtering. `FulfilmentSagaData` extended with `outstandingWorkOrderIds` + `completedWorkOrderIds` sets. `WorkOrderCreatedHandler` adds the WO id to outstanding (and flips state to `manufacturing_in_progress` on the first event, idempotent for siblings). `WorkOrderManufacturingCompletedHandler` moves the WO id outstanding → completed and only single-hops to `ready_to_ship` when outstanding is empty AND completed non-empty. `SagaDataIO` helper centralises the JSON round-trip.

Smoke-tested with a 2-line FG-TABLE-001 order: WO1 completion alone holds the saga at `manufacturing_in_progress`; only after WO2's completion does it advance to `ready_to_ship`.

## 2026-05-03 — Raw-material reservation flow

New event `manufacturing.RawMaterialReservationRequested` (carries the WO's BOM components by `work_order_material_id`). Inventory's `StockReservation` aggregate gains a `forWorkOrder(...)` factory; new `inventory.RawMaterialsReserved` event. `StockReservationService.reserveForWorkOrder` shares per-line reservation logic with the sales path.

The MTO saga worker is now active in `work_order_created` too: loads the WO via `WorkOrderRepository.findById`, builds the request from snapshotted materials, writes to outbox, transitions to `raw_material_reservation_requested`. Manufacturing's `RawMaterialsReservedHandler` advances to `raw_materials_reserved` (full success) or `raw_material_shortage` (partial / failed).

Smoke-tested both happy path and shortage path.

## 2026-05-04 — Pricing facet (Shape A consumer-side, sales)

Sales-side projection of `product.product.sales_price` + `currency_code` into `sales.product_pricing(product_id PK, sales_price, currency_code, updated_at)`. `ProductPricingChangedHandler` consumes `product.ProductPricingChanged`. `ProductPricingProjectionService` does the upsert. Sales' kafka subscribe-topics now includes `product.events`. Smoke-tested 2026-05-04: PUT pricing for FG-TABLE-001 → product DB updated, event published, `sales.product_pricing` row went 650 → 725.

**Decision locked**: with two facets (reorder + pricing) live as single columns on `product.product`, stay with single columns going forward — no `product_planning` / `product_pricing` sub-tables. Revisit only if a future facet has its own multi-row shape.

## 2026-05-04 — Make-vs-buy classification (manufacturing side)

Producer: `Product.changeMakeVsBuy(isPurchased, isManufactured)` with discontinued-status guard plus an at-least-one-source invariant. `MakeVsBuyChanged` event carrying old + new flags. `PUT /api/products/{id}/make-vs-buy` + `ChangeMakeVsBuyRequest`. Manufacturing-side projection: `manufacturing.product_replenishment(product_id PK, is_purchased, is_manufactured, updated_at)`.

`ManufacturingRequestedHandler` now checks the projection FIRST (before the BOM presence check). Lines whose product has `is_manufactured=false`, OR no projection row at all, get a new dispatched outcome `rejected_not_manufactured`. The all-rejected case continues to flip the sales saga to `stock_reservation_failed`.

Smoke-tested 2026-05-04. Purchasing-side projection deferred until purchasing-service flesh-out (the producer is ready).

**Newly registered products are unsourceable until make-vs-buy is set** — `ProductCreated` doesn't include flags, so `manufacturing.product_replenishment` has no row at registration. Defer until product-service grows a creation user story that reveals the right ergonomics.

## 2026-05-04 — Order-line price validation against `sales.product_pricing`

`ProductPricingLookup` port + `JdbcProductPricingLookup`. `SalesOrderService.placeOrder` resolves per-line price as: `unitPrice == null` → auto-fill from catalog (throws `UnknownPriceException` → 400 if no projection row); `unitPrice != null` → accept as-is (override). When the catalog has a row, the order's `currencyCode` is verified against the catalog's; mismatches throw `CurrencyMismatchException` → 400.

Smoke-tested 2026-05-04: auto-fill, override, USD-on-AUD mismatch.

## 2026-05-04 — BOM cycle prevention

`BomCycleDetector` port + `JdbcBomCycleDetector` impl using a PostgreSQL recursive CTE that walks descendants through the active-BOM graph (plus an optional candidate BOM treated as active for the duration of the walk). `UNION` (not `UNION ALL`) so the CTE deduplicates and terminates even if data corruption already produced a cycle.

`BomEditService` exposes three commands wrapped in REST endpoints under `/api/boms`:
- `POST /api/boms/{bomHeaderId}/lines` validates BOM is non-active, short-circuits trivial single-node cycles, runs the cycle detector, throws and rolls back on detection.
- `DELETE /api/boms/lines/{bomLineId}`: deletes a line from a non-active BOM.
- `POST /api/boms/{bomHeaderId}/activate` flips a draft to active (partial unique index `uq_bom_active_per_product` enforces "at most one active per finished_product_id"), then walks every line through the detector.

Smoke-tested 2026-05-04: cabinet → drawer-draft cycle returned 409; self-reference returned 409; valid drawer-draft → screws returned 201.

## 2026-05-04 — Sub-assembly recursion (manufacturing)

`WorkOrderReleaseService` walks the active BOM and, for `component_kind = 'sub_assembly'` lines, recurses into `releaseInternal(...)` to spawn a child WO with `parent_work_order_id` set. Each recursion picks up the sub-assembly's own active BOM + Routing snapshot. Sub-assembly bom_lines do NOT become `work_order_material` rows on the parent — only `raw` lines do. Each child WO also gets its own `make_to_order_saga` row inserted at `work_order_created` (factory `MakeToOrderSaga.attachedToWorkOrder(...)`).

Sales-side filtering: `WorkOrderCreated` and `WorkOrderManufacturingCompleted` carry a nullable `parentWorkOrderId`; sales handlers ignore non-null entries so the multi-line tracker only counts top-level WOs. Parent-on-children gate: `WorkOrder.completeOperation(seq, mins, noPendingChildren)` holds the parent at `in_progress` if children remain; `WorkOrder.onChildCompleted(boolean)` is the matching trigger.

Smoke-tested 2026-05-04 with 1× FG-CABINET-001.

## 2026-05-04 — `stock_reservation_failed` reframed (sales saga)

Inventory's `failed` reservation no longer dead-ends the saga; `StockReservedHandler` treats it the same way as `partially_reserved`: stash the per-line shortage map and transition to `stock_reserved` so the worker emits `ManufacturingRequested` for the missing stock. The saga state `stock_reservation_failed` still exists, kept for the genuinely-rejected case below.

## 2026-05-04 — "No active BOM on any line" silent hang

Manufacturing's `ManufacturingRequestedHandler` now always emits a `manufacturing.ManufacturingDispatched` event after deciding which lines it can fulfil. Per-line outcomes are `accepted` or `rejected_no_bom`. Sales-side `ManufacturingDispatchedHandler` reacts only to the all-rejected case: when zero lines were accepted AND saga is still in `manufacturing_requested`, it transitions to `stock_reservation_failed (current_step=no_manufacturable_lines)` and projects the order header to `rejected`.

Smoke-tested 2026-05-04.

## 2026-05-04 — Reporting view column rename

Reporting-service first slice surfaced legacy `sales_order_id` etc. on the projection views. New per-service changeset `reporting-service/.../changes/2026-05-04-rename-reporting-view-columns.sql` idempotently renames `sales_order_360_view.sales_order_id → sales_order_header_id`, `production_planning_board.sales_order_id → sales_order_header_id`, `purchase_order_tracking_view.purchase_order_id → purchase_order_header_id` (+ `last_goods_receipt_id` and `last_supplier_invoice_id` to `_header_id`).

## 2026-05-04 — Purchasing flesh-out, phase 1 (Supplier + Requisition triggered by shortage)

Manufacturing → purchasing event bridge live. Purchasing-service no longer a skeleton.

`Supplier` read aggregate + `SupplierRepository` (`defaultSupplier()`) + `JdbcSupplierRepository`. `PurchaseRequisition` aggregate (header + lines) — auto-approves at creation. Manual `POST /api/purchase-requisitions`. Domain event `purchasing.PurchaseRequisitionCreated`. New event `manufacturing.RawMaterialShortageDetected` emitted by `RawMaterialsReservedHandler` (same txn as transition to `raw_material_shortage`). Purchasing-side `RawMaterialShortageDetectedHandler` attaches default supplier, creates a `work_order_shortage` requisition.

Smoke-tested 2026-05-04 (manual + end-to-end shortage flow).

## 2026-05-04 — Purchasing flesh-out, phase 2 (PO + P2P saga started)

End-to-end producer → PR → PO → saga in one transaction per shortage event.

`PurchaseOrder` aggregate (header + lines) auto-creates at status `'sent'`. `PurchaseOrderService.convertFromRequisition(prId)` — single use case loads PR, picks supplier, builds PO lines, persists (emits `PurchaseOrderCreated`), marks PR `converted`, inserts the saga at `started`. `PurchaseRequisitionService.createManual` and `createForWorkOrderShortage` chain into `convertFromRequisition` immediately. `PurchaseToPaySaga` extends `SagaInstance`. `PurchaseToPaySagaWorker` transitions `started → waiting_for_goods`.

PO line `unit_price` was hardcoded to 0 (PR-line schema doesn't carry a price column and there's no supplier-price-list integration). Fixed in the supplier-pricing slice below.

Smoke-tested 2026-05-04 (manual + end-to-end).

## 2026-05-04 — Purchasing flesh-out, phase 3 (Goods receipt + manufacturing unblock)

End-to-end shortage→fulfilment loop closed. `goods_receipt_header` lives in `inventory` (not purchasing); event name `inventory.GoodsReceived`.

`GoodsReceipt` aggregate (header + lines, post-only). `GoodsReceiptService.post(...)` runs three writes in one transaction: persist receipt + lines, bump `inventory.stock_balance.on_hand_quantity` per line, emit `inventory.GoodsReceived`. `POST /api/goods-receipts`. Purchasing-side `GoodsReceivedHandler` matches receipt lines against `purchase_order_line.received_quantity`, reclassifies PO header status, advances P2P saga `waiting_for_goods → goods_received` on full receipt. Manufacturing-side `GoodsReceivedHandler` finds MTO sagas in `raw_material_shortage` whose materials reference any received `product_id` and transitions them back to `work_order_created`.

**Inventory retry-after-shortage fix:** the schema's UNIQUE on `stock_reservation_header.work_order_id` blocked a second reservation attempt. `StockReservationService.reserveForWorkOrder` now calls `cancelPriorReservationFor(workOrderId, warehouseId)` before each attempt: rolls back the reserved_quantity bumps, deletes both prior lines + header, then a fresh reservation is created.

Smoke-tested 2026-05-04 (full shortage→fulfilment loop).

## 2026-05-04 — Purchasing flesh-out, phase 4 (Supplier invoice + 3-way match)

Finance-service comes online. Supplier invoice posts trigger qty-only 3-way match against `finance.purchase_order_line_facts` projection.

Projection: `finance.purchase_order_line_facts(purchase_order_line_id PK, ...)`. Finance-side handlers: `PurchaseOrderCreatedHandler` seeds rows; `GoodsReceivedHandler` bumps `received_quantity`. `SupplierInvoice` aggregate (header + lines, post-only). `SupplierInvoiceService.recordInvoice(...)` aggregates invoice quantities by PO line, queries `purchase_order_line_facts` per PO line, and decides match per algorithm: cumulative `invoiced_quantity` after this invoice must not exceed `received_quantity`. On match, emits `finance.SupplierInvoiceApproved`. Purchasing-side `SupplierInvoiceApprovedHandler` advances P2P saga `goods_received → supplier_invoice_approved`.

Match is quantity-only; price match is skipped because PO `unit_price` is still 0 (resolved in supplier-pricing slice below).

Smoke-tested 2026-05-04.

## 2026-05-04 — Purchasing flesh-out, phase 5a (Outgoing supplier payment + saga to completed)

`Payment` aggregate (header + allocations as a value object) + `PaymentRepository`. `PaymentService.recordSupplierPayment(...)` validates the invoice is in `approved` or `partially_paid`, computes the post-payment status, persists the payment + allocation, emits `finance.SupplierPaymentMade` carrying `purchaseOrderHeaderId` (for saga routing) and `invoiceStatusAfter`.

DB trigger `maintain_allocation_totals` does the bookkeeping atomically: bumps `payment.amount_allocated`, the invoice's `paid_amount`, and flips invoice status to `paid`/`partially_paid`. `POST /api/payments`. Purchasing-side `SupplierPaymentMadeHandler` consumes the event. On full settlement: P2P saga single-hops `supplier_invoice_approved → completed (p2p_completed)`, PO header flips to `paid`. On partial: `supplier_payment_made` parks.

Smoke-tested 2026-05-04 (full P2P loop).

## 2026-05-04 — Purchasing flesh-out, phase 5b (GL posting + CurrencyConverter)

Real double-entry accounting. Four balanced debit/credit pairs post to `finance.journal_entry_header` + `_line` in same txn as the originating service write.

`CurrencyConverter` port + `JdbcCurrencyConverter`. Same-currency pass-through; cross-currency lookup against `finance.exchange_rate` with inverse-rate fallback. Throws `RateNotFoundException` if neither direction has a rate effective ≤ requested date. Triangulation through a base currency not implemented (out of scope).

`JournalEntry` aggregate + `JdbcJournalEntryRepository`. Three-step persist (header `'draft'` → lines → header `'posted'`) because `guard_journal_line_immutability` rejects line INSERTs on a `'posted'` parent. `enforce_journal_balance` deferred trigger fires at COMMIT. `JournalEntryService` four posting methods:

- Supplier invoice approval: Dr 5000 COGS / Cr 2100 AP (later changed in perpetual-inventory slice)
- Supplier payment: Dr 2100 AP / Cr 1000 Bank
- Customer invoice creation: Dr 1100 AR / Cr 4000 Revenue
- Customer payment: Dr 1000 Bank / Cr 1100 AR

`GlAccountLookup` resolves account_id + name by code (5 hot accounts: 1000, 1100, 2100, 4000, 5000).

Smoke-tested 2026-05-04: full P2P + O2C cycle for one cabinet order; trial balance reconciles, P&L = bank movement.

## 2026-05-04 — Purchasing flesh-out, phase 5c (Order-to-cash / customer flow)

Closes the full sales fulfilment loop end-to-end.

inventory: `Shipment` aggregate (header + lines, post-only), `ShipmentService.post(...)`, `POST /api/shipments`, domain event `inventory.ShipmentPosted`. **Stock-balance not adjusted on shipment in this slice** — the make-to-order flow doesn't currently bump FG stock_balance on manufacturing completion (resolved in the production-confirmation slice below).

sales: `SalesOrderShipped` event (richer than `ShipmentPosted`, carries per-line price + tax that finance needs). `ShipmentPostedHandler` advances saga `ready_to_ship → goods_shipped`, looks up order line pricing, emits `SalesOrderShipped`, projects header status to `shipped`.

finance: `CustomerInvoice` aggregate (header + lines, post-only). `SalesOrderShippedHandler` consumes `sales.SalesOrderShipped`, auto-creates the invoice at status `posted`, emits `finance.CustomerInvoiceCreated`. `PaymentService.recordCustomerPayment(...)` mirror of supplier path. New REST endpoint `POST /api/payments/customer`.

sales: `CustomerInvoiceCreatedHandler` advances saga `goods_shipped → invoice_created`. `CustomerPaymentReceivedHandler` on full settlement single-hops `invoice_created → completed (o2c_completed)` and projects header to `completed`. Partial → `invoice_paid` parks.

Smoke-tested 2026-05-04 (full O2C loop). Trial balance reconciles end-to-end.

## 2026-05-04 — Production-confirmation event

Closed two known gaps. Manufacturing's `WorkOrderManufacturingCompleted` is now also consumed by inventory: `WorkOrderManufacturingCompletedHandler` bumps `stock_balance.on_hand_quantity` for the finished good when a top-level WO completes. Sub-assembly children (non-null `parentWorkOrderId`) are filtered out — output consumed internally by parent.

`ShipmentService.post(...)` now decrements `on_hand_quantity` by shipped qty and releases any matching reservation by reducing `reserved_quantity` by `min(reserved, shipped)` in a single `UPDATE`.

Smoke-tested 2026-05-04: cabinet on_hand 0 → MTO chain → cabinet WO completes → on_hand bumps to 1 (drawer correctly skipped). Shipment posted → on_hand drops to 0.

## 2026-05-04 — Reporting service first slice (sales_order_360_view)

First non-skeleton wiring of `reporting-service`. Maintain `reporting.sales_order_360_view` from cross-context events emitted by every other producer service, plus a single read endpoint.

Five inbox handlers in `reporting/application/inbox/`: `SalesOrderPlacedHandler` (seeds), `WorkOrderManufacturingCompletedHandler` (filters out sub-assembly children), `ShipmentPostedHandler`, `CustomerInvoiceCreatedHandler`, `CustomerPaymentReceivedHandler`. All five share `AbstractProjectionHandler` for dedupe/deserialise/recordProcessed. `SalesOrder360ProjectionService` — idempotent INSERT … ON CONFLICT DO UPDATE on seed + status-only UPDATEs for the rest. `GET /api/sales-orders/{id}/360`. Reporting subscribes to `sales.events,inventory.events,manufacturing.events,finance.events`.

Smoke-tested 2026-05-04 (replayed 80 sales + 165 manufacturing + 66 inventory + 10 finance events; inbox dedupes correctly).

**Two bugs caught + fixed** during smoke test:
1. `final` on `AbstractProjectionHandler.handle()` prevented CGLIB from creating a transactional proxy — Objenesis-instantiated proxies arrived with null fields. Removed `final`. **Lesson:** never mark `final` a method that needs Spring AOP advice.
2. `reporting.sales_order_360_view`'s PK column was originally `sales_order_id` (legacy from before the rename). Renamed in the reporting view rename slice (above).

## 2026-05-04 — Reporting second slice (purchase_order_tracking_view)

Mirror of sales-360 on the P2P side. Confirms per-projection skeleton generalises. Four inbox handlers: `PurchaseOrderCreatedHandler` (seeds), `GoodsReceivedHandler`, `SupplierInvoiceApprovedHandler`, `SupplierPaymentMadeHandler`. `PurchaseOrderTrackingProjectionService` — every method uses `INSERT ... ON CONFLICT DO UPDATE` so handlers are **order-tolerant across topics**. `GET /api/purchase-orders/{id}/tracking`.

Bug caught: first version used UPDATE-only semantics (mirror of sales-360). When kafka topics replayed cold, `inventory.GoodsReceived` events landed before `purchasing.PurchaseOrderCreated` (different topics, no cross-topic ordering), and 5 receipts were silently lost. Refactored to UPSERT on every method.

The same latent issue against sales-360's `recordInvoice` / `recordPayment` was fixed in the same session: split string-templated upserts into per-event explicit methods (caught a Postgres "specified more than once" rejection from a templated column appearing twice in the SQL).

Smoke-tested 2026-05-04.

## 2026-05-04 — Supplier pricing

Closed the data-quality gap surfaced by the second reporting slice. PO line `unit_price` was hardcoded to 0; every `PurchaseOrderCreated.totalAmount` was 0 and `outstanding_amount` went negative when payments landed.

New table `purchasing.supplier_product_price (supplier_product_price_id PK, supplier_id, product_id, currency_code, unit_price, version, …)`. Seed: 6 rows, one per purchased product, all under `SUP-001`, AUD. `SupplierProductPriceLookup` port + `JdbcSupplierProductPriceLookup`. `PurchaseOrderService.buildLines` consults the lookup; missing-price tuples fall back to 0 with a WARN log.

Smoke-tested 2026-05-04: PO `subtotal_amount=800`, `total_amount=800` for 5 × RM-BOARD @ 80 + 16 × RM-LEG @ 25.

## 2026-05-05 — Supplier pricing — authoring path

`SupplierProductPriceService.setPrice(supplierId, productId, currencyCode, unitPrice)` upserts and emits `SupplierProductPriceChanged(eventId, aggregateId=priceId, supplierId, productId, currencyCode, oldUnitPrice, newUnitPrice, occurredAt)`. New REST endpoints `PUT /api/supplier-product-prices` and `GET /api/supplier-product-prices/by-supplier/{supplierId}`. Validation: positive unitPrice, currency code 3-letter pattern.

## 2026-05-05 — Supplier pricing — effective-date ranges + tiered quantity breaks

Liquibase changeset `2026-05-05-add-price-effectivity-and-tiers.sql` adds `effective_from` (DATE NOT NULL DEFAULT '1970-01-01' for safe backfill), `effective_to` (nullable DATE), `min_quantity` (NUMERIC NOT NULL DEFAULT 0). Drops old UNIQUE; adds `supplier_product_price_unique_tier` UNIQUE on `(supplier_id, product_id, currency_code, effective_from, min_quantity)`. New btree index on the lookup tuple. v3.sql baseline updated.

Lookup port extends with `findUnitPrice(supplierId, productId, currencyCode, at, quantity)` — picks row whose `[effective_from, effective_to)` covers `at` and has highest `min_quantity ≤ qty` (deepest applicable tier; ties broken by latest `effective_from`). `PurchaseOrderService.buildLines` and `quoteTotal` pass the requested quantity so tier discounts engage at PR→PO conversion.

## 2026-05-05 — Supplier pricing — multi-supplier comparison

`PurchaseOrderService.pickFromApprovedVendors` scores eligible suppliers (intersection of approved vendors across all PR lines) by total quote cost and picks cheapest. Supplier missing a price-list entry for any line falls out of contention. Two-tier preference: cheapest preferred-for-every-line first, then cheapest eligible. Falls back to suggested/default if no eligible supplier has a complete quote.

## 2026-05-05 — Stock movement audit trail

`StockMovementWriter` writes immutable audit rows to `inventory.stock_movement` (partitioned table existed in v3 but was unused) from each of the three sites that mutate `stock_balance.on_hand_quantity`: `GoodsReceiptService.post` (`purchase_receipt`/`in`), `ShipmentService.post` (`sales_shipment`/`out`), inventory's `WorkOrderManufacturingCompletedHandler` for top-level WOs (`finished_goods_receipt`/`in`). Same-transaction writes so audit and balance stay consistent. Sub-assembly WIP movements are NOT written (would need a parallel `wip_movement` table).

The full `StockBalance`/`StockMovement` *domain aggregate* refactor (wrapping JDBC writes in DDD aggregates) deliberately not done — pure code-organisation change with no functional gain.

## 2026-05-05 — Index name drift cleanup

Per-service Liquibase changesets `2026-05-05-rename-drifted-indexes.sql` (sales/inventory/manufacturing/finance) idempotently `ALTER INDEX IF EXISTS old_name RENAME TO new_name` for 5 indexes whose name suffix referred to the pre-rename column (`_sales_order_id` → `_sales_order_header_id`, etc.). Wrapped in PL/pgSQL EXCEPTION blocks so no-op safe on fresh DBs.

## 2026-05-05 — Manual review tooling for 3-way-match-failed supplier invoices

`SupplierInvoice.manualApprove(reason)` + `SupplierInvoice.manualReject(reason)`. Strict precondition: only allowed from `three_way_match_failed`. Manual approve flips to `'approved'` + `match_status='matched'` and emits `SupplierInvoiceApproved` (same event as auto-approve). Manual reject flips to `'cancelled'` (terminal); no event.

`SupplierInvoiceService.manualApprove(invoiceId, reviewer, reason)` — orchestrates aggregate update, repository save, projection bump, GL posting (Dr 1300 GRNI / Cr 2100 AP) in one txn.

New REST endpoints: `GET /api/supplier-invoices/pending-review`, `POST /api/supplier-invoices/{id}/manual-approve`, `POST /api/supplier-invoices/{id}/reject`. New DTO `ManualReviewRequest{reviewer, reason}`.

**Why no event on reject:** rejecting a 3-way-match-failed invoice is a terminal accounting decision but doesn't change the operational picture — PO is still received, saga still parked at `goods_received`, invoice just permanently cancelled. PO cancellation is a separate slice if needed.

## 2026-05-05 — Manufacturing BOM-create-draft endpoint

`BomEditService.createDraft(CreateBomDraftCommand)` inserts a row into `manufacturing.bom_header` at `status='draft'`, `row_version=0`. `POST /api/boms` with `CreateBomDraftRequest{finishedProductId, finishedProductSku, finishedProductName, version}`. Existing `addLine` / `removeLine` / `activate` operate on the returned `bom_header_id` — full lifecycle now reachable via REST.

## 2026-05-05 — Reporting third slice (production_planning_board)

Five inbox handlers + four new payloads: `WorkOrderCreatedHandler` (seeds at `work_order_status='released'`), `OperationCompletedHandler` (`released → in_progress`, idempotent), `BoardWorkOrderCompletedHandler` (distinct from sales-360's same-event consumer; flips to `'completed'` + final `completed_quantity`), `RawMaterialsReservedHandler` (maps `'reserved'`/`'partially_reserved'`/`'failed'` to `material_status`, clears stale shortage info on full reservation), `ShortageDetectedHandler` (records shortage_materials_count + comma-separated shortage_summary). `ProductionPlanningProjectionService` UPSERT on every method. `GET /api/work-orders/{id}/board`.

**Naming convention for cross-projection event reuse**: when the same event feeds two projections, prefix the second handler with the projection name (`BoardWorkOrderCompletedHandler` for production-planning); each gets its own consumer_name so inbox dedupe doesn't conflate them.

Smoke-tested 2026-05-05: 32 work orders projected; 19 completed, 21 fully reserved, 5 partially reserved, etc.

**Two bugs caught:**
1. Status-mapping mismatch — first version mapped inventory's status as `"full"`/`"partial"`/`"failed"`, but inventory emits `"reserved"`/`"partially_reserved"`/`"failed"`. **Lesson:** read the producer's source, not the schema CHECK.
2. Stale shortage info — when MTO saga's retry-cancel-recreate flow legitimately moved a WO from `'shortage'` → `'reserved'`, the projection retained the old `shortage_materials_count` + `shortage_summary`. Fixed by clearing those columns inside `recordRawMaterialsReserved` only when the new status is `'reserved'`.

## 2026-05-05 — Reporting fourth/fifth/sixth slices (material_shortage + ATP + financial_dashboard)

Closed out the four-projection backlog in one session.

**Code organisation change:** new handlers live in **sub-packages by projection** — `inbox/shortage/`, `inbox/atp/`, `inbox/dashboard/`. `AbstractProjectionHandler` bumped from package-private to `public`. Each sub-package handler carries explicit `@Component("prefix_HandlerClass")` bean name (Spring's default class-simple-name naming would collide).

**material_shortage_view**: 4 handlers — `ShortageDetectedHandler`, `PurchaseRequisitionCreatedHandler` (filtered to `sourceType='work_order_shortage'`), `PurchaseOrderCreatedHandler`, `GoodsReceivedHandler`. Status walks `open → purchase_requested → purchase_ordered → resolved`. Endpoints: `GET /api/material-shortages` (active by default; `?includeResolved=true`), `GET /api/material-shortages/{productId}`.

**available_to_promise_view**: 7 handlers in `inbox/atp/` covering every supply-chain event. Each method UPSERT with stub-row creation for events that don't carry product SKU/name. After every write, `available_quantity = max(on_hand - reserved_for_sales - reserved_for_production, 0)`. `GET /api/atp` and `/{productId}`. WorkOrderManufacturingCompleted carries only `completedQuantity`, not `plannedQuantity`, so handler clears completed (not planned) from `incoming_from_production` — fine for showcase.

**financial_dashboard_daily**: 7 handlers in `inbox/dashboard/`. Per-day delta semantics. `gross_profit` recomputes inside the same SQL on every revenue/cost write. PK is `(dashboard_date, currency_code)`. `GET /api/financial-dashboard?currency=AUD`, `/{date}`.

**One bug caught + fixed**: Spring's default `@Component` bean name is the class's lower-camel simple name. `GoodsReceivedHandler` in `inbox/` collided with `GoodsReceivedHandler` in `inbox/shortage/`. Startup failed with `ConflictingBeanDefinitionException`. Fixed by setting explicit `@Component("shortage_GoodsReceivedHandler")` etc. **Lesson:** same class-simple-name across packages doesn't auto-disambiguate.

## 2026-05-05 — Shape A producer-side facets (valuation_class, active_bom, approved_vendors)

Filled out the Shape A facet roster on product master. Six facets now wired (`ReorderPolicyChanged`, `ProductPricingChanged`, `MakeVsBuyChanged` were earlier; `ValuationClassChanged`, `BomActivated`, `ApprovedVendorListChanged` land now).

Liquibase changeset `2026-05-05-add-shape-a-facets.sql` adds two new columns to `product.product` (`valuation_class VARCHAR(50)`, `active_bom_id UUID`) plus new table `product.approved_vendor`. v3.sql baseline updated. Backfill seeds `valuation_class` for 8 existing products from their `product_type`.

Three events: `ValuationClassChanged`, `BomActivated`, `ApprovedVendorListChanged` (carrying full new list, not deltas — consumers replace projection in one statement). Three aggregate methods on `Product`. Three application methods on `ProductCatalogService`. Three REST endpoints.

## 2026-05-05 — Shape A consumer-side projections (purchasing/manufacturing/finance)

Each follows the established Shape A consumer pattern:

- **`ApprovedVendorListChanged → purchasing.product_approved_vendor`** — `ApprovedVendorListChangedHandler` invokes `replaceFor(productId, vendors)` which does `DELETE all + INSERT all`.
- **`BomActivated → manufacturing.product_active_bom`** — `BomActivatedHandler` invokes `apply(productId, newActiveBomId)`. Co-exists with manufacturing's own `bom_header.is_active` column.
- **`ValuationClassChanged → finance.product_valuation_class`** — informational only today.

Smoke-tested 2026-05-05.

**Bug caught:** `ProductApprovedVendorProjectionService.replaceFor` initially relied on schema default for `approved_vendor_id`. At runtime per-service connection's `search_path = purchasing, shared` doesn't include `public`, so unqualified `gen_random_bytes()` failed. Fixed by passing `UUID.randomUUID()` explicitly. **Reminder:** CLAUDE.md "Reference data and seed UUIDs" gotcha applies to projection services too.

## 2026-05-05 — Shape A consumer wiring (purchasing + manufacturing reads from projections)

- **`PurchaseOrderService.pickSupplier` consults `purchasing.product_approved_vendor`** — `ApprovedVendorQueryPort` + `JdbcApprovedVendorQueryPort`. `pickSupplier` intersects approved-vendor sets across all PR lines, prefers `is_preferred=true`, falls back to suggested/default if no shared approved vendor exists. Engineering quality gate now active at PR→PO conversion.
- **`WorkOrderReleaseService` reads `manufacturing.product_active_bom`** — `JdbcBomLookup.findActiveByFinishedProductId` consults the projection first, falls back to legacy `bom_header.status='active'` when no projection row. Backfill changeset `2026-05-05-backfill-product-active-bom.sql` seeds the projection from existing local active-flag state. Toggle in `BomEditService.activate(bomId)` deliberately NOT removed — both flag and projection stay consistent.

## 2026-05-05 — Sales fulfilment saga — cross-partition race fix

The known race: *"if WO1's Completed reaches sales before WO2's Created, `outstanding` is briefly empty and the saga advances prematurely."*

`FulfilmentSagaData` gains `Integer expectedWorkOrderCount` (nullable for back-compat; null = legacy outstanding-set logic). `ManufacturingDispatchedHandler` counts `accepted` line outcomes and stamps the count via `withExpectedWorkOrderCount(int)`. Stamped only when `anyAccepted`. `FulfilmentSagaData.allWorkOrdersComplete()` branches: when `expectedWorkOrderCount` is set, gate is `completed.size() >= expectedCount`; otherwise legacy logic.

`expectedWorkOrderCount` is set monotonically at request-receipt time. After that, completion arrival before sibling creation can no longer trip the gate — count is the authoritative target.

## 2026-05-05 — Perpetual-inventory accounting

Closes the longest-deferred GL hole. Inventory + GRNI accounts (1200, 1300) were defined since phase 5b but unused; cost was being recognised as COGS the moment a supplier invoice was approved, regardless of whether goods had actually been sold.

New GL story:

| Event | Pre-perpetual | Perpetual (now) |
|---|---|---|
| Goods receipt | (nothing on GL) | Dr 1200 Inventory / Cr 1300 GRNI |
| Supplier invoice approval | Dr 5000 COGS / Cr 2100 AP | Dr 1300 GRNI / Cr 2100 AP |
| Supplier payment | Dr 2100 AP / Cr 1000 Bank | unchanged |
| Customer invoice creation | Dr 1100 AR / Cr 4000 Revenue | unchanged |
| Shipment | (nothing on GL) | Dr 5000 COGS / Cr 1200 Inventory |
| Customer payment | Dr 1000 Bank / Cr 1100 AR | unchanged |

`JournalEntryService.postGoodsReceived` and `postShipmentCost` added. `postSupplierInvoiceApproval` switched from Dr 5000 COGS to Dr 1300 GRNI. Finance's existing `GoodsReceivedHandler` calls `postGoodsReceived` in the same txn. New finance-side `ShipmentPostedCogsHandler` subscribes to `inventory.ShipmentPosted` (not sales' `SalesOrderShipped`, since the inventory event carries unit cost). Distinct consumer_name from `SalesOrderShippedHandler` so each fires independently.

What's still hardcoded (future-slice candidates):
- Single 5000 COGS account for every shipment regardless of product mix. Pairs with the deferred Shape A "JournalEntryService consults finance.product_valuation_class" follow-up.
- Single 1200 Inventory and 1300 GRNI per product. Real ERP would have valuation-class-driven inventory accounts.
- Inventory revaluation, average-cost rolling, FIFO/LIFO — out of scope.

## 2026-05-05 — Sub-assembly WIP balance (bump-only)

Closes part of the deferred limitation: *"sub-assembly children are filtered out — output never lands as standalone shippable stock"*. Sub-assembly outputs now bump a separate WIP balance instead of vanishing.

New table `inventory.wip_balance(wip_balance_id PK, warehouse_id, product_id, on_hand_quantity, average_cost, version, ...)` with `UNIQUE(warehouse_id, product_id)`. Mirrors `stock_balance` shape minus `reserved_quantity`.

Inventory's `WorkOrderManufacturingCompletedHandler` switches on `parentWorkOrderId`:
- Top-level WO (null) — bump FG `stock_balance.on_hand_quantity` (unchanged).
- Sub-assembly child (non-null) — bump `wip_balance.on_hand_quantity` for the child's `finishedProductId`.

**Consume path is parked**: when a parent WO completes, no event tells inventory WHICH sub-assemblies were consumed. Two future approaches: (1) new event `manufacturing.SubAssembliesConsumed` alongside the parent's `WorkOrderManufacturingCompleted`; (2) per-operation issue events. Until then `wip_balance.on_hand_quantity` grows monotonically — fine for the showcase.

## 2026-05-05 — Journal entry reversal

Closes the deferred phase 5b limitation: *"the `'reversed'` state exists but no service path reaches it yet"*.

`JournalEntry.reverseOf(original, reason, postingDate)` factory — produces a new posted entry whose lines are the original's with debit and credit amounts swapped per-line. New journal number prefixed `JE-REV-`. Linked back via `source_document_type='journal_reversal'` + `source_document_id = original.id()`. `JournalEntryService.reverseEntry(originalId, reason, postingDate)` orchestrates the same-txn reversal: load → enforce status `'posted'` → save reversal → `markReversed(originalId)` flips original `'posted'` → `'reversed'`. Both writes in one txn.

`JournalEntryRepository.markReversed(JournalEntryId)` runs the precise UPDATE the schema's `guard_journal_immutability` trigger permits.

`GET /api/journal-entries/{id}` and `POST /api/journal-entries/{id}/reverse`.

## 2026-05-05 — Multi-invoice payment allocation

Closes the deferred phase 5a/5b limitation: *"single-invoice allocation only"*.

Two new factory methods on `Payment`: `recordMultiSupplierPayment(...)` and `recordMultiCustomerPayment(...)`. Each builds one Payment with N PaymentAllocation rows and emits N events (one `SupplierPaymentMade` / `CustomerPaymentReceived` per allocation) so each P2P / O2C saga gets routed correctly. Validation: every allocation must reference an invoice in `approved`/`partially_paid` (supplier) or `posted`/`partially_paid` (customer); all invoices in a multi-allocation must share the same supplier/customer AND currency.

Single combined GL pair posted (Dr 2100 AP / Cr 1000 Bank for supplier) for the **total** amount — one physical movement of cash. Per-invoice GL detail lives in the AP/AR sub-ledger.

Two new REST endpoints: `POST /api/payments/multi`, `POST /api/payments/customer/multi`. Existing single-invoice endpoints preserved.

DB-level `maintain_allocation_totals` trigger handles per-allocation bookkeeping unchanged — multi-allocation was always supported at the schema level.

## 2026-05-05 — Make-to-order saga: sharper "did this receipt cover the shortage?" trigger

Closes the follow-up: *"compute 'quantity now available vs. saga's outstanding shortage' and only un-park when sufficient"*.

`RawMaterialsReservedHandler` stamps a `shortageByProductId` map onto `saga.data` whenever it transitions to `raw_material_shortage`. `GoodsReceivedHandler` no longer un-parks unconditionally. New `decideUnpark(saga, receivedByProduct)` helper:
- Reads the stash, decrements per-product remaining shortage (capped at 0). Drops entries that hit 0.
- Returns `UNPARK` if every entry driven to 0; `NARROW` if some remain (saga.data rewritten); `NONE` defensive.
- Backward-compat: sagas without a stash (legacy) get unconditional `UNPARK` (matches old behaviour).

Before this slice every goods receipt against a shortage product un-parked the saga to retry reservation, even when receipt was clearly insufficient — partial receipts now narrow the recorded shortage instead.

## 2026-05-05 — `invoice_paid` saga state CHECK gap

Caught by code audit (verifying `user-stories.md` against implementation): `CustomerPaymentReceivedHandler` transitions partial-settlement sagas to `invoice_paid`, but that state was missing from the v3 baseline CHECK on `sales.sales_order_fulfilment_saga.saga_state`. Logged in `bugs-caught-by-tests.md`. Fixed by Liquibase changeset `2026-05-05-extend-fulfilment-saga-states.sql` (drops + re-adds the CHECK with `invoice_paid` included) + v3.sql patch.

The tier 2 unit tests for the handler all mock the saga repository, so `sagas.save(saga)` never reached the DB and the CHECK never ran. A real partial customer payment would have failed at INSERT/UPDATE.

## 2026-05-05 — web-ui-bff gateway module

New `web-ui-bff/` module on port 8080, ~600 lines of Java total. Generic JDK 21 `HttpClient` reverse proxy with a path-prefix routing table; aggregated saga read API + single SSE stream merging across the three saga services. Vite dev proxy collapsed from ~15 entries to one (`/api → 8080`). SPA's `SagaConsole` switched from three concurrent EventSources to one aggregated stream; rows split by `sagaType` into the three columns. Per-service `/api/sagas/*/stream` endpoints retained on the saga services for direct debugging but aren't proxied. The SPA's fetcher contract didn't change; only the proxy endpoint moved. Reactor tests unchanged at 308 / 308.

What stayed browser-side:
- Scenario orchestration (`useScenarioRunner`) — moving server-side adds no demo value today, deferred until a multi-watcher use case surfaces.
- Phase 1 stub event drawer — still synthetic; real `/events` aggregator (BFF subscribes to outbox tables / Kafka topics and re-emits) is in `dev-todo.md`.

## 2026-05-05 — Web UI — six-phase rollout

See `web-ui/README.md` for the per-phase detail. Summary:

- **Phase 1 (read-only shell)** — Vite + React + TS + Tailwind v4 layout: top bar (logo + persona switcher + Scenarios + Events toggle), persona-grouped sidebar, Dashboard reading reporting's `/api/financial-dashboard`, stub event drawer. 252 KB JS / 14 KB CSS gzip.
- **Phase 2 (read-only depth)** — seven persona pages: Products, Stock items, Sales orders + 360, Purchase orders + tracking, Production board, ATP, Material shortages. Reporting gained three list endpoints (`GET /api/sales-orders`, `/api/purchase-orders`, `/api/work-orders`); product-service + inventory-service gained `findAll()` + list endpoints.
- **Phase 3 (Saga Console)** — three-column live view at `/saga-console`. Each row shows forward-path stages as inline dots with the current dot pulsing; rows flash on `version` change. Backed by per-service `SagaApiController` (list + SSE) on sales / manufacturing / purchasing. Vite proxies routed each.
- **Phase 4 (write paths)** — place sales order, goods receipt, shipment, supplier invoice, payments (AP/AR tabs), complete operation modal (production board), product edit modal. Fixed several drifted curl bodies (`customerCode` not `customerId`, `paymentMethod: bank_transfer` not `EFT`, etc.).
- **Phase 5 (scenario runner)** — three baked scenarios (3.1 / 5.2 / 7.1) in the top-bar Scenarios dropdown. State machine in `useScenarioRunner` interleaves auto API actions with `wait-for-saga-state` polls (2 s, 60–90 s timeouts). Pauses for human steps until the user clicks **Run step**. Stepper UI with status icons, captured-context disclosure, pause/skip/abort.
- **Phase 6 (BFF)** — see preceding entry.

Tests added during the rollout (tier 1 + tier 2 of the test rollout): 308 backend unit tests, plus `ReorderPolicyChangedSeamIT` Testcontainers IT. SPA typecheck + production build clean throughout.
