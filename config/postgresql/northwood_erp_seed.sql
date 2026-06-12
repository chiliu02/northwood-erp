-- northwood_erp_seed.sql
-- Northwood Furniture Co. Mini ERP — demo seed data
--
-- This file is the data-side companion to config/postgresql/northwood_erp.sql. The schema
-- file is the canonical baseline (DDL, roles, grants, partitions); this file
-- is the showcase fixture set (products, BOMs, customers, suppliers, chart of
-- accounts, etc.). They were one file until 2026-05-20; splitting them lets a
-- developer choose between "from scratch" (schema only, populate at runtime
-- via events) and "ready to demo" (schema + seed) without editing either file.
--
-- Two ways to run it:
--
--   (a) Standalone psql, after the schema is loaded:
--         psql -U postgres -d northwood_erp -f northwood_erp_seed.sql
--
--   (b) Docker (`/docker-entrypoint-initdb.d/` on first boot): mount this file
--       alongside the schema file via the docker-compose.seed.yml override.
--       From the repo root:
--         docker compose down -v
--         docker compose -f docker-compose.yml -f docker-compose.seed.yml up -d
--       Without the override, `docker compose up -d` boots with schema only.
--
-- Idempotency: every INSERT here uses `ON CONFLICT … DO NOTHING` so the file
-- is safe to re-run against an already-seeded database. Re-seeding never
-- updates existing rows — for that, use the runtime event flows.
--
-- ============================================================================
-- DESIGN NOTES  (split-readiness, mirrored from schema file)
-- ----------------------------------------------------------------------------
--
--   * Organised as one self-contained section per service, mirroring the
--     section layout in northwood_erp.sql. Each section is wrapped in
--     BEGIN/COMMIT and contains only its service's INSERT statements.
--     Lifting any one section out into its own services/<name>/seed.sql
--     produces a working seed file for that service against its own database.
--
--   * Cross-context fixture data (the wooden table BOM that joins product,
--     inventory, and manufacturing) uses well-known constant UUIDs declared
--     once in the fixture UUID registry below. Each service's seed references the constants directly
--     — no joins across schemas. This is the load-bearing design decision
--     that keeps the seed file lift-and-shift portable for the eventual
--     database-per-service split.
--
--   * INSERTs are schema-qualified (`INSERT INTO product.product …`), so no
--     `SET search_path` is needed. After the database-per-service split,
--     each service's lifted seed file will see only its own database and
--     can either keep the qualifier or drop it.
--
-- What this file does NOT do (intentionally):
--
--   * Replace the cross-context seed with event replay. The hardcoded UUIDs
--     are a pragmatic shortcut for a showcase; a more authentic dev loop
--     would have product master emit `ProductCreated` events that downstream
--     services consume to populate their projections.
-- ============================================================================

-- ============================================================================
-- WELL-KNOWN FIXTURE UUIDs
-- ----------------------------------------------------------------------------
-- These constants let each service's seed reference cross-context entities
-- (product, BOMs, warehouse, customer, supplier) by literal UUID without
-- joining across schemas. They are NOT used by application code and are NOT
-- checked into a registry; they exist purely so the showcase has consistent
-- demo data after the database-per-service split.
--
-- Format: 00000000-0000-7000-8nnn-nnnnnnnnnnnn  (valid UUIDv7 layout)
--
--   Products (finished goods):
--     FG-TABLE-001    00000000-0000-7000-8000-000000000001
--     FG-CABINET-001  00000000-0000-7000-8000-000000000200
--     FG-CHEST-001    00000000-0000-7000-8000-000000000300
--     FG-CHAIR-001    00000000-0000-7000-8000-000000000400
--     FG-RUG-001      00000000-0000-7000-8000-000000000500  (purchased; buy-to-stock)
--     FG-CARPET-001   00000000-0000-7000-8000-000000000501  (purchased; buy-to-order, to_order)
--
--   Products (raw materials):
--     RM-BOARD-001         00000000-0000-7000-8000-000000000002
--     RM-LEG-001           00000000-0000-7000-8000-000000000003
--     RM-SCREW-001         00000000-0000-7000-8000-000000000004
--     RM-VARNISH-001       00000000-0000-7000-8000-000000000005
--     RM-DRAWER-FRONT-001  00000000-0000-7000-8000-000000000202
--     RM-DRAWER-RUNNER-001 00000000-0000-7000-8000-000000000203
--
--   Products (semi-finished sub-assemblies):
--     SA-DRAWER-001   00000000-0000-7000-8000-000000000201
--     SA-FRAME-001    00000000-0000-7000-8000-000000000301
--     SA-PANEL-001    00000000-0000-7000-8000-000000000302
--
--   Units of measure:
--     EA              00000000-0000-7000-8000-000000000010
--     L               00000000-0000-7000-8000-000000000011
--     KG              00000000-0000-7000-8000-000000000012
--
--   Warehouses:
--     MAIN            00000000-0000-7000-8000-000000000020
--     MELB            00000000-0000-7000-8000-000000000021
--
--   Customers (5; region + status diversity):
--     CUST-001 Sydney         00000000-0000-7000-8000-000000000030  (active)
--     CUST-002 Melbourne      00000000-0000-7000-8000-000000000031  (active)
--     CUST-003 Brisbane       00000000-0000-7000-8000-000000000032  (active)
--     CUST-004 Perth hotel    00000000-0000-7000-8000-000000000033  (active)
--     CUST-005 Adelaide       00000000-0000-7000-8000-000000000034  (blocked — corner case)
--
--   Suppliers (5; category + status diversity):
--     SUP-001 timber          00000000-0000-7000-8000-000000000040  (active)
--     SUP-002 hardware        00000000-0000-7000-8000-000000000041  (active; has volume tier)
--     SUP-003 finishing       00000000-0000-7000-8000-000000000042  (active)
--     SUP-004 alt-timber      00000000-0000-7000-8000-000000000043  (active; multi-source vs SUP-001)
--     SUP-005 discontinued    00000000-0000-7000-8000-000000000044  (blocked — corner case)
--     SUP-006 floor-coverings 00000000-0000-7000-8000-000000000045  (active; sources FG-RUG / FG-CARPET)
--
--   BOMs (manufacturing.bom_header):
--     Wooden Table    00000000-0000-7000-8000-000000000100
--     Cabinet         00000000-0000-7000-8000-000000000210
--     Cabinet drawer  00000000-0000-7000-8000-000000000211
--     Chest           00000000-0000-7000-8000-000000000310
--     Chest frame     00000000-0000-7000-8000-000000000311
--     Chest panel     00000000-0000-7000-8000-000000000312
--     Chair           00000000-0000-7000-8000-000000000410
--
--   Routings (manufacturing.routing_header):
--     Wooden Table    00000000-0000-7000-8000-000000000050
--     Cabinet         00000000-0000-7000-8000-000000000060
--     Cabinet drawer  00000000-0000-7000-8000-000000000070
--     Chair           00000000-0000-7000-8000-000000000080
--
--   Work centers (manufacturing.work_center):
--     WC-ASSEMBLY     00000000-0000-7000-8000-000000000500
-- ============================================================================


-- ============================================================================
-- SEED: PRODUCT
-- ============================================================================

BEGIN;

INSERT INTO product.unit_of_measure (uom_id, code, name) VALUES
    ('00000000-0000-7000-8000-000000000010', 'EA', 'Each'),
    ('00000000-0000-7000-8000-000000000011', 'L',  'Litre'),
    ('00000000-0000-7000-8000-000000000012', 'KG', 'Kilogram')
ON CONFLICT (code) DO NOTHING;

INSERT INTO product.product (
    product_id, sku, name, description, product_type, base_uom_id,
    is_stocked, is_purchased, is_manufactured, is_sellable,
    sales_price, standard_cost,
    -- Reorder policy and valuation class live here on the product master (the
    -- source of truth); the per-service product_card projections are fed from
    -- product events (ReorderPolicyChanged -> inventory, ValuationClassChanged
    -- -> finance). Product.register defaults both (reorder 0/0, valuation_class
    -- NULL) and a steward sets them post-create -- but the seed represents the
    -- configured day-1 state, so we set them here and they must match the
    -- inventory + finance card seeds below or the master and projections
    -- disagree. valuation_class follows the product_type default for every
    -- seeded SKU (finished_good -> finished_goods, raw_material -> raw_materials,
    -- semi_finished_good -> semi_finished_goods).
    reorder_point, reorder_quantity, valuation_class
) VALUES
    ('00000000-0000-7000-8000-000000000001', 'FG-TABLE-001',   'Wooden Dining Table',
     'Finished wooden dining table',  'finished_good', '00000000-0000-7000-8000-000000000010',
     true, false, true,  true,  650.00, 332.00,  2,  5, 'finished_goods'),
    ('00000000-0000-7000-8000-000000000002', 'RM-BOARD-001',   'Wooden Board',
     'Timber board for table top',    'raw_material',  '00000000-0000-7000-8000-000000000010',
     true, true,  false, false,   0.00,  80.00, 10, 20, 'raw_materials'),
    ('00000000-0000-7000-8000-000000000003', 'RM-LEG-001',     'Table Leg',
     'Timber table leg',              'raw_material',  '00000000-0000-7000-8000-000000000010',
     true, true,  false, false,   0.00,  25.00, 20, 40, 'raw_materials'),
    ('00000000-0000-7000-8000-000000000004', 'RM-SCREW-001',   'Screw Pack',
     'Screw pack for one table',      'raw_material',  '00000000-0000-7000-8000-000000000010',
     true, true,  false, false,   0.00,   5.00, 10, 30, 'raw_materials'),
    ('00000000-0000-7000-8000-000000000005', 'RM-VARNISH-001', 'Varnish Pack',
     'Varnish portion for one table', 'raw_material',  '00000000-0000-7000-8000-000000000010',
     true, true,  false, false,   0.00,  12.00, 10, 30, 'raw_materials'),
    -- Sub-assembly demo set: a cabinet whose BOM has a drawer sub-assembly
    -- plus extra raw materials. Drives the sub-assembly recursion path in
    -- WorkOrderReleaseService and the BOM cycle detector's "this would close
    -- a loop" rejection path.
    ('00000000-0000-7000-8000-000000000200', 'FG-CABINET-001', 'Storage Cabinet',
     'Wooden storage cabinet with drawer', 'finished_good', '00000000-0000-7000-8000-000000000010',
     true, false, true,  true,  890.00, 343.00,  1,  3, 'finished_goods'),
    ('00000000-0000-7000-8000-000000000201', 'SA-DRAWER-001', 'Cabinet Drawer Sub-assembly',
     'Drawer pre-built for cabinet',       'semi_finished_good', '00000000-0000-7000-8000-000000000010',
     true, false, true,  false, 0.00,    84.75,  0,  0, 'semi_finished_goods'),
    ('00000000-0000-7000-8000-000000000202', 'RM-DRAWER-FRONT-001', 'Drawer Front Panel',
     'Pre-cut front panel for drawer',     'raw_material',       '00000000-0000-7000-8000-000000000010',
     true, true,  false, false, 0.00,    18.00,  5, 10, 'raw_materials'),
    ('00000000-0000-7000-8000-000000000203', 'RM-DRAWER-RUNNER-001', 'Drawer Runner',
     'Slide runner pair for drawer',       'raw_material',       '00000000-0000-7000-8000-000000000010',
     true, true,  false, false, 0.00,    14.00,  5, 10, 'raw_materials'),
    -- Multi-level BOM demo set: chest of drawers, with a frame sub-assembly
    -- that itself contains a panel sub-assembly. Exercises the recursive-CTE
    -- walk through 3 levels, and demonstrates the "same component used at
    -- multiple depths" case — RM-SCREW-001 appears at depth 1 (chest), depth 2
    -- (frame + drawer), and depth 3 (panel).
    ('00000000-0000-7000-8000-000000000300', 'FG-CHEST-001', 'Chest of Drawers',
     'Wooden chest of drawers with two drawers and a panelled frame', 'finished_good', '00000000-0000-7000-8000-000000000010',
     true, false, true,  true,  1490.00, 512.50,  1,  3, 'finished_goods'),
    ('00000000-0000-7000-8000-000000000301', 'SA-FRAME-001', 'Chest Frame Sub-assembly',
     'Panelled frame that holds the chest drawers',                   'semi_finished_good', '00000000-0000-7000-8000-000000000010',
     true, false, true,  false, 0.00,     304.00,  0,  0, 'semi_finished_goods'),
    ('00000000-0000-7000-8000-000000000302', 'SA-PANEL-001', 'Side Panel Sub-assembly',
     'Side panel built from board + varnish + screws',                'semi_finished_good', '00000000-0000-7000-8000-000000000010',
     true, false, true,  false, 0.00,     102.00,  0,  0, 'semi_finished_goods'),
    -- Simple FG demo set: a chair. Re-uses RM-LEG/BOARD/SCREW/VARNISH, so adds
    -- no new raws. The chair BOM (in SEED: MANUFACTURING) is the first one in
    -- the seed that carries non-zero scrap_factor_percent values — exercises
    -- the requirement = qty_per_unit × (1 + scrap/100) planning path with
    -- realistic numbers rather than the zero-scrap default.
    ('00000000-0000-7000-8000-000000000400', 'FG-CHAIR-001', 'Wooden Dining Chair',
     'Companion chair for FG-TABLE-001',                              'finished_good',      '00000000-0000-7000-8000-000000000010',
     true, false, true,  true,  220.00,   207.65,  5, 10, 'finished_goods')
ON CONFLICT (sku) DO NOTHING;

COMMIT;


-- ============================================================================
-- SEED: SALES
-- ============================================================================

BEGIN;

-- Five demo customers spanning regions + statuses. CUST-005 sits in 'blocked'
-- so the credit-hold filter on order-placement has a real row to suppress;
-- if a future slice adds an 'inactive' path, add a sixth row rather than
-- mutating CUST-005 — the showcase wants both states visible.
INSERT INTO sales.customer (
    customer_id, customer_code, name, email, phone, billing_address, shipping_address, status
) VALUES
    ('00000000-0000-7000-8000-000000000030',
     'CUST-001', 'Sydney Home Living',
     'orders@sydneyhomeliving.example',    '+61 2 9000 0001',
     'Sydney NSW',    'Sydney NSW',    'active'),
    ('00000000-0000-7000-8000-000000000031',
     'CUST-002', 'Melbourne Modern Furnishings',
     'buyers@melbmodern.example',          '+61 3 9000 0002',
     'Melbourne VIC', 'Melbourne VIC', 'active'),
    ('00000000-0000-7000-8000-000000000032',
     'CUST-003', 'Brisbane Boutique Living',
     'sales@brisboutique.example',         '+61 7 9000 0003',
     'Brisbane QLD',  'Brisbane QLD',  'active'),
    ('00000000-0000-7000-8000-000000000033',
     'CUST-004', 'Perth Premier Hotel Group',
     'procurement@perthpremier.example',   '+61 8 9000 0004',
     'Perth WA',      'Perth WA',      'active'),
    ('00000000-0000-7000-8000-000000000034',
     'CUST-005', 'Adelaide Outlet Co.',
     'accounts@adelaideoutlet.example',    '+61 8 9000 0005',
     'Adelaide SA',   'Adelaide SA',   'blocked')
ON CONFLICT (customer_code) DO NOTHING;

-- Backfill sales.product_card from product.product so the projection has
-- one row per existing Product at boot — same shape the runtime
-- ProductCreatedHandler produces on product.ProductCreated. Raw materials and
-- semi-finished goods are unsellable from the sales perspective, so their
-- price + currency are NULL (lifecycle: created → discontinued, never priced).
-- The two finished goods carry their real prices so the demo scenarios that
-- place orders don't need a separate SalesPriceChanged seed to be sellable.
INSERT INTO sales.product_card (product_id, sales_price, currency_code)
VALUES
    ('00000000-0000-7000-8000-000000000001', 650.00, 'AUD'),  -- FG-TABLE-001
    ('00000000-0000-7000-8000-000000000002', NULL,   NULL),   -- RM-BOARD-001
    ('00000000-0000-7000-8000-000000000003', NULL,   NULL),   -- RM-LEG-001
    ('00000000-0000-7000-8000-000000000004', NULL,   NULL),   -- RM-SCREW-001
    ('00000000-0000-7000-8000-000000000005', NULL,   NULL),   -- RM-VARNISH-001
    ('00000000-0000-7000-8000-000000000200', 890.00, 'AUD'),  -- FG-CABINET-001
    ('00000000-0000-7000-8000-000000000201', NULL,   NULL),   -- SA-DRAWER-001
    ('00000000-0000-7000-8000-000000000202', NULL,   NULL),   -- RM-DRAWER-FRONT-001
    ('00000000-0000-7000-8000-000000000203', NULL,   NULL),   -- RM-DRAWER-RUNNER-001
    ('00000000-0000-7000-8000-000000000300', 1490.00,'AUD'),  -- FG-CHEST-001
    ('00000000-0000-7000-8000-000000000301', NULL,   NULL),   -- SA-FRAME-001
    ('00000000-0000-7000-8000-000000000302', NULL,   NULL),   -- SA-PANEL-001
    ('00000000-0000-7000-8000-000000000400', 220.00, 'AUD')   -- FG-CHAIR-001
ON CONFLICT (product_id) DO NOTHING;

COMMIT;


-- ============================================================================
-- SEED: INVENTORY
-- Uses fixture UUIDs from the registry above; no cross-schema joins.
-- The product_card table is inventory's local projection of product master
-- (consolidates the former stock_item + product_card); in production it would
-- be populated by consuming ProductCreated / MakeVsBuyChanged events.
-- is_purchased/is_manufactured are seeded here so the reorder-point detection
-- service can route make-vs-buy for SQL-seeded demo SKUs (which never emit a
-- runtime MakeVsBuyChanged).
-- ============================================================================

BEGIN;

-- Two-warehouse setup so demos exercise multi-location ATP + stock-transfer
-- corner cases. MAIN carries the full catalogue; MELB only mirrors what a
-- regional dispatch hub realistically pre-stocks (finished tables + the
-- raws used to assemble chairs locally) and runs empty on the cabinet /
-- chest families.
INSERT INTO inventory.warehouse (warehouse_id, warehouse_code, name, address)
VALUES
    ('00000000-0000-7000-8000-000000000020', 'MAIN', 'Main Warehouse',     'Sydney NSW'),
    ('00000000-0000-7000-8000-000000000021', 'MELB', 'Melbourne Dispatch', 'Melbourne VIC')
ON CONFLICT (warehouse_code) DO NOTHING;

INSERT INTO inventory.product_card (
    product_id, product_sku, product_name, product_type, base_uom_code, stock_tracking_mode,
    is_purchased, is_manufactured, reorder_point, reorder_quantity
) VALUES
    ('00000000-0000-7000-8000-000000000001', 'FG-TABLE-001',   'Wooden Dining Table', 'finished_good', 'EA', 'tracked', false, true,   2,  5),
    ('00000000-0000-7000-8000-000000000002', 'RM-BOARD-001',   'Wooden Board',        'raw_material',  'EA', 'tracked', true,  false, 10, 20),
    ('00000000-0000-7000-8000-000000000003', 'RM-LEG-001',     'Table Leg',           'raw_material',  'EA', 'tracked', true,  false, 20, 40),
    ('00000000-0000-7000-8000-000000000004', 'RM-SCREW-001',   'Screw Pack',          'raw_material',  'EA', 'tracked', true,  false, 10, 30),
    ('00000000-0000-7000-8000-000000000005', 'RM-VARNISH-001', 'Varnish Pack',        'raw_material',  'EA', 'tracked', true,  false, 10, 30),
    -- Sub-assembly demo set:
    ('00000000-0000-7000-8000-000000000200', 'FG-CABINET-001',       'Storage Cabinet',             'finished_good',      'EA', 'tracked', false, true,  1,  3),
    ('00000000-0000-7000-8000-000000000201', 'SA-DRAWER-001',        'Cabinet Drawer Sub-assembly', 'semi_finished_good', 'EA', 'tracked', false, true,  0,  0),
    ('00000000-0000-7000-8000-000000000202', 'RM-DRAWER-FRONT-001',  'Drawer Front Panel',          'raw_material',       'EA', 'tracked', true,  false, 5, 10),
    ('00000000-0000-7000-8000-000000000203', 'RM-DRAWER-RUNNER-001', 'Drawer Runner',               'raw_material',       'EA', 'tracked', true,  false, 5, 10),
    -- Multi-level BOM demo set: chest + frame/panel sub-assemblies. Made on
    -- demand (no stock_balance rows), but the card still exists — at runtime
    -- product.ProductCreated projects a card for every product, so the seed
    -- must too, or the stock-items page is short three rows vs product.Products.
    ('00000000-0000-7000-8000-000000000300', 'FG-CHEST-001',         'Chest of Drawers',            'finished_good',      'EA', 'tracked', false, true,  1,  3),
    ('00000000-0000-7000-8000-000000000301', 'SA-FRAME-001',         'Chest Frame Sub-assembly',    'semi_finished_good', 'EA', 'tracked', false, true,  0,  0),
    ('00000000-0000-7000-8000-000000000302', 'SA-PANEL-001',         'Side Panel Sub-assembly',     'semi_finished_good', 'EA', 'tracked', false, true,  0,  0),
    ('00000000-0000-7000-8000-000000000400', 'FG-CHAIR-001',         'Wooden Dining Chair',         'finished_good',      'EA', 'tracked', false, true,  5, 10)
ON CONFLICT (product_id) DO NOTHING;

INSERT INTO inventory.stock_balance (
    warehouse_id, product_id, on_hand_quantity, reserved_quantity, average_cost
) VALUES
    ('00000000-0000-7000-8000-000000000020', '00000000-0000-7000-8000-000000000001',  2, 0, 320.00),
    ('00000000-0000-7000-8000-000000000020', '00000000-0000-7000-8000-000000000002',  5, 0,  80.00),
    ('00000000-0000-7000-8000-000000000020', '00000000-0000-7000-8000-000000000003', 20, 0,  25.00),
    ('00000000-0000-7000-8000-000000000020', '00000000-0000-7000-8000-000000000004', 20, 0,   5.00),
    ('00000000-0000-7000-8000-000000000020', '00000000-0000-7000-8000-000000000005', 20, 0,  12.00),
    -- Sub-assembly demo set: cabinet/drawer parts. The finished cabinet carries
    -- a small MAIN baseline (3); the drawer sub-assembly is not pre-stocked (its
    -- child WO produces it on demand); raws are.
    ('00000000-0000-7000-8000-000000000020', '00000000-0000-7000-8000-000000000200',  3, 0, 420.00),
    ('00000000-0000-7000-8000-000000000020', '00000000-0000-7000-8000-000000000201',  0, 0,  65.00),
    ('00000000-0000-7000-8000-000000000020', '00000000-0000-7000-8000-000000000202', 10, 0,  18.00),
    ('00000000-0000-7000-8000-000000000020', '00000000-0000-7000-8000-000000000203', 10, 0,  14.00),
    -- Multi-level BOM demo set: chest + frame/panel sub-assemblies. Like the
    -- cabinet set, the assembled items are made on demand (not pre-stocked);
    -- the 0-qty rows give the ATP-across-locations view explicit cells rather
    -- than gaps. Chest raws are the shared RM-* already stocked above.
    ('00000000-0000-7000-8000-000000000020', '00000000-0000-7000-8000-000000000300',  0, 0, 720.00),
    ('00000000-0000-7000-8000-000000000020', '00000000-0000-7000-8000-000000000301',  0, 0, 380.00),
    ('00000000-0000-7000-8000-000000000020', '00000000-0000-7000-8000-000000000302',  0, 0, 105.00),
    -- Chair in MAIN: 10 pre-built; assembled locally from existing raws.
    ('00000000-0000-7000-8000-000000000020', '00000000-0000-7000-8000-000000000400', 10, 0, 120.00),
    -- MELB stocks: finished table + the raws needed to assemble chairs locally.
    -- The cabinet and chest families carry 0-qty rows here (assembled on demand,
    -- not pre-stocked at a regional hub) so the ATP-across-locations view shows
    -- explicit 0-on-hand cells rather than gaps. Costs match MAIN since
    -- average_cost is per (warehouse, product) but seeded identically at day-1.
    ('00000000-0000-7000-8000-000000000021', '00000000-0000-7000-8000-000000000001',  1, 0, 320.00),
    ('00000000-0000-7000-8000-000000000021', '00000000-0000-7000-8000-000000000002',  3, 0,  80.00),
    ('00000000-0000-7000-8000-000000000021', '00000000-0000-7000-8000-000000000003',  8, 0,  25.00),
    ('00000000-0000-7000-8000-000000000021', '00000000-0000-7000-8000-000000000004',  0, 0,   5.00),
    ('00000000-0000-7000-8000-000000000021', '00000000-0000-7000-8000-000000000005',  0, 0,  12.00),
    ('00000000-0000-7000-8000-000000000021', '00000000-0000-7000-8000-000000000200',  0, 0, 420.00),
    ('00000000-0000-7000-8000-000000000021', '00000000-0000-7000-8000-000000000201',  0, 0,  65.00),
    ('00000000-0000-7000-8000-000000000021', '00000000-0000-7000-8000-000000000202',  0, 0,  18.00),
    ('00000000-0000-7000-8000-000000000021', '00000000-0000-7000-8000-000000000203',  0, 0,  14.00),
    ('00000000-0000-7000-8000-000000000021', '00000000-0000-7000-8000-000000000300',  0, 0, 720.00),
    ('00000000-0000-7000-8000-000000000021', '00000000-0000-7000-8000-000000000301',  0, 0, 380.00),
    ('00000000-0000-7000-8000-000000000021', '00000000-0000-7000-8000-000000000302',  0, 0, 105.00),
    ('00000000-0000-7000-8000-000000000021', '00000000-0000-7000-8000-000000000400',  0, 0, 120.00)
ON CONFLICT (warehouse_id, product_id) DO NOTHING;

COMMIT;


-- ============================================================================
-- SEED: MANUFACTURING
-- Wooden Table BOM. Fixture UUIDs from the registry above; no cross-schema joins.
-- ============================================================================

BEGIN;

-- Manufacturing card: mirror product.product is_purchased / is_manufactured
-- so manufacturing has a row per existing SKU at boot. Runtime keeps it in
-- step via the inbox handlers on product.MakeVsBuyChanged + ActiveBomChanged
-- + ProductDiscontinued + the local materials-cost rollup engine.
INSERT INTO manufacturing.product_card (product_id, is_purchased, is_manufactured)
VALUES
    ('00000000-0000-7000-8000-000000000001', false, true),
    ('00000000-0000-7000-8000-000000000002', true,  false),
    ('00000000-0000-7000-8000-000000000003', true,  false),
    ('00000000-0000-7000-8000-000000000004', true,  false),
    ('00000000-0000-7000-8000-000000000005', true,  false),
    ('00000000-0000-7000-8000-000000000200', false, true),
    ('00000000-0000-7000-8000-000000000201', false, true),
    ('00000000-0000-7000-8000-000000000202', true,  false),
    ('00000000-0000-7000-8000-000000000203', true,  false),
    ('00000000-0000-7000-8000-000000000300', false, true),
    ('00000000-0000-7000-8000-000000000301', false, true),
    ('00000000-0000-7000-8000-000000000302', false, true),
    ('00000000-0000-7000-8000-000000000400', false, true)
ON CONFLICT (product_id) DO NOTHING;

INSERT INTO manufacturing.bom_header (
    bom_header_id, finished_product_id, finished_product_sku, finished_product_name, version, status
) VALUES (
    '00000000-0000-7000-8000-000000000100',
    '00000000-0000-7000-8000-000000000001',
    'FG-TABLE-001', 'Wooden Dining Table', '1', 'active'
)
ON CONFLICT (finished_product_id, version) DO NOTHING;

INSERT INTO manufacturing.bom_line (
    bom_header_id, line_number, component_product_id, component_sku, component_name,
    component_kind, quantity_per_finished_unit, scrap_factor_percent
) VALUES
    ('00000000-0000-7000-8000-000000000100', 1, '00000000-0000-7000-8000-000000000002', 'RM-BOARD-001',   'Wooden Board', 'raw', 1.000000, 0),
    ('00000000-0000-7000-8000-000000000100', 2, '00000000-0000-7000-8000-000000000003', 'RM-LEG-001',     'Table Leg',    'raw', 4.000000, 0),
    ('00000000-0000-7000-8000-000000000100', 3, '00000000-0000-7000-8000-000000000004', 'RM-SCREW-001',   'Screw Pack',   'raw', 1.000000, 0),
    ('00000000-0000-7000-8000-000000000100', 4, '00000000-0000-7000-8000-000000000005', 'RM-VARNISH-001', 'Varnish Pack', 'raw', 1.000000, 0)
ON CONFLICT (bom_header_id, line_number) DO NOTHING;

-- Sub-assembly demo: cabinet's BOM has the drawer as a sub_assembly plus raws;
-- the drawer's BOM has only raws. Together these exercise the recursion in
-- WorkOrderReleaseService and provide a non-trivial graph for cycle detection.
INSERT INTO manufacturing.bom_header (
    bom_header_id, finished_product_id, finished_product_sku, finished_product_name, version, status
) VALUES
    ('00000000-0000-7000-8000-000000000210',
     '00000000-0000-7000-8000-000000000200',
     'FG-CABINET-001', 'Storage Cabinet', '1', 'active'),
    ('00000000-0000-7000-8000-000000000211',
     '00000000-0000-7000-8000-000000000201',
     'SA-DRAWER-001',  'Cabinet Drawer Sub-assembly', '1', 'active')
ON CONFLICT (finished_product_id, version) DO NOTHING;

INSERT INTO manufacturing.bom_line (
    bom_header_id, line_number, component_product_id, component_sku, component_name,
    component_kind, quantity_per_finished_unit, scrap_factor_percent
) VALUES
    -- Cabinet → drawer (sub-assembly) + raw materials
    ('00000000-0000-7000-8000-000000000210', 1, '00000000-0000-7000-8000-000000000201', 'SA-DRAWER-001', 'Cabinet Drawer Sub-assembly', 'sub_assembly', 1.000000, 0),
    ('00000000-0000-7000-8000-000000000210', 2, '00000000-0000-7000-8000-000000000002', 'RM-BOARD-001',   'Wooden Board', 'raw', 2.000000, 0),
    ('00000000-0000-7000-8000-000000000210', 3, '00000000-0000-7000-8000-000000000005', 'RM-VARNISH-001', 'Varnish Pack', 'raw', 1.000000, 0),
    -- Drawer → raws only
    ('00000000-0000-7000-8000-000000000211', 1, '00000000-0000-7000-8000-000000000202', 'RM-DRAWER-FRONT-001',  'Drawer Front Panel', 'raw', 1.000000, 0),
    ('00000000-0000-7000-8000-000000000211', 2, '00000000-0000-7000-8000-000000000203', 'RM-DRAWER-RUNNER-001', 'Drawer Runner',      'raw', 2.000000, 0),
    ('00000000-0000-7000-8000-000000000211', 3, '00000000-0000-7000-8000-000000000004', 'RM-SCREW-001',         'Screw Pack',         'raw', 1.000000, 0)
ON CONFLICT (bom_header_id, line_number) DO NOTHING;

-- Multi-level demo: a chest of drawers whose BOM nests two sub-assemblies
-- deep (chest → frame → panel) and uses RM-SCREW-001 at four positions
-- across three depths. Exercises the recursive-CTE walk in
-- BomLookup.findActiveBomTreeRows and the flat-view's "same component
-- at multiple paths" case in the SPAs.
INSERT INTO manufacturing.bom_header (
    bom_header_id, finished_product_id, finished_product_sku, finished_product_name, version, status
) VALUES
    ('00000000-0000-7000-8000-000000000310',
     '00000000-0000-7000-8000-000000000300',
     'FG-CHEST-001', 'Chest of Drawers', '1', 'active'),
    ('00000000-0000-7000-8000-000000000311',
     '00000000-0000-7000-8000-000000000301',
     'SA-FRAME-001', 'Chest Frame Sub-assembly', '1', 'active'),
    ('00000000-0000-7000-8000-000000000312',
     '00000000-0000-7000-8000-000000000302',
     'SA-PANEL-001', 'Side Panel Sub-assembly', '1', 'active')
ON CONFLICT (finished_product_id, version) DO NOTHING;

INSERT INTO manufacturing.bom_line (
    bom_header_id, line_number, component_product_id, component_sku, component_name,
    component_kind, quantity_per_finished_unit, scrap_factor_percent
) VALUES
    -- Chest → 2× drawer (sub-assembly), 1× frame (sub-assembly), 3× screw, 2× varnish
    ('00000000-0000-7000-8000-000000000310', 1, '00000000-0000-7000-8000-000000000201', 'SA-DRAWER-001',  'Cabinet Drawer Sub-assembly', 'sub_assembly', 2.000000, 0),
    ('00000000-0000-7000-8000-000000000310', 2, '00000000-0000-7000-8000-000000000301', 'SA-FRAME-001',   'Chest Frame Sub-assembly',    'sub_assembly', 1.000000, 0),
    ('00000000-0000-7000-8000-000000000310', 3, '00000000-0000-7000-8000-000000000004', 'RM-SCREW-001',   'Screw Pack',                  'raw',          3.000000, 0),
    ('00000000-0000-7000-8000-000000000310', 4, '00000000-0000-7000-8000-000000000005', 'RM-VARNISH-001', 'Varnish Pack',                'raw',          2.000000, 0),
    -- Frame → 2× panel (sub-sub-assembly), 1× board (top), 4× screw
    ('00000000-0000-7000-8000-000000000311', 1, '00000000-0000-7000-8000-000000000302', 'SA-PANEL-001', 'Side Panel Sub-assembly', 'sub_assembly', 2.000000, 0),
    ('00000000-0000-7000-8000-000000000311', 2, '00000000-0000-7000-8000-000000000002', 'RM-BOARD-001', 'Wooden Board',            'raw',          1.000000, 0),
    ('00000000-0000-7000-8000-000000000311', 3, '00000000-0000-7000-8000-000000000004', 'RM-SCREW-001', 'Screw Pack',              'raw',          4.000000, 0),
    -- Panel → 1× board, 1× varnish, 2× screw
    ('00000000-0000-7000-8000-000000000312', 1, '00000000-0000-7000-8000-000000000002', 'RM-BOARD-001',   'Wooden Board', 'raw', 1.000000, 0),
    ('00000000-0000-7000-8000-000000000312', 2, '00000000-0000-7000-8000-000000000005', 'RM-VARNISH-001', 'Varnish Pack', 'raw', 1.000000, 0),
    ('00000000-0000-7000-8000-000000000312', 3, '00000000-0000-7000-8000-000000000004', 'RM-SCREW-001',   'Screw Pack',   'raw', 2.000000, 0)
ON CONFLICT (bom_header_id, line_number) DO NOTHING;

-- Conversion-cost rates: $30/hr labour (0.50/min) + overhead
-- absorbed at 50% of labour (0.25/min). FG-TABLE-001's routing is 180 min/unit
-- (35+25+70+50), so conversion = 180 × 0.75 = 135/unit; with the 197 material
-- rollup the full standard cost rolls up to 332 (the cost-rollup slice drives
-- product.standard_cost from this — until then the seeded standard_cost still
-- reads its hand-set value).
INSERT INTO manufacturing.work_center (work_center_id, work_center_code, name, labour_rate_per_minute, overhead_rate_per_minute)
VALUES (
    '00000000-0000-7000-8000-000000000500',
    'WC-ASSEMBLY', 'Assembly Bay', 0.500000, 0.250000
) ON CONFLICT (work_center_code) DO NOTHING;

INSERT INTO manufacturing.routing_header (
    routing_header_id, finished_product_id, finished_product_sku, finished_product_name, version, status
) VALUES (
    '00000000-0000-7000-8000-000000000050',
    '00000000-0000-7000-8000-000000000001',
    'FG-TABLE-001', 'Wooden Dining Table', '1', 'active'
) ON CONFLICT (finished_product_id, version) DO NOTHING;

INSERT INTO manufacturing.routing_operation (
    routing_operation_id,
    routing_header_id, operation_sequence, operation_code, description,
    work_center_id, planned_setup_minutes, planned_run_minutes
) VALUES
    ('00000000-0000-7000-8000-000000000051', '00000000-0000-7000-8000-000000000050', 10, 'CUT',      'Cut to size',     '00000000-0000-7000-8000-000000000500',  5, 30),
    ('00000000-0000-7000-8000-000000000052', '00000000-0000-7000-8000-000000000050', 20, 'DRILL',    'Drill leg holes', '00000000-0000-7000-8000-000000000500',  5, 20),
    ('00000000-0000-7000-8000-000000000053', '00000000-0000-7000-8000-000000000050', 30, 'ASSEMBLE', 'Assemble',        '00000000-0000-7000-8000-000000000500', 10, 60),
    ('00000000-0000-7000-8000-000000000054', '00000000-0000-7000-8000-000000000050', 40, 'FINISH',   'Varnish + sand',  '00000000-0000-7000-8000-000000000500',  5, 45)
ON CONFLICT (routing_header_id, operation_sequence) DO NOTHING;

-- Sub-assembly demo: routings for the cabinet and the drawer. Both run on
-- WC-ASSEMBLY for now; introducing a second work centre is a separate slice.
INSERT INTO manufacturing.routing_header (
    routing_header_id, finished_product_id, finished_product_sku, finished_product_name, version, status
) VALUES
    ('00000000-0000-7000-8000-000000000060',
     '00000000-0000-7000-8000-000000000200',
     'FG-CABINET-001', 'Storage Cabinet', '1', 'active'),
    ('00000000-0000-7000-8000-000000000070',
     '00000000-0000-7000-8000-000000000201',
     'SA-DRAWER-001',  'Cabinet Drawer Sub-assembly', '1', 'active')
ON CONFLICT (finished_product_id, version) DO NOTHING;

INSERT INTO manufacturing.routing_operation (
    routing_operation_id,
    routing_header_id, operation_sequence, operation_code, description,
    work_center_id, planned_setup_minutes, planned_run_minutes
) VALUES
    -- Cabinet routing
    ('00000000-0000-7000-8000-000000000061', '00000000-0000-7000-8000-000000000060', 10, 'CUT',      'Cut to size',     '00000000-0000-7000-8000-000000000500',  5, 20),
    ('00000000-0000-7000-8000-000000000062', '00000000-0000-7000-8000-000000000060', 20, 'ASSEMBLE', 'Assemble cabinet','00000000-0000-7000-8000-000000000500', 10, 45),
    ('00000000-0000-7000-8000-000000000063', '00000000-0000-7000-8000-000000000060', 30, 'FINISH',   'Varnish',         '00000000-0000-7000-8000-000000000500',  5, 30),
    -- Drawer routing
    ('00000000-0000-7000-8000-000000000071', '00000000-0000-7000-8000-000000000070', 10, 'CUT',      'Cut drawer parts','00000000-0000-7000-8000-000000000500',  5, 15),
    ('00000000-0000-7000-8000-000000000072', '00000000-0000-7000-8000-000000000070', 20, 'ASSEMBLE', 'Assemble drawer', '00000000-0000-7000-8000-000000000500',  5, 20)
ON CONFLICT (routing_header_id, operation_sequence) DO NOTHING;

-- Chair demo: simple FG that re-uses existing raws and is the first BOM in
-- the seed with non-zero scrap_factor_percent. Screws lose ~2% to misfeeds
-- on assembly; varnish loses ~5% to brush retention and overspray. The
-- planner consumes (qty_per_unit × (1 + scrap/100)) — exercises both the
-- "raw material with scrap" path and the integer-vs-decimal handling in
-- the rollup since RM-BOARD/VARNISH are fractional (0.500000) per chair.
INSERT INTO manufacturing.bom_header (
    bom_header_id, finished_product_id, finished_product_sku, finished_product_name, version, status
) VALUES (
    '00000000-0000-7000-8000-000000000410',
    '00000000-0000-7000-8000-000000000400',
    'FG-CHAIR-001', 'Wooden Dining Chair', '1', 'active'
)
ON CONFLICT (finished_product_id, version) DO NOTHING;

INSERT INTO manufacturing.bom_line (
    bom_header_id, line_number, component_product_id, component_sku, component_name,
    component_kind, quantity_per_finished_unit, scrap_factor_percent
) VALUES
    ('00000000-0000-7000-8000-000000000410', 1, '00000000-0000-7000-8000-000000000003', 'RM-LEG-001',     'Table Leg',    'raw', 4.000000, 0),
    ('00000000-0000-7000-8000-000000000410', 2, '00000000-0000-7000-8000-000000000002', 'RM-BOARD-001',   'Wooden Board', 'raw', 0.500000, 0),
    ('00000000-0000-7000-8000-000000000410', 3, '00000000-0000-7000-8000-000000000004', 'RM-SCREW-001',   'Screw Pack',   'raw', 1.000000, 2),
    ('00000000-0000-7000-8000-000000000410', 4, '00000000-0000-7000-8000-000000000005', 'RM-VARNISH-001', 'Varnish Pack', 'raw', 0.500000, 5)
ON CONFLICT (bom_header_id, line_number) DO NOTHING;

INSERT INTO manufacturing.routing_header (
    routing_header_id, finished_product_id, finished_product_sku, finished_product_name, version, status
) VALUES (
    '00000000-0000-7000-8000-000000000080',
    '00000000-0000-7000-8000-000000000400',
    'FG-CHAIR-001', 'Wooden Dining Chair', '1', 'active'
) ON CONFLICT (finished_product_id, version) DO NOTHING;

INSERT INTO manufacturing.routing_operation (
    routing_operation_id,
    routing_header_id, operation_sequence, operation_code, description,
    work_center_id, planned_setup_minutes, planned_run_minutes
) VALUES
    ('00000000-0000-7000-8000-000000000081', '00000000-0000-7000-8000-000000000080', 10, 'CUT',      'Cut chair parts',  '00000000-0000-7000-8000-000000000500', 5, 15),
    ('00000000-0000-7000-8000-000000000082', '00000000-0000-7000-8000-000000000080', 20, 'ASSEMBLE', 'Assemble chair',   '00000000-0000-7000-8000-000000000500', 5, 25),
    ('00000000-0000-7000-8000-000000000083', '00000000-0000-7000-8000-000000000080', 30, 'FINISH',   'Varnish + polish', '00000000-0000-7000-8000-000000000500', 5, 20)
ON CONFLICT (routing_header_id, operation_sequence) DO NOTHING;

COMMIT;


-- ============================================================================
-- SEED: PURCHASING
-- ============================================================================

BEGIN;

-- Five demo suppliers spanning categories and statuses:
--   SUP-001  timber       (existing — feeds RM-BOARD/LEG)
--   SUP-002  hardware     (RM-SCREW + RM-DRAWER-RUNNER, has volume tier)
--   SUP-003  finishing    (RM-VARNISH, undercuts SUP-001's price)
--   SUP-004  alt-timber   (RM-BOARD/LEG, dual-source scenario vs SUP-001)
--   SUP-005  blocked      (corner case: 'blocked' supplier; carries no prices)
-- Multi-source coverage (SUP-001 vs SUP-004 on board+leg; SUP-001 vs SUP-002
-- on screw; SUP-001 vs SUP-003 on varnish) exercises supplier-selection paths
-- that single-source seed couldn't.
INSERT INTO purchasing.supplier (
    supplier_id, supplier_code, name, email, phone, address, status
) VALUES
    ('00000000-0000-7000-8000-000000000040',
     'SUP-001', 'Australian Timber Supplies',
     'sales@austimber.example',          '+61 2 9000 1001',
     'Newcastle NSW', 'active'),
    ('00000000-0000-7000-8000-000000000041',
     'SUP-002', 'Sydney Hardware Wholesale',
     'orders@sydneyhardware.example',    '+61 2 9000 1002',
     'Sydney NSW',    'active'),
    ('00000000-0000-7000-8000-000000000042',
     'SUP-003', 'Coastal Coatings & Finishes',
     'sales@coastalcoatings.example',    '+61 2 9000 1003',
     'Wollongong NSW','active'),
    ('00000000-0000-7000-8000-000000000043',
     'SUP-004', 'Highland Timber NSW',
     'sales@highlandtimber.example',     '+61 2 9000 1004',
     'Bathurst NSW',  'active'),
    ('00000000-0000-7000-8000-000000000044',
     'SUP-005', 'Discontinued Outback Co.',
     'closed@outback-discontinued.example', '+61 8 9000 1005',
     'Alice Springs NT', 'blocked')
ON CONFLICT (supplier_code) DO NOTHING;

-- Supplier price list — six purchased products at standard cost.
INSERT INTO purchasing.supplier_product_price
    (supplier_product_price_id, supplier_id, product_id, currency_code, unit_price)
VALUES
    ('00000000-0000-7000-8000-000000000601',
     '00000000-0000-7000-8000-000000000040',
     '00000000-0000-7000-8000-000000000002', 'AUD', 80.000000),
    ('00000000-0000-7000-8000-000000000602',
     '00000000-0000-7000-8000-000000000040',
     '00000000-0000-7000-8000-000000000003', 'AUD', 25.000000),
    ('00000000-0000-7000-8000-000000000603',
     '00000000-0000-7000-8000-000000000040',
     '00000000-0000-7000-8000-000000000004', 'AUD', 5.000000),
    ('00000000-0000-7000-8000-000000000604',
     '00000000-0000-7000-8000-000000000040',
     '00000000-0000-7000-8000-000000000005', 'AUD', 12.000000),
    ('00000000-0000-7000-8000-000000000605',
     '00000000-0000-7000-8000-000000000040',
     '00000000-0000-7000-8000-000000000202', 'AUD', 18.000000),
    ('00000000-0000-7000-8000-000000000606',
     '00000000-0000-7000-8000-000000000040',
     '00000000-0000-7000-8000-000000000203', 'AUD', 14.000000),
-- Alternative-source base-tier prices for the new suppliers (min_quantity
-- defaults to 0). SUP-002 undercuts SUP-001 on screws; SUP-003 undercuts
-- on varnish; SUP-004 sits slightly above on board/leg as a backup source.
    ('00000000-0000-7000-8000-000000000607',
     '00000000-0000-7000-8000-000000000041',
     '00000000-0000-7000-8000-000000000004', 'AUD', 4.500000),
    ('00000000-0000-7000-8000-000000000609',
     '00000000-0000-7000-8000-000000000041',
     '00000000-0000-7000-8000-000000000203', 'AUD', 13.500000),
    ('00000000-0000-7000-8000-000000000610',
     '00000000-0000-7000-8000-000000000042',
     '00000000-0000-7000-8000-000000000005', 'AUD', 11.000000),
    ('00000000-0000-7000-8000-000000000611',
     '00000000-0000-7000-8000-000000000043',
     '00000000-0000-7000-8000-000000000002', 'AUD', 82.000000),
    ('00000000-0000-7000-8000-000000000612',
     '00000000-0000-7000-8000-000000000043',
     '00000000-0000-7000-8000-000000000003', 'AUD', 26.000000)
-- ON CONFLICT must match the table's unique constraint exactly
-- (supplier_product_price_unique_tier on all five columns); the defaulted
-- effective_from = '1970-01-01' and min_quantity = 0 are part of that key.
ON CONFLICT (supplier_id, product_id, currency_code, effective_from, min_quantity)
    DO NOTHING;

-- Volume-discount tier example: SUP-002 charges 3.80 for screws once the
-- order reaches 100 packs (vs 4.50 at the base tier). Lookup picks the row
-- with the highest min_quantity ≤ ordered quantity, so PO for 50 → 4.50,
-- PO for 200 → 3.80. Sits in its own INSERT because it carries an explicit
-- min_quantity column the base rows don't.
INSERT INTO purchasing.supplier_product_price
    (supplier_product_price_id, supplier_id, product_id, currency_code, unit_price, min_quantity)
VALUES
    ('00000000-0000-7000-8000-000000000608',
     '00000000-0000-7000-8000-000000000041',
     '00000000-0000-7000-8000-000000000004', 'AUD', 3.800000, 100)
ON CONFLICT (supplier_id, product_id, currency_code, effective_from, min_quantity)
    DO NOTHING;

-- Backfill purchasing.product_card name snapshot (product_sku/product_name) so
-- the supplier-price list view shows readable columns at boot — the same shape
-- the runtime ProductCreatedHandler produces on product.ProductCreated.
-- discontinued_at stays NULL (none retired at day-1). One row per product
-- (one-card-per-product), not just the priced ones, mirroring the other
-- services' card backfills. FG-RUG/CARPET (the floor-coverings pair) are
-- included here too even though their prices seed in a later block.
-- is_purchased mirrors product.product.is_purchased (the master): the six raw
-- materials plus the two floor-covering FGs (RUG/CARPET) are bought; the
-- made-in-house FGs and sub-assemblies are make-only. PurchasableProductLookup
-- reads this so a manual requisition can't line up a make-only SKU.
INSERT INTO purchasing.product_card (product_id, product_sku, product_name, is_purchased) VALUES
    ('00000000-0000-7000-8000-000000000001', 'FG-TABLE-001',         'Wooden Dining Table',         false),
    ('00000000-0000-7000-8000-000000000002', 'RM-BOARD-001',         'Wooden Board',                true),
    ('00000000-0000-7000-8000-000000000003', 'RM-LEG-001',           'Table Leg',                   true),
    ('00000000-0000-7000-8000-000000000004', 'RM-SCREW-001',         'Screw Pack',                  true),
    ('00000000-0000-7000-8000-000000000005', 'RM-VARNISH-001',       'Varnish Pack',                true),
    ('00000000-0000-7000-8000-000000000200', 'FG-CABINET-001',       'Storage Cabinet',             false),
    ('00000000-0000-7000-8000-000000000201', 'SA-DRAWER-001',        'Cabinet Drawer Sub-assembly', false),
    ('00000000-0000-7000-8000-000000000202', 'RM-DRAWER-FRONT-001',  'Drawer Front Panel',          true),
    ('00000000-0000-7000-8000-000000000203', 'RM-DRAWER-RUNNER-001', 'Drawer Runner',               true),
    ('00000000-0000-7000-8000-000000000300', 'FG-CHEST-001',         'Chest of Drawers',            false),
    ('00000000-0000-7000-8000-000000000301', 'SA-FRAME-001',         'Chest Frame Sub-assembly',    false),
    ('00000000-0000-7000-8000-000000000302', 'SA-PANEL-001',         'Side Panel Sub-assembly',     false),
    ('00000000-0000-7000-8000-000000000400', 'FG-CHAIR-001',         'Wooden Dining Chair',         false),
    ('00000000-0000-7000-8000-000000000500', 'FG-RUG-001',           'Woven Floor Rug',             true),
    ('00000000-0000-7000-8000-000000000501', 'FG-CARPET-001',        'Custom-design Carpet',        true)
ON CONFLICT (product_id) DO NOTHING;

-- Mirror replenishment_strategy for the two to_order products onto the purchasing
-- card (the bulk insert above lets the column default to 'to_stock'): FG-CHEST-001
-- (make-to-order) and FG-CARPET-001 (buy-to-order). ToOrderProductLookup reads this
-- so a manual requisition for a to-order product is rejected — its received stock
-- could never be reserved by a sales order (every to-order line raises its own
-- dedicated, order-pegged supply). Idempotent (constant target).
UPDATE purchasing.product_card SET replenishment_strategy = 'to_order'
 WHERE product_id IN (
     '00000000-0000-7000-8000-000000000300',
     '00000000-0000-7000-8000-000000000501'
 );

COMMIT;


-- ============================================================================
-- SEED: FINANCE
-- ============================================================================

BEGIN;

-- Chart of accounts. Adds the equity side (3xxx) so the trial-balance view
-- has a non-empty equity column from day-1, plus two operational accounts
-- (sales discounts as a revenue contra, freight expense) that future demo
-- scenarios can post against without needing a fresh CoA addition.
INSERT INTO finance.gl_account (account_code, account_name, account_type) VALUES
    ('1000', 'Bank',                          'asset'),
    ('1100', 'Accounts Receivable',           'asset'),
    ('1200', 'Inventory',                     'asset'),
    ('1210', 'Raw Materials Inventory',       'asset'),
    ('1220', 'Finished Goods Inventory',      'asset'),
    -- Perpetual WIP. Raw materials Dr here when issued to a work order (Cr 1210);
    -- the finished good Dr's 1220 / Cr's 1230 at completion. Nets to zero per WO
    -- at standard cost (no variance accounts in the material-only cut).
    ('1230', 'Work In Progress',              'asset'),
    -- 1300 is the GRNI clearing account that finance posts against (see
    -- FinanceAccountCodes.GRNI = "1300"): Cr at goods receipt, Dr at invoice
    -- approval. Its label was corrected from the misleading "Work In Progress"
    -- (that name now belongs to 1230) and the unused 2200 duplicate was dropped.
    ('1300', 'Goods Received Not Invoiced',   'asset'),
    ('2100', 'Accounts Payable',              'liability'),
    -- Liability for cash received on prepayment orders before goods are
    -- delivered. Credited at payment receipt for prepayment invoices; debited
    -- at shipment to reclassify the deposit against Sales Revenue once the
    -- goods-delivered performance obligation is met.
    ('2110', 'Customer Deposits',             'liability'),
    ('3000', 'Owner''s Equity',               'equity'),
    ('3100', 'Retained Earnings',             'equity'),
    ('4000', 'Sales Revenue',                 'revenue'),
    ('4100', 'Sales Discounts',               'revenue'),
    ('5000', 'Cost of Goods Sold',            'expense'),
    ('5100', 'Production Variance',           'expense'),
    ('5200', 'Materials Cost',                'expense'),
    -- Conversion Cost Applied: credited when a work order's standard conversion
    -- cost (labour + overhead) is absorbed into WIP at completion (Dr 1230), so
    -- WIP nets to zero against the full standard cost out.
    ('5250', 'Conversion Cost Applied',       'expense'),
    ('5300', 'Freight Expense',               'expense'),
    ('5400', 'Inventory Adjustment',          'expense'),
    ('5500', 'Promotions & Samples Expense',  'expense')
ON CONFLICT (account_code) DO NOTHING;

-- Tax codes. GST_NZ_15 is schema-prep — no Java code path produces or
-- consumes it today (multi-currency GL consolidation is deprioritised).
-- Carrying the row lets a future NZ-customer scenario post against it
-- without needing a CoA addition first.
INSERT INTO finance.tax_code (tax_code, description, rate) VALUES
    ('GST_AU_10', 'Australian GST 10%',       0.10),
    ('GST_NZ_15', 'New Zealand GST 15%',      0.15),
    ('GST_FREE',  'GST-free supply',          0.00),
    ('NO_TAX',    'No tax (export, etc)',     0.00)
ON CONFLICT (tax_code) DO NOTHING;

-- Backfill finance.product_card from product.product so the projection
-- has one row per existing Product at boot — same shape the runtime
-- ProductCreatedHandler produces on product.ProductCreated. Standard cost +
-- currency seeded from product.standard_cost so day-1 COGS posting works
-- ahead of the first StandardCostChanged event. Valuation class follows the
-- product_type analogue: raw → 'raw_materials', finished → 'finished_goods',
-- semi-finished → 'semi_finished_goods' (which JournalEntryService routes to
-- the FG inventory + COGS accounts, same as finished goods). Wire-format
-- values mirror product.domain.ValuationClass.code(). Subsequent events
-- update individual columns.
INSERT INTO finance.product_card (
    product_id, standard_cost, currency_code, valuation_class
) VALUES
    ('00000000-0000-7000-8000-000000000001', 332.00, 'AUD', 'finished_goods'),      -- FG-TABLE-001 (197 material + 135 conversion)
    ('00000000-0000-7000-8000-000000000002',  80.00, 'AUD', 'raw_materials'),       -- RM-BOARD-001
    ('00000000-0000-7000-8000-000000000003',  25.00, 'AUD', 'raw_materials'),       -- RM-LEG-001
    ('00000000-0000-7000-8000-000000000004',   5.00, 'AUD', 'raw_materials'),       -- RM-SCREW-001
    ('00000000-0000-7000-8000-000000000005',  12.00, 'AUD', 'raw_materials'),       -- RM-VARNISH-001
    ('00000000-0000-7000-8000-000000000200', 343.00, 'AUD', 'finished_goods'),      -- FG-CABINET-001 (material + conversion)
    ('00000000-0000-7000-8000-000000000201',  84.75, 'AUD', 'semi_finished_goods'), -- SA-DRAWER-001    ('00000000-0000-7000-8000-000000000202',  18.00, 'AUD', 'raw_materials'),       -- RM-DRAWER-FRONT-001
    ('00000000-0000-7000-8000-000000000203',  14.00, 'AUD', 'raw_materials'),       -- RM-DRAWER-RUNNER-001
    ('00000000-0000-7000-8000-000000000300', 512.50, 'AUD', 'finished_goods'),      -- FG-CHEST-001    ('00000000-0000-7000-8000-000000000301', 304.00, 'AUD', 'semi_finished_goods'), -- SA-FRAME-001    ('00000000-0000-7000-8000-000000000302', 102.00, 'AUD', 'semi_finished_goods'), -- SA-PANEL-001    ('00000000-0000-7000-8000-000000000400', 207.65, 'AUD', 'finished_goods')       -- FG-CHAIR-001ON CONFLICT (product_id) DO NOTHING;

COMMIT;


-- ============================================================================
-- SEED: REPORTING
-- Read models are otherwise populated by projection consumers draining
-- events from the bus into reporting.inbox_message and applying them to
-- the read tables. The product_card cache is seeded here so that
-- day-1 inventory_value computation works ahead of the first
-- StandardCostChanged event; subsequent events update individual rows.
-- ============================================================================

BEGIN;

INSERT INTO reporting.product_card (product_id, standard_cost, currency_code)
VALUES
    ('00000000-0000-7000-8000-000000000001', 332.00, 'AUD'),  -- FG-TABLE-001 (197 material + 135 conversion)
    ('00000000-0000-7000-8000-000000000002',  80.00, 'AUD'),
    ('00000000-0000-7000-8000-000000000003',  25.00, 'AUD'),
    ('00000000-0000-7000-8000-000000000004',   5.00, 'AUD'),
    ('00000000-0000-7000-8000-000000000005',  12.00, 'AUD'),
    ('00000000-0000-7000-8000-000000000200', 343.00, 'AUD'),  -- FG-CABINET-001    ('00000000-0000-7000-8000-000000000201',  84.75, 'AUD'),  -- SA-DRAWER-001    ('00000000-0000-7000-8000-000000000202',  18.00, 'AUD'),
    ('00000000-0000-7000-8000-000000000203',  14.00, 'AUD'),
    ('00000000-0000-7000-8000-000000000300', 512.50, 'AUD'),  -- FG-CHEST-001    ('00000000-0000-7000-8000-000000000301', 304.00, 'AUD'),  -- SA-FRAME-001    ('00000000-0000-7000-8000-000000000302', 102.00, 'AUD'),  -- SA-PANEL-001    ('00000000-0000-7000-8000-000000000400', 207.65, 'AUD')   -- FG-CHAIR-001ON CONFLICT (product_id) DO NOTHING;

COMMIT;


-- ============================================================================
-- SEED: FLOOR COVERINGS — buy-side make/buy-to-order demo pair
-- Two purchased, sellable finished goods that contrast the two buy-side
-- replenishment modes within one product family:
--   FG-RUG-001    — buy-to-stock:    replenishment_strategy = to_stock, a normal
--                   reorder point/qty so it replenishes the shared pool (the
--                   off-the-shelf baseline / contrast partner).
--   FG-CARPET-001 — buy-to-order:    replenishment_strategy = to_order, reorder
--                   0/0 (per the to_order invariant) — each sales order raises a
--                   dedicated, order-pegged PO, reserved to the SO line on
--                   goods receipt (REQ-PROD-022 / REQ-INV-093).
-- Both purchased from a new floor-coverings supplier (SUP-006). The seed
-- hand-maintains every per-service *_card projection + stock_balance,
-- so all are written here too — skipping any repeats the chest-family gap.
-- ============================================================================

BEGIN;

-- Source of truth. Listing replenishment_strategy explicitly (the existing
-- product INSERT omits it and relies on the column DEFAULT 'to_stock'); CARPET
-- must be 'to_order'. The three to_order CHECK invariants hold: is_sellable=true,
-- reorder 0/0, and (non-service) strategy in (to_stock, to_order).
INSERT INTO product.product (
    product_id, sku, name, description, product_type, base_uom_id,
    is_stocked, is_purchased, is_manufactured, is_sellable,
    sales_price, standard_cost,
    reorder_point, reorder_quantity, valuation_class, replenishment_strategy
) VALUES
    ('00000000-0000-7000-8000-000000000500', 'FG-RUG-001',    'Woven Floor Rug',
     'Off-the-shelf woven floor rug',          'finished_good', '00000000-0000-7000-8000-000000000010',
     true, true, false, true,  180.00,  95.00,  4,  8, 'finished_goods', 'to_stock'),
    ('00000000-0000-7000-8000-000000000501', 'FG-CARPET-001', 'Custom-design Carpet',
     'Customised carpet — cut + finished per order', 'finished_good', '00000000-0000-7000-8000-000000000010',
     true, true, false, true, 1200.00, 700.00,  0,  0, 'finished_goods', 'to_order')
ON CONFLICT (sku) DO NOTHING;

-- Floor-coverings supplier + price list (mirrors the timber-supplier rows).
INSERT INTO purchasing.supplier (
    supplier_id, supplier_code, name, email, phone, address, status
) VALUES
    ('00000000-0000-7000-8000-000000000045',
     'SUP-006', 'Floor Coverings Direct',
     'sales@floorcoverings.example', '+61 2 9000 1006',
     'Sydney NSW', 'active')
ON CONFLICT (supplier_code) DO NOTHING;

INSERT INTO purchasing.supplier_product_price
    (supplier_product_price_id, supplier_id, product_id, currency_code, unit_price)
VALUES
    ('00000000-0000-7000-8000-000000000613',
     '00000000-0000-7000-8000-000000000045',
     '00000000-0000-7000-8000-000000000500', 'AUD',  95.000000),
    ('00000000-0000-7000-8000-000000000614',
     '00000000-0000-7000-8000-000000000045',
     '00000000-0000-7000-8000-000000000501', 'AUD', 700.000000)
ON CONFLICT (supplier_id, product_id, currency_code, effective_from, min_quantity)
    DO NOTHING;

-- sales.product_card — both sellable + priced. Listing replenishment_strategy
-- explicitly (the existing sales card INSERT relies on the DEFAULT 'to_stock');
-- CARPET must be 'to_order' so the fulfilment saga's reserve step pegs it.
INSERT INTO sales.product_card (product_id, sales_price, currency_code, replenishment_strategy)
VALUES
    ('00000000-0000-7000-8000-000000000500', 180.00,  'AUD', 'to_stock'),
    ('00000000-0000-7000-8000-000000000501', 1200.00, 'AUD', 'to_order')
ON CONFLICT (product_id) DO NOTHING;

-- inventory.product_card — purchased, not manufactured; RUG carries a reorder
-- policy (buy-to-stock), CARPET is 0/0 (buy-to-order, no independent loop).
INSERT INTO inventory.product_card (
    product_id, product_sku, product_name, product_type, base_uom_code, stock_tracking_mode,
    is_purchased, is_manufactured, reorder_point, reorder_quantity
) VALUES
    ('00000000-0000-7000-8000-000000000500', 'FG-RUG-001',    'Woven Floor Rug',     'finished_good', 'EA', 'tracked', true, false, 4, 8),
    ('00000000-0000-7000-8000-000000000501', 'FG-CARPET-001', 'Custom-design Carpet', 'finished_good', 'EA', 'tracked', true, false, 0, 0)
ON CONFLICT (product_id) DO NOTHING;

-- inventory.stock_balance — RUG carries an off-the-shelf MAIN baseline (sells
-- from the pool); CARPET is 0 everywhere (built per order, pegged on receipt).
INSERT INTO inventory.stock_balance (
    warehouse_id, product_id, on_hand_quantity, reserved_quantity, average_cost
) VALUES
    ('00000000-0000-7000-8000-000000000020', '00000000-0000-7000-8000-000000000500', 6, 0,  95.00),
    ('00000000-0000-7000-8000-000000000020', '00000000-0000-7000-8000-000000000501', 0, 0, 700.00),
    ('00000000-0000-7000-8000-000000000021', '00000000-0000-7000-8000-000000000500', 0, 0,  95.00),
    ('00000000-0000-7000-8000-000000000021', '00000000-0000-7000-8000-000000000501', 0, 0, 700.00)
ON CONFLICT (warehouse_id, product_id) DO NOTHING;

-- manufacturing.product_card — purchased (not made), but the card exists per the
-- one-card-per-product projection-completeness rule.
INSERT INTO manufacturing.product_card (product_id, is_purchased, is_manufactured)
VALUES
    ('00000000-0000-7000-8000-000000000500', true, false),
    ('00000000-0000-7000-8000-000000000501', true, false)
ON CONFLICT (product_id) DO NOTHING;

INSERT INTO finance.product_card (product_id, standard_cost, currency_code, valuation_class)
VALUES
    ('00000000-0000-7000-8000-000000000500',  95.00, 'AUD', 'finished_goods'),
    ('00000000-0000-7000-8000-000000000501', 700.00, 'AUD', 'finished_goods')
ON CONFLICT (product_id) DO NOTHING;

INSERT INTO reporting.product_card (product_id, standard_cost, currency_code)
VALUES
    ('00000000-0000-7000-8000-000000000500',  95.00, 'AUD'),
    ('00000000-0000-7000-8000-000000000501', 700.00, 'AUD')
ON CONFLICT (product_id) DO NOTHING;

COMMIT;


-- ============================================================================
-- SEED: FG-CHEST-001 reconfigured make-to-order
-- The chest was seeded above with the bulk default (to_stock, reorder 1/3).
-- Flip it to make-to-order so each sales order pegs a dedicated work order
-- rather than building to the shared pool — the manufactured counterpart to
-- FG-CARPET-001's buy-to-order. The three to_order invariants hold:
-- is_sellable=true (set above), reorder 0/0 (zeroed here), non-service. Mirror
-- the strategy onto sales.product_card (the fulfilment saga reads it to peg the
-- SO line) and zero inventory's reorder policy so the make-to-stock reorder
-- loop never fires for it. stock_balance is already 0 (made on demand).
-- Idempotent UPDATEs (constant target), like the version fixup below.
-- ============================================================================

BEGIN;

UPDATE product.product
   SET replenishment_strategy = 'to_order', reorder_point = 0, reorder_quantity = 0
 WHERE product_id = '00000000-0000-7000-8000-000000000300';

UPDATE sales.product_card
   SET replenishment_strategy = 'to_order'
 WHERE product_id = '00000000-0000-7000-8000-000000000300';

UPDATE inventory.product_card
   SET reorder_point = 0, reorder_quantity = 0
 WHERE product_id = '00000000-0000-7000-8000-000000000300';

COMMIT;


-- ============================================================================
-- SEED: APPROVED VENDORS
-- The Shape-A approved-vendor list per purchased product — the engineering
-- quality gate PurchaseOrderService.pickSupplier reads to choose a supplier
-- (preferred first, then cheapest eligible by price list). Without it every PR
-- falls through to the default supplier (SUP-001), which yields a 0.00 PO for a
-- product SUP-001 doesn't price (e.g. the floor coverings, sourced only by
-- SUP-006). Preferred = the cheapest supplier in the price list above, so the
-- materials-cost rollup and the auto-source pick agree.
--
-- Seeded on BOTH the product master (product.approved_vendor — source of truth,
-- read by the catalog UI) AND purchasing's projection
-- (purchasing.product_approved_vendor — read by supplier selection), since the
-- seed hand-maintains projections (no runtime ApprovedVendorListChanged at boot).
-- PK defaults via uuid_generate_v7(); ON CONFLICT keys on the (product, supplier)
-- unique constraint so re-running the seed is idempotent.
-- ============================================================================

BEGIN;

INSERT INTO product.approved_vendor (product_id, supplier_id, supplier_code, supplier_name, is_preferred) VALUES
    ('00000000-0000-7000-8000-000000000002', '00000000-0000-7000-8000-000000000040', 'SUP-001', 'Australian Timber Supplies', true),
    ('00000000-0000-7000-8000-000000000002', '00000000-0000-7000-8000-000000000043', 'SUP-004', 'Highland Timber NSW',        false),
    ('00000000-0000-7000-8000-000000000003', '00000000-0000-7000-8000-000000000040', 'SUP-001', 'Australian Timber Supplies', true),
    ('00000000-0000-7000-8000-000000000003', '00000000-0000-7000-8000-000000000043', 'SUP-004', 'Highland Timber NSW',        false),
    ('00000000-0000-7000-8000-000000000004', '00000000-0000-7000-8000-000000000041', 'SUP-002', 'Sydney Hardware Wholesale',  true),
    ('00000000-0000-7000-8000-000000000004', '00000000-0000-7000-8000-000000000040', 'SUP-001', 'Australian Timber Supplies', false),
    ('00000000-0000-7000-8000-000000000005', '00000000-0000-7000-8000-000000000042', 'SUP-003', 'Coastal Coatings & Finishes', true),
    ('00000000-0000-7000-8000-000000000005', '00000000-0000-7000-8000-000000000040', 'SUP-001', 'Australian Timber Supplies', false),
    ('00000000-0000-7000-8000-000000000202', '00000000-0000-7000-8000-000000000040', 'SUP-001', 'Australian Timber Supplies', true),
    ('00000000-0000-7000-8000-000000000203', '00000000-0000-7000-8000-000000000041', 'SUP-002', 'Sydney Hardware Wholesale',  true),
    ('00000000-0000-7000-8000-000000000203', '00000000-0000-7000-8000-000000000040', 'SUP-001', 'Australian Timber Supplies', false),
    ('00000000-0000-7000-8000-000000000500', '00000000-0000-7000-8000-000000000045', 'SUP-006', 'Floor Coverings Direct',     true),
    ('00000000-0000-7000-8000-000000000501', '00000000-0000-7000-8000-000000000045', 'SUP-006', 'Floor Coverings Direct',     true)
ON CONFLICT (product_id, supplier_id) DO NOTHING;

-- Purchasing's projection — same rows, the table supplier selection actually reads.
INSERT INTO purchasing.product_approved_vendor (product_id, supplier_id, supplier_code, supplier_name, is_preferred) VALUES
    ('00000000-0000-7000-8000-000000000002', '00000000-0000-7000-8000-000000000040', 'SUP-001', 'Australian Timber Supplies', true),
    ('00000000-0000-7000-8000-000000000002', '00000000-0000-7000-8000-000000000043', 'SUP-004', 'Highland Timber NSW',        false),
    ('00000000-0000-7000-8000-000000000003', '00000000-0000-7000-8000-000000000040', 'SUP-001', 'Australian Timber Supplies', true),
    ('00000000-0000-7000-8000-000000000003', '00000000-0000-7000-8000-000000000043', 'SUP-004', 'Highland Timber NSW',        false),
    ('00000000-0000-7000-8000-000000000004', '00000000-0000-7000-8000-000000000041', 'SUP-002', 'Sydney Hardware Wholesale',  true),
    ('00000000-0000-7000-8000-000000000004', '00000000-0000-7000-8000-000000000040', 'SUP-001', 'Australian Timber Supplies', false),
    ('00000000-0000-7000-8000-000000000005', '00000000-0000-7000-8000-000000000042', 'SUP-003', 'Coastal Coatings & Finishes', true),
    ('00000000-0000-7000-8000-000000000005', '00000000-0000-7000-8000-000000000040', 'SUP-001', 'Australian Timber Supplies', false),
    ('00000000-0000-7000-8000-000000000202', '00000000-0000-7000-8000-000000000040', 'SUP-001', 'Australian Timber Supplies', true),
    ('00000000-0000-7000-8000-000000000203', '00000000-0000-7000-8000-000000000041', 'SUP-002', 'Sydney Hardware Wholesale',  true),
    ('00000000-0000-7000-8000-000000000203', '00000000-0000-7000-8000-000000000040', 'SUP-001', 'Australian Timber Supplies', false),
    ('00000000-0000-7000-8000-000000000500', '00000000-0000-7000-8000-000000000045', 'SUP-006', 'Floor Coverings Direct',     true),
    ('00000000-0000-7000-8000-000000000501', '00000000-0000-7000-8000-000000000045', 'SUP-006', 'Floor Coverings Direct',     true)
ON CONFLICT (product_id, supplier_id) DO NOTHING;

COMMIT;


-- ============================================================================
-- SEED: AGGREGATE VERSION FIXUP
-- Seeded aggregate-root rows must land at version 1, not the table default 0.
-- Each Jdbc*Repository.save() uses version() == 0 as its "new, not yet
-- persisted -> INSERT" sentinel; a seeded row left at the default 0 makes the
-- FIRST app edit re-run the INSERT and hit a duplicate-PK error (e.g. editing
-- FG-CHAIR-001 pricing -> "duplicate key value violates product_pkey").
-- The INSERTs above can't carry version inline without listing it in every
-- column tuple, so we bump it once here, idempotently. Only the seeded tables
-- backed by a version-sentinel repository are affected:
-- product.product, sales.customer, purchasing.supplier (now an aggregate),
-- purchasing.supplier_product_price.
-- ============================================================================

BEGIN;

UPDATE product.product                   SET version = 1 WHERE version = 0;
UPDATE sales.customer                    SET version = 1 WHERE version = 0;
UPDATE purchasing.supplier               SET version = 1 WHERE version = 0;
UPDATE purchasing.supplier_product_price SET version = 1 WHERE version = 0;

COMMIT;

-- ============================================================================
-- Seed complete.
-- ============================================================================
