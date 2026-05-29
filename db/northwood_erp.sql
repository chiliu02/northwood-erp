-- northwood_erp.sql
-- Northwood Furniture Co. Mini ERP — schema baseline
-- PostgreSQL installation script
--
-- This file is the canonical schema baseline: extensions, schemas, roles,
-- grants, DDL, partitions, functions, and PL/pgSQL constraints. It contains
-- NO data — demo seed (products, BOMs, customers, suppliers, GL chart, etc.)
-- lives in the companion file db/northwood_erp_seed.sql. They were one file
-- until 2026-05-20; splitting them lets a developer choose between "from
-- scratch" (schema only, populate at runtime via events) and "ready to demo"
-- (schema + seed) without editing either file.
--
-- The script assumes the target database already exists and that you are
-- already connected to it. Two ways to run it:
--
--   (a) Standalone psql:
--         createdb -U postgres northwood_erp
--         psql -U postgres -d northwood_erp -f northwood_erp.sql
--         # optional: psql -U postgres -d northwood_erp -f northwood_erp_seed.sql
--
--   (b) Docker (`/docker-entrypoint-initdb.d/`): the postgres entrypoint
--       creates the database via POSTGRES_DB and runs every *.sql here while
--       connected to it. The default docker-compose.yml mounts this file
--       only; pass `-f docker-compose.yml -f docker-compose.seed.yml` on
--       `up` to also mount the seed file.
--
-- The DROP DATABASE / CREATE DATABASE / \connect block that earlier versions
-- of this script carried is gone — both invocation paths above already have
-- the database created and we're already inside it, so dropping the
-- currently-open database is rejected by Postgres.
--
-- ============================================================================
-- DESIGN NOTES  (split-readiness)
-- ----------------------------------------------------------------------------
-- Structured so that moving from "schema-per-service in one database" to
-- "database-per-service" is a configuration change, not a refactor:
--
--   * Organised as one self-contained section per service. Each section is
--     wrapped in BEGIN/COMMIT and contains: schema creation, idempotent role
--     creation, grants, and all DDL (including the service's own outbox/inbox).
--     Lifting any one section out into its own services/<name>/install.sql
--     produces a working installer for that service against its own database.
--     The matching seed section in db/northwood_erp_seed.sql §<same number>
--     ships the demo fixture rows for that service when wanted.
--
--   * The shared bootstrap (extensions, `shared` schema, `uuid_generate_v7()`,
--     `set_updated_at()`) lives in a single transaction at the top. In a
--     multi-database deployment, each per-service install file inlines this
--     same block — the IF NOT EXISTS / OR REPLACE guards make it idempotent
--     either way. Treat `shared` as a vendored library, not a runtime
--     dependency between services.
--
--   * Each service creates only its own role, idempotently, via a per-service
--     `DO` block. Reporting consumes events from the bus through its inbox;
--     no cross-schema SELECT grants exist.
--
--   * No physical cross-schema foreign keys. Cross-context identities
--     (supplier_id on finance.payment, product_id on every projection, etc.)
--     are plain UUIDs maintained via event projection — never enforced by FK.
--
--   * Each service's section ends with a final block of grants on tables and
--     sequences (`SELECT, INSERT, UPDATE` on tables, `USAGE, SELECT` on
--     sequences). These run after the DDL so newly-created objects are
--     covered without needing `ALTER DEFAULT PRIVILEGES`.
--
-- What this file does NOT do (intentionally; these belong outside SQL):
--
--   * Adopt a migration tool's full lifecycle. For ongoing schema work, drop
--     a Liquibase changeset under `<service>/src/main/resources/db/changelog/
--     changes/` and reference it from that service's master changelog; this
--     file stays the canonical baseline.
--   * Configure one DataSource per service. The application code opens a
--     separate connection pool per service, each authenticated with its own
--     `<service>_service` role. `search_path` is set per pool so the service
--     can only see its own schema and `shared`.
--   * Ship demo seed data. The companion file db/northwood_erp_seed.sql
--     carries the cross-context fixture rows (the wooden table BOM that
--     joins product, inventory, and manufacturing) keyed off well-known
--     constant UUIDs; see that file's §0 for the registry. The hardcoded
--     UUIDs are a pragmatic shortcut for a showcase; a more authentic dev
--     loop would have product master emit `ProductCreated` events that
--     downstream services consume to populate their projections.
--
-- Domain invariants enforced in this schema: balanced journals, posted-document
-- immutability, payment allocation totals never exceed payment amount, range-
-- partitioned movement/journal tables, sequence-numbered outbox cursors, OCC
-- versioning, saga leases. See each service's section for the per-aggregate
-- detail.
-- ============================================================================

-- ============================================================================
-- §1  SHARED BOOTSTRAP
-- ----------------------------------------------------------------------------
-- Inlined per service in the multi-database deployment. The CREATE SCHEMA IF
-- NOT EXISTS / CREATE OR REPLACE FUNCTION guards make this safe to re-run.
-- ============================================================================

BEGIN;

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE SCHEMA IF NOT EXISTS shared;

-- UUIDv7: time-ordered UUIDs preserve btree locality for high-write tables.
-- Pure-SQL implementation; on PG 18+ replace with built-in uuidv7().
CREATE OR REPLACE FUNCTION shared.uuid_generate_v7()
RETURNS UUID
LANGUAGE plpgsql
AS $$
DECLARE
    unix_ts_ms BIGINT;
    rand_bytes BYTEA;
    uuid_bytes BYTEA;
BEGIN
    unix_ts_ms := (EXTRACT(EPOCH FROM clock_timestamp()) * 1000)::BIGINT;
    rand_bytes := gen_random_bytes(10);

    -- 48-bit timestamp (big-endian) + 80 bits of randomness, with version 7
    -- and RFC 4122 variant bits set per the UUIDv7 spec.
    uuid_bytes :=
        set_byte(set_byte(set_byte(set_byte(set_byte(set_byte(rand_bytes, 0,
            (get_byte(rand_bytes, 0) & 15) | 112), -- version 7
            1, get_byte(rand_bytes, 1)),
            2, (get_byte(rand_bytes, 2) & 63) | 128), -- variant
            3, get_byte(rand_bytes, 3)),
            4, get_byte(rand_bytes, 4)),
            5, get_byte(rand_bytes, 5));

    RETURN encode(
        decode(lpad(to_hex(unix_ts_ms), 12, '0'), 'hex') || uuid_bytes,
        'hex'
    )::UUID;
END;
$$;

-- Generic updated_at maintenance trigger.
CREATE OR REPLACE FUNCTION shared.set_updated_at()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END;
$$;

COMMIT;


-- ============================================================================
-- §  SERVICE: PRODUCT
-- File equivalent in multi-DB: services/product/install.sql
-- ============================================================================

BEGIN;

CREATE SCHEMA IF NOT EXISTS product;

-- Idempotent role creation. Roles are cluster-wide on a single PostgreSQL
-- instance, so this guard makes the script safe to re-run after DROP DATABASE.
-- After the database-per-service split, each service's install runs against
-- its own cluster (or its own database within a shared cluster) and creates
-- only its own role.
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'product_service') THEN
        CREATE ROLE product_service NOLOGIN;
    END IF;
END $$;

GRANT USAGE ON SCHEMA product TO product_service;
GRANT USAGE ON SCHEMA shared TO product_service;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA shared TO product_service;


CREATE TABLE product.unit_of_measure (
    uom_id UUID PRIMARY KEY DEFAULT shared.uuid_generate_v7(),
    code VARCHAR(20) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE product.product (
    product_id UUID PRIMARY KEY DEFAULT shared.uuid_generate_v7(),
    sku VARCHAR(50) NOT NULL UNIQUE,
    name VARCHAR(200) NOT NULL,
    description TEXT,
    product_type VARCHAR(30) NOT NULL CHECK (
        product_type IN ('raw_material', 'finished_good', 'semi_finished_good', 'service')
    ),
    base_uom_id UUID NOT NULL REFERENCES product.unit_of_measure(uom_id),
    is_stocked BOOLEAN NOT NULL DEFAULT true,
    is_purchased BOOLEAN NOT NULL DEFAULT false,
    is_manufactured BOOLEAN NOT NULL DEFAULT false,
    is_sellable BOOLEAN NOT NULL DEFAULT false,
    sales_price NUMERIC(18, 6) NOT NULL DEFAULT 0,
    standard_cost NUMERIC(18, 6) NOT NULL DEFAULT 0,
    -- Reorder policy on the master record. Mirrored/owned-locally by
    -- inventory.stock_item per the projection design (inventory has its own
    -- authoritative copy for replenishment planning); product.product carries
    -- it too so the catalog API can return reorder hints without a cross-
    -- service call.
    reorder_point NUMERIC(18, 4) NOT NULL DEFAULT 0,
    reorder_quantity NUMERIC(18, 4) NOT NULL DEFAULT 0,
    -- Shape A facets: valuation class drives finance's GL account selection;
    -- active BOM is the authoritative pointer (manufacturing keeps a parallel
    -- bom_header.is_active during the migration period); approved vendors
    -- live in product.approved_vendor (separate, multi-valued). Wire-format
    -- values mirror product.domain.ValuationClass.dbValue() (product-events).
    valuation_class VARCHAR(50) CHECK (
        valuation_class IS NULL
        OR valuation_class IN ('raw_materials', 'finished_goods', 'semi_finished_goods')
    ),
    active_bom_id UUID,
    status VARCHAR(20) NOT NULL DEFAULT 'active' CHECK (
        status IN ('active', 'inactive', 'discontinued')
    ),
    -- Optimistic concurrency: updates use WHERE version = :expected.
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Actor audit (envelope-stamped on every mutation; nullable forever — seed
    -- rows + saga-driven mutations stay null on purpose).
    created_by VARCHAR(64),
    last_modified_by VARCHAR(64)
);

CREATE INDEX idx_product_product_type ON product.product(product_type);
CREATE INDEX idx_product_status ON product.product(status);

CREATE TRIGGER trg_product_updated_at
    BEFORE UPDATE ON product.product
    FOR EACH ROW EXECUTE FUNCTION shared.set_updated_at();

-- Shape A approved-vendor list — multi-valued facet of product master.
-- Drives purchasing's supplier selection at PR→PO conversion. The actual
-- price per supplier+product still lives in purchasing.supplier_product_price.
CREATE TABLE product.approved_vendor (
    approved_vendor_id UUID PRIMARY KEY DEFAULT shared.uuid_generate_v7(),
    product_id UUID NOT NULL REFERENCES product.product(product_id) ON DELETE CASCADE,
    supplier_id UUID NOT NULL,
    supplier_code VARCHAR(50),
    supplier_name VARCHAR(200),
    is_preferred BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (product_id, supplier_id)
);

CREATE INDEX idx_approved_vendor_product ON product.approved_vendor(product_id);

CREATE TRIGGER trg_approved_vendor_updated_at
    BEFORE UPDATE ON product.approved_vendor
    FOR EACH ROW EXECUTE FUNCTION shared.set_updated_at();


-- ----------------------------------------------------------------------------
-- PRODUCT: outbox / inbox
-- ----------------------------------------------------------------------------

CREATE SEQUENCE product.outbox_message_seq;

CREATE TABLE product.outbox_message (
    outbox_message_id UUID NOT NULL DEFAULT shared.uuid_generate_v7(),
    -- The polling cursor. Increases monotonically per row write; consumers
    -- track last_sequence_number and SELECT WHERE sequence_number > :cursor.
    sequence_number BIGINT NOT NULL DEFAULT nextval('product.outbox_message_seq'),
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(150) NOT NULL,
    event_version INT NOT NULL DEFAULT 1,
    payload JSONB NOT NULL,
    headers JSONB NOT NULL DEFAULT '{}'::jsonb,
    correlation_id UUID,
    causation_id UUID,
    status VARCHAR(30) NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'published', 'failed')),
    retry_count INT NOT NULL DEFAULT 0,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    -- Envelope-level actor (nullable; saga-driven publishes stay null).
    actor_user_id VARCHAR(64),
    PRIMARY KEY (outbox_message_id, created_at)
) PARTITION BY RANGE (created_at);

CREATE TABLE product.outbox_message_default
    PARTITION OF product.outbox_message DEFAULT;

CREATE INDEX idx_product_outbox_pending
    ON product.outbox_message(sequence_number)
    WHERE status IN ('pending', 'failed');
CREATE INDEX idx_product_outbox_aggregate
    ON product.outbox_message(aggregate_type, aggregate_id);
CREATE INDEX idx_product_outbox_correlation
    ON product.outbox_message(correlation_id) WHERE correlation_id IS NOT NULL;

CREATE TABLE product.inbox_message (
    inbox_message_id UUID NOT NULL DEFAULT shared.uuid_generate_v7(),
    message_id UUID NOT NULL,
    consumer_name VARCHAR(150) NOT NULL,
    event_type VARCHAR(150) NOT NULL,
    event_version INT NOT NULL DEFAULT 1,
    source_sequence_number BIGINT,
    payload JSONB NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'processed' CHECK (status IN ('processed', 'failed')),
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (inbox_message_id, processed_at),
    UNIQUE (message_id, consumer_name, processed_at)
) PARTITION BY RANGE (processed_at);

CREATE TABLE product.inbox_message_default
    PARTITION OF product.inbox_message DEFAULT;


-- ----------------------------------------------------------------------------
-- PRODUCT: final grants (run after all DDL so newly-created tables are covered)
-- ----------------------------------------------------------------------------
GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA product TO product_service;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA product TO product_service;

-- ----------------------------------------------------------------------------
-- PRODUCT: seed lives in db/northwood_erp_seed.sql §SEED: PRODUCT.
-- ----------------------------------------------------------------------------

COMMIT;

-- ============================================================================
-- §  SERVICE: SALES
-- File equivalent in multi-DB: services/sales/install.sql
-- ============================================================================

BEGIN;

CREATE SCHEMA IF NOT EXISTS sales;

-- Idempotent role creation. Roles are cluster-wide on a single PostgreSQL
-- instance, so this guard makes the script safe to re-run after DROP DATABASE.
-- After the database-per-service split, each service's install runs against
-- its own cluster (or its own database within a shared cluster) and creates
-- only its own role.
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sales_service') THEN
        CREATE ROLE sales_service NOLOGIN;
    END IF;
END $$;

GRANT USAGE ON SCHEMA sales TO sales_service;
GRANT USAGE ON SCHEMA shared TO sales_service;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA shared TO sales_service;


CREATE TABLE sales.customer (
    customer_id UUID PRIMARY KEY DEFAULT shared.uuid_generate_v7(),
    customer_code VARCHAR(50) NOT NULL UNIQUE,
    name VARCHAR(200) NOT NULL,
    email VARCHAR(255),
    phone VARCHAR(50),
    billing_address TEXT,
    shipping_address TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'active' CHECK (
        status IN ('active', 'inactive', 'blocked')
    ),
    -- §2.31 Slice A: default commercial payment terms for this customer.
    -- Snapshotted onto sales.sales_order_header.payment_terms at order
    -- placement (overridable per-order). 'on_shipment' = credit terms,
    -- invoice on shipment (Northwood's existing AR flow). 'prepayment' =
    -- cash with order, invoice at placement, shipment gated on payment
    -- (§2.31 Slice B+).
    default_payment_terms VARCHAR(20) NOT NULL DEFAULT 'on_shipment' CHECK (
        default_payment_terms IN ('on_shipment', 'prepayment')
    ),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by VARCHAR(64),
    last_modified_by VARCHAR(64)
);

CREATE TRIGGER trg_customer_updated_at
    BEFORE UPDATE ON sales.customer
    FOR EACH ROW EXECUTE FUNCTION shared.set_updated_at();

-- Read-side projection of product pricing for sales' use cases. Updated by
-- the SalesPriceChangedHandler inbox handler from product.SalesPriceChanged
-- events; the data of record is product.product.sales_price + currency_code.
-- No version column — the inbox dedupes by event_id, and event-time order is
-- preserved within a partition (events for the same product flow on the same
-- key, so latest-wins is naturally correct). Kept denormalised against the
-- product context to avoid a cross-schema query at order-validation time.
-- sales_price / currency_code are nullable: the row is seeded on
-- product.ProductCreated (stub) and populated on product.SalesPriceChanged.
-- A NULL sales_price means "this product hasn't been priced for sales yet"
-- (e.g. a raw material that purchasing buys but sales never lists); placeOrder
-- treats that as unsellable, identically to a discontinued product. The seed
-- makes lifecycle closure structural — one row per Product for its lifetime,
-- mirroring the inventory.stock_item and manufacturing.product_card
-- shape.
CREATE TABLE sales.product_card (
    product_id UUID PRIMARY KEY,
    sales_price NUMERIC(18, 6),
    currency_code CHAR(3),
    discontinued_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TRIGGER trg_product_card_updated_at
    BEFORE UPDATE ON sales.product_card
    FOR EACH ROW EXECUTE FUNCTION shared.set_updated_at();

CREATE TABLE sales.sales_order_header (
    sales_order_header_id UUID PRIMARY KEY DEFAULT shared.uuid_generate_v7(),
    order_number VARCHAR(50) NOT NULL UNIQUE,
    -- Within-schema FK: customer_id is owned by the same service.
    customer_id UUID NOT NULL REFERENCES sales.customer(customer_id) ON DELETE RESTRICT,
    -- Denormalised fields are still useful for projections without joins,
    -- but the FK above is the source of truth.
    customer_code VARCHAR(50) NOT NULL,
    customer_name VARCHAR(200) NOT NULL,
    order_date DATE NOT NULL DEFAULT CURRENT_DATE,
    requested_delivery_date DATE,
    -- Single workflow status. Cross-cutting flags (stock/manufacturing/invoice/
    -- payment) live on reporting.sales_order_360_view and are derived from events.
    status VARCHAR(30) NOT NULL DEFAULT 'draft' CHECK (
        status IN (
            'draft', 'submitted', 'confirmed', 'in_fulfilment',
            'shipped', 'completed', 'cancelled', 'rejected'
        )
    ),
    -- §2.31 Slice A: commercial payment terms snapshotted from
    -- sales.customer.default_payment_terms at placement (overridable per-order
    -- on the place-order command). 'on_shipment' = current credit-terms flow;
    -- 'prepayment' = cash-with-order (§2.31 Slice B+ branches the saga on it).
    payment_terms VARCHAR(20) NOT NULL DEFAULT 'on_shipment' CHECK (
        payment_terms IN ('on_shipment', 'prepayment')
    ),
    currency_code CHAR(3) NOT NULL DEFAULT 'AUD',
    -- exchange_rate against company base currency at the time of order.
    -- Required even for AUD (1.0) so reporting joins are uniform.
    -- Paired with exchange_rate_captured_at so the snapshot is verifiable
    -- against the finance.exchange_rate lookup table.
    exchange_rate NUMERIC(18, 8) NOT NULL DEFAULT 1.0 CHECK (exchange_rate > 0),
    exchange_rate_captured_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    subtotal_amount NUMERIC(18, 2) NOT NULL DEFAULT 0,
    tax_amount NUMERIC(18, 2) NOT NULL DEFAULT 0,
    total_amount NUMERIC(18, 2) NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    submitted_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_by VARCHAR(64),
    last_modified_by VARCHAR(64)
);

CREATE INDEX idx_sales_order_header_customer_id ON sales.sales_order_header(customer_id);
CREATE INDEX idx_sales_order_header_status ON sales.sales_order_header(status);
CREATE INDEX idx_sales_order_header_order_date ON sales.sales_order_header(order_date);

CREATE TRIGGER trg_sales_order_header_updated_at
    BEFORE UPDATE ON sales.sales_order_header
    FOR EACH ROW EXECUTE FUNCTION shared.set_updated_at();

CREATE TABLE sales.sales_order_line (
    sales_order_line_id UUID PRIMARY KEY DEFAULT shared.uuid_generate_v7(),
    -- RESTRICT, not CASCADE: submitted documents are not hard-deleted.
    sales_order_header_id UUID NOT NULL REFERENCES sales.sales_order_header(sales_order_header_id) ON DELETE RESTRICT,
    line_number INT NOT NULL,
    product_id UUID NOT NULL,
    product_sku VARCHAR(50) NOT NULL,
    product_name VARCHAR(200) NOT NULL,
    ordered_quantity NUMERIC(18, 4) NOT NULL CHECK (ordered_quantity > 0),
    reserved_quantity NUMERIC(18, 4) NOT NULL DEFAULT 0,
    shipped_quantity NUMERIC(18, 4) NOT NULL DEFAULT 0,
    backordered_quantity NUMERIC(18, 4) NOT NULL DEFAULT 0,
    manufacturing_required_quantity NUMERIC(18, 4) NOT NULL DEFAULT 0,
    unit_price NUMERIC(18, 6) NOT NULL CHECK (unit_price >= 0),
    tax_code VARCHAR(20),
    tax_rate NUMERIC(9, 6) NOT NULL DEFAULT 0 CHECK (tax_rate >= 0),
    tax_amount NUMERIC(18, 2) NOT NULL DEFAULT 0 CHECK (tax_amount >= 0),
    line_total NUMERIC(18, 2) NOT NULL CHECK (line_total >= 0),
    line_status VARCHAR(30) NOT NULL DEFAULT 'open' CHECK (
        line_status IN (
            'open', 'reserved', 'partially_reserved', 'waiting_for_production',
            'ready_to_ship', 'partially_shipped', 'shipped', 'cancelled'
        )
    ),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (sales_order_header_id, line_number),
    -- Quantity invariants: derived quantities cannot exceed what was ordered.
    CHECK (reserved_quantity >= 0 AND reserved_quantity <= ordered_quantity),
    CHECK (shipped_quantity >= 0 AND shipped_quantity <= ordered_quantity),
    CHECK (backordered_quantity >= 0 AND backordered_quantity <= ordered_quantity),
    CHECK (manufacturing_required_quantity >= 0 AND manufacturing_required_quantity <= ordered_quantity)
);

CREATE INDEX idx_sales_order_line_product_id ON sales.sales_order_line(product_id);

-- Status history (monthly partitioned for retention and vacuum).
CREATE TABLE sales.sales_order_status_history (
    history_id UUID NOT NULL DEFAULT shared.uuid_generate_v7(),
    sales_order_header_id UUID NOT NULL,
    old_status VARCHAR(30),
    new_status VARCHAR(30) NOT NULL,
    reason TEXT,
    changed_by VARCHAR(100),
    changed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (history_id, changed_at)
) PARTITION BY RANGE (changed_at);

CREATE TABLE sales.sales_order_status_history_default
    PARTITION OF sales.sales_order_status_history DEFAULT;

CREATE INDEX idx_sales_order_status_history_sales_order_header_id
    ON sales.sales_order_status_history(sales_order_header_id);

CREATE TABLE sales.sales_order_fulfilment_saga (
    saga_id UUID PRIMARY KEY DEFAULT shared.uuid_generate_v7(),
    sales_order_header_id UUID NOT NULL UNIQUE,
    saga_state VARCHAR(50) NOT NULL CHECK (
        saga_state IN (
            'started', 'stock_reservation_requested', 'stock_reservation_incomplete', 'rejected',
            -- §2.31 Slice B: prepayment branch states. awaiting_prepayment_invoice
            -- parks the saga after PrepaymentInvoiceRequested until finance acks
            -- with CustomerInvoiceCreated. prepaid is the active checkpoint
            -- between full payment receipt and stock reservation request
            -- (the worker picks the row up from prepaid the same way it does
            -- from started / stock_reservation_incomplete).
            'awaiting_prepayment_invoice', 'prepaid',
            'manufacturing_requested', 'manufacturing_in_progress', 'manufacturing_completed',
            -- §2.36: purchasing_requested is the symmetric branch off
            -- stock_reservation_incomplete for purchased-only short lines.
            -- The worker emits sales.SalesOrderPurchasingRequested (per-line
            -- shortages routed through inventory's ReplenishmentRequest), and
            -- the saga parks here until every outstanding ReplenishmentFulfilled
            -- for the order's products has fired — then it re-enters
            -- stock_reservation_requested to retry reservation with the now-
            -- restocked inventory.
            'purchasing_requested',
            'ready_to_ship', 'goods_shipped', 'invoice_requested', 'invoice_created',
            'invoice_partially_paid',
            'completed', 'compensating', 'compensated', 'failed'
        )
    ),
    current_step VARCHAR(100),
    last_error TEXT,
    data JSONB NOT NULL DEFAULT '{}'::jsonb,
    retry_count INT NOT NULL DEFAULT 0,
    -- Worker scheduling: SELECT ... FOR UPDATE SKIP LOCKED WHERE next_retry_at <= now()
    -- ensures multiple workers don't pick up the same saga.
    next_retry_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    lease_owner VARCHAR(100),
    lease_expires_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ
);

CREATE INDEX idx_sales_order_fulfilment_saga_state
    ON sales.sales_order_fulfilment_saga(saga_state);
CREATE INDEX idx_sales_order_fulfilment_saga_due
    ON sales.sales_order_fulfilment_saga(next_retry_at)
    WHERE saga_state NOT IN ('completed', 'compensated', 'failed');
CREATE INDEX idx_sales_order_fulfilment_saga_data
    ON sales.sales_order_fulfilment_saga USING gin (data jsonb_path_ops);

CREATE TRIGGER trg_sales_order_fulfilment_saga_updated_at
    BEFORE UPDATE ON sales.sales_order_fulfilment_saga
    FOR EACH ROW EXECUTE FUNCTION shared.set_updated_at();


-- ----------------------------------------------------------------------------
-- SALES: outbox / inbox
-- ----------------------------------------------------------------------------

-- sales
CREATE SEQUENCE sales.outbox_message_seq;

CREATE TABLE sales.outbox_message (
    outbox_message_id UUID NOT NULL DEFAULT shared.uuid_generate_v7(),
    sequence_number BIGINT NOT NULL DEFAULT nextval('sales.outbox_message_seq'),
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(150) NOT NULL,
    event_version INT NOT NULL DEFAULT 1,
    payload JSONB NOT NULL,
    headers JSONB NOT NULL DEFAULT '{}'::jsonb,
    correlation_id UUID,
    causation_id UUID,
    status VARCHAR(30) NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'published', 'failed')),
    retry_count INT NOT NULL DEFAULT 0,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    -- Envelope-level actor (nullable; saga-driven publishes stay null).
    actor_user_id VARCHAR(64),
    PRIMARY KEY (outbox_message_id, created_at)
) PARTITION BY RANGE (created_at);
CREATE TABLE sales.outbox_message_default PARTITION OF sales.outbox_message DEFAULT;
CREATE INDEX idx_sales_outbox_pending ON sales.outbox_message(sequence_number) WHERE status IN ('pending', 'failed');
CREATE INDEX idx_sales_outbox_aggregate ON sales.outbox_message(aggregate_type, aggregate_id);
CREATE INDEX idx_sales_outbox_correlation ON sales.outbox_message(correlation_id) WHERE correlation_id IS NOT NULL;

CREATE TABLE sales.inbox_message (
    inbox_message_id UUID NOT NULL DEFAULT shared.uuid_generate_v7(),
    message_id UUID NOT NULL,
    consumer_name VARCHAR(150) NOT NULL,
    event_type VARCHAR(150) NOT NULL,
    event_version INT NOT NULL DEFAULT 1,
    source_sequence_number BIGINT,
    payload JSONB NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'processed' CHECK (status IN ('processed', 'failed')),
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (inbox_message_id, processed_at),
    UNIQUE (message_id, consumer_name, processed_at)
) PARTITION BY RANGE (processed_at);
CREATE TABLE sales.inbox_message_default PARTITION OF sales.inbox_message DEFAULT;


-- ----------------------------------------------------------------------------
-- SALES: final grants (run after all DDL so newly-created tables are covered)
-- ----------------------------------------------------------------------------
GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA sales TO sales_service;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA sales TO sales_service;

-- ----------------------------------------------------------------------------
-- SALES: seed lives in db/northwood_erp_seed.sql §SEED: SALES.
-- ----------------------------------------------------------------------------

COMMIT;

-- ============================================================================
-- §  SERVICE: INVENTORY
-- File equivalent in multi-DB: services/inventory/install.sql
-- ============================================================================

BEGIN;

CREATE SCHEMA IF NOT EXISTS inventory;

-- Idempotent role creation. Roles are cluster-wide on a single PostgreSQL
-- instance, so this guard makes the script safe to re-run after DROP DATABASE.
-- After the database-per-service split, each service's install runs against
-- its own cluster (or its own database within a shared cluster) and creates
-- only its own role.
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'inventory_service') THEN
        CREATE ROLE inventory_service NOLOGIN;
    END IF;
END $$;

GRANT USAGE ON SCHEMA inventory TO inventory_service;
GRANT USAGE ON SCHEMA shared TO inventory_service;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA shared TO inventory_service;


CREATE TABLE inventory.warehouse (
    warehouse_id UUID PRIMARY KEY DEFAULT shared.uuid_generate_v7(),
    warehouse_code VARCHAR(50) NOT NULL UNIQUE,
    name VARCHAR(200) NOT NULL,
    address TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'inactive')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE inventory.stock_item (
    stock_item_id UUID PRIMARY KEY DEFAULT shared.uuid_generate_v7(),
    product_id UUID NOT NULL UNIQUE,
    product_sku VARCHAR(50) NOT NULL,
    product_name VARCHAR(200) NOT NULL,
    product_type VARCHAR(30) NOT NULL CHECK (
        product_type IN ('raw_material', 'finished_good', 'semi_finished_good', 'service')
    ),
    base_uom_code VARCHAR(20) NOT NULL,
    stock_tracking_mode VARCHAR(20) NOT NULL DEFAULT 'tracked' CHECK (
        stock_tracking_mode IN ('tracked', 'not_tracked')
    ),
    -- Reorder policy is owned here: it is a per-SKU inventory planning
    -- parameter, not a catalogue attribute. The non-authoritative columns
    -- above (sku/name/type/uom) are still projected from product events.
    reorder_point NUMERIC(18, 4) NOT NULL DEFAULT 0,
    reorder_quantity NUMERIC(18, 4) NOT NULL DEFAULT 0,
    -- Stamped from product.ProductDiscontinued so reorder-alert logic can
    -- suppress alerts for retired SKUs (§1F.1).
    discontinued_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TRIGGER trg_stock_item_updated_at
    BEFORE UPDATE ON inventory.stock_item
    FOR EACH ROW EXECUTE FUNCTION shared.set_updated_at();

-- §2.35 Slice A: inventory-side projection of product.MakeVsBuyChanged.
-- Mirrors manufacturing.product_card's replenishment columns — duplicate
-- projection across services is the accepted cost of cross-schema isolation.
-- Read by the §2.35 reorder-point detection service (Slice B) to decide
-- whether to route a replenishment to manufacturing or purchasing.
-- Seed defaults: ProductCreated derives (is_purchased, is_manufactured) from
-- product_type (RAW_MATERIAL/SERVICE → buy-only, FINISHED_GOOD/SEMI_FINISHED
-- → make-only) so the table is non-empty for day-zero SKUs before any
-- MakeVsBuyChanged event arrives.
CREATE TABLE inventory.product_card (
    product_id        UUID PRIMARY KEY,
    is_purchased      BOOLEAN NOT NULL DEFAULT false,
    is_manufactured   BOOLEAN NOT NULL DEFAULT false,
    discontinued_at   TIMESTAMPTZ,
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TRIGGER trg_product_card_updated_at
    BEFORE UPDATE ON inventory.product_card
    FOR EACH ROW EXECUTE FUNCTION shared.set_updated_at();

-- §2.35 Slice B: inventory-orchestrated replenishment requests.
-- Raised by ReplenishmentDetectionService on the two on-hand-decrement paths
-- (shipment, stock adjustment) when on_hand < reorder_point and no open
-- request exists for the (product, warehouse) pair. Also raised by the §2.35
-- shortage-to-replenishment bridge (Slice C, manufacturing.RawMaterialShortageDetected)
-- with reason='work_order_shortage' — the same aggregate handles both triggers.
--
-- The one-open-per-(product,warehouse) invariant is enforced by the partial
-- unique index below, NOT by an in-Java check, because that's the only
-- race-safe way under concurrent decrements. The detection service catches
-- the PG unique-violation and converts it to a debug-logged no-op.
--
-- dispatched_aggregate_kind / dispatched_aggregate_id are populated by
-- Slice E's close-the-loop handlers when the downstream WO or PR receives
-- the request. linked_purchase_order_id is stamped when the PR converts to
-- a PO (so GoodsReceived can resolve to this request).
CREATE TABLE inventory.replenishment_request (
    replenishment_request_id    UUID PRIMARY KEY,
    product_id                  UUID NOT NULL,
    warehouse_id                UUID NOT NULL REFERENCES inventory.warehouse(warehouse_id),
    requested_quantity          NUMERIC(18, 4) NOT NULL CHECK (requested_quantity > 0),
    target_service              VARCHAR(20) NOT NULL CHECK (target_service IN ('manufacturing', 'purchasing')),
    -- §2.36: sales_order_shortage is the third reason — a sales order
    -- line short on stock for a purchased-only SKU. Distinct from
    -- reorder_point_breach (proactive policy-driven) and
    -- work_order_shortage (manufacturing's raw-material bridge); these
    -- are reactive demand-driven requests carrying a source-line back-
    -- reference so the sales saga can un-park on fulfilment.
    reason                      VARCHAR(40) NOT NULL CHECK (reason IN ('reorder_point_breach', 'work_order_shortage', 'sales_order_shortage')),
    status                      VARCHAR(20) NOT NULL DEFAULT 'requested' CHECK (status IN ('requested', 'dispatched', 'fulfilled', 'cancelled')),
    dispatched_aggregate_kind   VARCHAR(30) CHECK (dispatched_aggregate_kind IN ('work_order', 'purchase_requisition')),
    dispatched_aggregate_id     UUID,
    linked_purchase_order_id    UUID,
    -- §2.36: back-reference to the sales-order line that triggered a
    -- sales_order_shortage replenishment. Populated only when
    -- reason='sales_order_shortage'; null for the other two reasons
    -- (proactive reorder-point + WO-raw-material-shortage have no
    -- single source line to attribute to). Stamped onto
    -- ReplenishmentFulfilled payload at markFulfilled time so sales
    -- can un-park the corresponding fulfilment saga.
    -- source_sales_order_header_id sits alongside (same nullable
    -- semantic) so the fan-in handler can route to the saga without
    -- a cross-schema join — sales is keyed by header_id, not line_id.
    source_sales_order_header_id UUID,
    source_sales_order_line_id   UUID,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    dispatched_at               TIMESTAMPTZ,
    fulfilled_at                TIMESTAMPTZ,
    cancelled_at                TIMESTAMPTZ,
    version                     BIGINT NOT NULL DEFAULT 0,
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One-open invariant: at most one open (requested / dispatched) request
-- per (product, warehouse) for the proactive policy-driven and WO-shortage
-- paths — re-triggering while a request is pending is a no-op. Sales-
-- order-shortage requests are EXCLUDED from this constraint: every short
-- SO line raises its own request (back-referenced to the line) so the
-- sales saga can wait for ITS specific replenishment to land, even when
-- multiple sales orders are short on the same SKU.
CREATE UNIQUE INDEX uq_replenishment_request_open
    ON inventory.replenishment_request (product_id, warehouse_id)
    WHERE status IN ('requested', 'dispatched')
      AND reason <> 'sales_order_shortage';

CREATE INDEX idx_replenishment_request_dispatched_aggregate
    ON inventory.replenishment_request (dispatched_aggregate_id)
    WHERE dispatched_aggregate_id IS NOT NULL;

CREATE INDEX idx_replenishment_request_linked_purchase_order
    ON inventory.replenishment_request (linked_purchase_order_id)
    WHERE linked_purchase_order_id IS NOT NULL;

CREATE INDEX idx_replenishment_request_source_sales_order_line
    ON inventory.replenishment_request (source_sales_order_line_id)
    WHERE source_sales_order_line_id IS NOT NULL;

CREATE TRIGGER trg_replenishment_request_updated_at
    BEFORE UPDATE ON inventory.replenishment_request
    FOR EACH ROW EXECUTE FUNCTION shared.set_updated_at();

CREATE TABLE inventory.stock_balance (
    stock_balance_id UUID PRIMARY KEY DEFAULT shared.uuid_generate_v7(),
    warehouse_id UUID NOT NULL REFERENCES inventory.warehouse(warehouse_id),
    product_id UUID NOT NULL,
    on_hand_quantity NUMERIC(18, 4) NOT NULL DEFAULT 0,
    reserved_quantity NUMERIC(18, 4) NOT NULL DEFAULT 0,
    available_quantity NUMERIC(18, 4) GENERATED ALWAYS AS (on_hand_quantity - reserved_quantity) STORED,
    average_cost NUMERIC(18, 6) NOT NULL DEFAULT 0,
    -- Optimistic concurrency control. Application layer issues
    --   UPDATE ... SET ..., version = version + 1 WHERE version = :expected
    -- and retries on zero-row-affected.
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (warehouse_id, product_id),
    CHECK (on_hand_quantity >= 0),
    CHECK (reserved_quantity >= 0),
    CHECK (on_hand_quantity >= reserved_quantity)
);

CREATE INDEX idx_stock_balance_product_id ON inventory.stock_balance(product_id);

CREATE TRIGGER trg_stock_balance_updated_at
    BEFORE UPDATE ON inventory.stock_balance
    FOR EACH ROW EXECUTE FUNCTION shared.set_updated_at();

-- Work-in-progress balance for sub-assembly outputs. Bumped when a child
-- WO completes (parentWorkOrderId non-null on the manufacturing event);
-- the produced sub-assemblies sit here until consumed by the parent. The
-- consume path is parked — see dev-todo.
CREATE TABLE inventory.wip_balance (
    wip_balance_id UUID PRIMARY KEY DEFAULT shared.uuid_generate_v7(),
    warehouse_id UUID NOT NULL REFERENCES inventory.warehouse(warehouse_id) ON DELETE RESTRICT,
    product_id UUID NOT NULL,
    on_hand_quantity NUMERIC(18, 4) NOT NULL DEFAULT 0 CHECK (on_hand_quantity >= 0),
    average_cost NUMERIC(18, 6) NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (warehouse_id, product_id)
);

CREATE INDEX idx_wip_balance_product ON inventory.wip_balance(product_id);

CREATE TRIGGER trg_wip_balance_updated_at
    BEFORE UPDATE ON inventory.wip_balance
    FOR EACH ROW EXECUTE FUNCTION shared.set_updated_at();

-- Reservations: source_type/source_id replaced with two nullable FKs.
-- Exactly one must be set, enforced by CHECK.
-- We don't FK across the sales/manufacturing schema boundary by FK constraint
-- (microservice rule), but the column split makes the polymorphic relationship
-- explicit and queryable.
CREATE TABLE inventory.stock_reservation_header (
    stock_reservation_header_id UUID PRIMARY KEY DEFAULT shared.uuid_generate_v7(),
    sales_order_header_id UUID,
    work_order_id UUID,
    warehouse_id UUID NOT NULL REFERENCES inventory.warehouse(warehouse_id),
    status VARCHAR(30) NOT NULL DEFAULT 'pending' CHECK (
        status IN ('pending', 'reserved', 'partially_reserved', 'failed', 'released', 'consumed')
    ),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by VARCHAR(64),
    last_modified_by VARCHAR(64),
    -- Exactly one source must be set.
    CHECK (
        (sales_order_header_id IS NOT NULL)::int + (work_order_id IS NOT NULL)::int = 1
    ),
    -- Each business document gets at most one open reservation aggregate.
    UNIQUE (sales_order_header_id),
    UNIQUE (work_order_id)
);

CREATE TRIGGER trg_stock_reservation_header_updated_at
    BEFORE UPDATE ON inventory.stock_reservation_header
    FOR EACH ROW EXECUTE FUNCTION shared.set_updated_at();

CREATE TABLE inventory.stock_reservation_line (
    stock_reservation_line_id UUID PRIMARY KEY DEFAULT shared.uuid_generate_v7(),
    stock_reservation_header_id UUID NOT NULL REFERENCES inventory.stock_reservation_header(stock_reservation_header_id) ON DELETE RESTRICT,
    product_id UUID NOT NULL,
    product_sku VARCHAR(50) NOT NULL,
    product_name VARCHAR(200) NOT NULL,
    requested_quantity NUMERIC(18, 4) NOT NULL CHECK (requested_quantity > 0),
    reserved_quantity NUMERIC(18, 4) NOT NULL DEFAULT 0,
    status VARCHAR(30) NOT NULL DEFAULT 'pending' CHECK (
        status IN ('pending', 'reserved', 'partially_reserved', 'failed', 'released', 'consumed')
    ),
    shortage_quantity NUMERIC(18, 4) NOT NULL DEFAULT 0,
    CHECK (reserved_quantity >= 0 AND reserved_quantity <= requested_quantity),
    CHECK (shortage_quantity >= 0 AND shortage_quantity <= requested_quantity)
);

CREATE INDEX idx_stock_reservation_header_sales_order
    ON inventory.stock_reservation_header(sales_order_header_id) WHERE sales_order_header_id IS NOT NULL;
CREATE INDEX idx_stock_reservation_header_work_order
    ON inventory.stock_reservation_header(work_order_id) WHERE work_order_id IS NOT NULL;
CREATE INDEX idx_stock_reservation_line_product_id
    ON inventory.stock_reservation_line(product_id);

-- Stock movements: append-only, monthly-partitioned for archival/vacuum.
CREATE TABLE inventory.stock_movement (
    stock_movement_id UUID NOT NULL DEFAULT shared.uuid_generate_v7(),
    warehouse_id UUID NOT NULL REFERENCES inventory.warehouse(warehouse_id),
    product_id UUID NOT NULL,
    product_sku VARCHAR(50) NOT NULL,
    product_name VARCHAR(200) NOT NULL,
    movement_type VARCHAR(40) NOT NULL CHECK (
        movement_type IN (
            'purchase_receipt', 'sales_shipment', 'material_issue', 'finished_goods_receipt',
            'stock_adjustment_in', 'stock_adjustment_out', 'reservation_release'
        )
    ),
    direction VARCHAR(10) NOT NULL CHECK (direction IN ('in', 'out')),
    quantity NUMERIC(18, 4) NOT NULL CHECK (quantity > 0),
    unit_cost NUMERIC(18, 6) NOT NULL DEFAULT 0,
    total_cost NUMERIC(18, 2) NOT NULL DEFAULT 0,
    source_type VARCHAR(40),
    source_id UUID,
    source_line_id UUID,
    movement_date TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (stock_movement_id, movement_date)
) PARTITION BY RANGE (movement_date);

CREATE TABLE inventory.stock_movement_default
    PARTITION OF inventory.stock_movement DEFAULT;

CREATE INDEX idx_stock_movement_product_id ON inventory.stock_movement(product_id);
CREATE INDEX idx_stock_movement_source ON inventory.stock_movement(source_type, source_id);
CREATE INDEX idx_stock_movement_date ON inventory.stock_movement(movement_date);

CREATE TABLE inventory.goods_receipt_header (
    goods_receipt_header_id UUID PRIMARY KEY DEFAULT shared.uuid_generate_v7(),
    goods_receipt_number VARCHAR(50) NOT NULL UNIQUE,
    purchase_order_header_id UUID NOT NULL,
    supplier_id UUID,
    supplier_name VARCHAR(200),
    warehouse_id UUID NOT NULL REFERENCES inventory.warehouse(warehouse_id),
    receipt_date DATE NOT NULL DEFAULT CURRENT_DATE,
    status VARCHAR(30) NOT NULL DEFAULT 'draft' CHECK (status IN ('draft', 'posted', 'reversed')),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    posted_at TIMESTAMPTZ,
    created_by VARCHAR(64),
    last_modified_by VARCHAR(64)
);

CREATE TRIGGER trg_goods_receipt_header_updated_at
    BEFORE UPDATE ON inventory.goods_receipt_header
    FOR EACH ROW EXECUTE FUNCTION shared.set_updated_at();

CREATE TABLE inventory.goods_receipt_line (
    goods_receipt_line_id UUID PRIMARY KEY DEFAULT shared.uuid_generate_v7(),
    goods_receipt_header_id UUID NOT NULL REFERENCES inventory.goods_receipt_header(goods_receipt_header_id) ON DELETE RESTRICT,
    purchase_order_line_id UUID,
    product_id UUID NOT NULL,
    product_sku VARCHAR(50) NOT NULL,
    product_name VARCHAR(200) NOT NULL,
    received_quantity NUMERIC(18, 4) NOT NULL CHECK (received_quantity > 0),
    unit_cost NUMERIC(18, 6) NOT NULL DEFAULT 0,
    line_cost NUMERIC(18, 2) NOT NULL DEFAULT 0
);

CREATE INDEX idx_goods_receipt_header_po_id ON inventory.goods_receipt_header(purchase_order_header_id);

-- Stock adjustments: a warehouse operator's manual inventory gain/loss
-- (cycle-count correction, damage, shrinkage, demo setup). Post-only header,
-- single product per adjustment. The signed change is recorded as a positive
-- magnitude + a direction; on_hand is derived from the resulting stock_movement
-- (never set directly), and the adjustment emits inventory.StockAdjusted so
-- finance posts the corresponding GL entry. §2.29.
CREATE TABLE inventory.stock_adjustment (
    stock_adjustment_id UUID PRIMARY KEY DEFAULT shared.uuid_generate_v7(),
    adjustment_number VARCHAR(50) NOT NULL UNIQUE,
    warehouse_id UUID NOT NULL REFERENCES inventory.warehouse(warehouse_id),
    product_id UUID NOT NULL,
    product_sku VARCHAR(50) NOT NULL,
    product_name VARCHAR(200) NOT NULL,
    direction VARCHAR(10) NOT NULL CHECK (direction IN ('in', 'out')),
    quantity NUMERIC(18, 4) NOT NULL CHECK (quantity > 0),
    reason VARCHAR(500) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'posted' CHECK (status IN ('draft', 'posted', 'reversed')),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    posted_at TIMESTAMPTZ,
    created_by VARCHAR(64),
    last_modified_by VARCHAR(64)
);

CREATE INDEX idx_stock_adjustment_product_id ON inventory.stock_adjustment(product_id);

CREATE TRIGGER trg_stock_adjustment_updated_at
    BEFORE UPDATE ON inventory.stock_adjustment
    FOR EACH ROW EXECUTE FUNCTION shared.set_updated_at();

CREATE TABLE inventory.shipment_header (
    shipment_header_id UUID PRIMARY KEY DEFAULT shared.uuid_generate_v7(),
    shipment_number VARCHAR(50) NOT NULL UNIQUE,
    sales_order_header_id UUID NOT NULL,
    customer_id UUID,
    customer_name VARCHAR(200),
    warehouse_id UUID NOT NULL REFERENCES inventory.warehouse(warehouse_id),
    shipment_date DATE NOT NULL DEFAULT CURRENT_DATE,
    status VARCHAR(30) NOT NULL DEFAULT 'draft' CHECK (status IN ('draft', 'posted', 'reversed')),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    posted_at TIMESTAMPTZ,
    created_by VARCHAR(64),
    last_modified_by VARCHAR(64)
);

CREATE TRIGGER trg_shipment_header_updated_at
    BEFORE UPDATE ON inventory.shipment_header
    FOR EACH ROW EXECUTE FUNCTION shared.set_updated_at();

CREATE TABLE inventory.shipment_line (
    shipment_line_id UUID PRIMARY KEY DEFAULT shared.uuid_generate_v7(),
    shipment_header_id UUID NOT NULL REFERENCES inventory.shipment_header(shipment_header_id) ON DELETE RESTRICT,
    sales_order_line_id UUID,
    product_id UUID NOT NULL,
    product_sku VARCHAR(50) NOT NULL,
    product_name VARCHAR(200) NOT NULL,
    shipped_quantity NUMERIC(18, 4) NOT NULL CHECK (shipped_quantity > 0),
    unit_cost NUMERIC(18, 6) NOT NULL DEFAULT 0,
    line_cost NUMERIC(18, 2) NOT NULL DEFAULT 0
);

CREATE INDEX idx_shipment_header_sales_order_header_id ON inventory.shipment_header(sales_order_header_id);

-- Inventory-local projections of sales-order / purchase-order line shape.
-- Maintained by inbox handlers consuming sales.SalesOrderPlaced and
-- purchasing.PurchaseOrderCreated; read by ShipmentService / GoodsReceiptService
-- to validate that each posted line's product_id matches the originating SO/PO
-- line. Defence-in-depth against a client that mismatches productId for a line.
CREATE TABLE inventory.sales_order_line_facts (
    sales_order_line_id UUID PRIMARY KEY,
    sales_order_header_id UUID NOT NULL,
    product_id UUID NOT NULL,
    -- §2.31 Slice C: payment_terms snapshotted from sales.SalesOrderPlaced;
    -- prepayment_settled flipped true by sales.SalesOrderPrepaymentSettled.
    -- ShipmentService.post refuses prepayment orders with prepayment_settled=false
    -- (HTTP 409). Both columns repeat on every line (denormalised against the
    -- header-level fact in sales) — the facts row is the only inventory-side
    -- read for shipment gating, so one query covers both validations.
    payment_terms VARCHAR(20) NOT NULL DEFAULT 'on_shipment' CHECK (
        payment_terms IN ('on_shipment', 'prepayment')
    ),
    prepayment_settled BOOLEAN NOT NULL DEFAULT false,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_sales_order_line_facts_header ON inventory.sales_order_line_facts(sales_order_header_id);
CREATE TRIGGER trg_sales_order_line_facts_updated_at
    BEFORE UPDATE ON inventory.sales_order_line_facts
    FOR EACH ROW EXECUTE FUNCTION shared.set_updated_at();

CREATE TABLE inventory.purchase_order_line_facts (
    purchase_order_line_id UUID PRIMARY KEY,
    purchase_order_header_id UUID NOT NULL,
    product_id UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_purchase_order_line_facts_header ON inventory.purchase_order_line_facts(purchase_order_header_id);
CREATE TRIGGER trg_purchase_order_line_facts_updated_at
    BEFORE UPDATE ON inventory.purchase_order_line_facts
    FOR EACH ROW EXECUTE FUNCTION shared.set_updated_at();


-- ----------------------------------------------------------------------------
-- INVENTORY: outbox / inbox
-- ----------------------------------------------------------------------------

-- inventory
CREATE SEQUENCE inventory.outbox_message_seq;
CREATE TABLE inventory.outbox_message (
    outbox_message_id UUID NOT NULL DEFAULT shared.uuid_generate_v7(),
    sequence_number BIGINT NOT NULL DEFAULT nextval('inventory.outbox_message_seq'),
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(150) NOT NULL,
    event_version INT NOT NULL DEFAULT 1,
    payload JSONB NOT NULL,
    headers JSONB NOT NULL DEFAULT '{}'::jsonb,
    correlation_id UUID,
    causation_id UUID,
    status VARCHAR(30) NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'published', 'failed')),
    retry_count INT NOT NULL DEFAULT 0,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    -- Envelope-level actor (nullable; saga-driven publishes stay null).
    actor_user_id VARCHAR(64),
    PRIMARY KEY (outbox_message_id, created_at)
) PARTITION BY RANGE (created_at);
CREATE TABLE inventory.outbox_message_default PARTITION OF inventory.outbox_message DEFAULT;
CREATE INDEX idx_inventory_outbox_pending ON inventory.outbox_message(sequence_number) WHERE status IN ('pending', 'failed');
CREATE INDEX idx_inventory_outbox_aggregate ON inventory.outbox_message(aggregate_type, aggregate_id);
CREATE INDEX idx_inventory_outbox_correlation ON inventory.outbox_message(correlation_id) WHERE correlation_id IS NOT NULL;

CREATE TABLE inventory.inbox_message (
    inbox_message_id UUID NOT NULL DEFAULT shared.uuid_generate_v7(),
    message_id UUID NOT NULL,
    consumer_name VARCHAR(150) NOT NULL,
    event_type VARCHAR(150) NOT NULL,
    event_version INT NOT NULL DEFAULT 1,
    source_sequence_number BIGINT,
    payload JSONB NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'processed' CHECK (status IN ('processed', 'failed')),
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (inbox_message_id, processed_at),
    UNIQUE (message_id, consumer_name, processed_at)
) PARTITION BY RANGE (processed_at);
CREATE TABLE inventory.inbox_message_default PARTITION OF inventory.inbox_message DEFAULT;


-- ----------------------------------------------------------------------------
-- INVENTORY: final grants (run after all DDL so newly-created tables are covered)
-- ----------------------------------------------------------------------------
GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA inventory TO inventory_service;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA inventory TO inventory_service;

-- ----------------------------------------------------------------------------
-- INVENTORY: seed lives in db/northwood_erp_seed.sql §SEED: INVENTORY.
-- ----------------------------------------------------------------------------

COMMIT;

-- ============================================================================
-- §  SERVICE: MANUFACTURING
-- File equivalent in multi-DB: services/manufacturing/install.sql
-- ============================================================================

BEGIN;

CREATE SCHEMA IF NOT EXISTS manufacturing;

-- Idempotent role creation. Roles are cluster-wide on a single PostgreSQL
-- instance, so this guard makes the script safe to re-run after DROP DATABASE.
-- After the database-per-service split, each service's install runs against
-- its own cluster (or its own database within a shared cluster) and creates
-- only its own role.
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'manufacturing_service') THEN
        CREATE ROLE manufacturing_service NOLOGIN;
    END IF;
END $$;

GRANT USAGE ON SCHEMA manufacturing TO manufacturing_service;
GRANT USAGE ON SCHEMA shared TO manufacturing_service;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA shared TO manufacturing_service;


-- Manufacturing's consolidated denormalized card per Product (see
-- docs/conventions.md → Consumer-side denormalized tables). One row per
-- product holds:
--
--   * Replenishment classification (is_purchased / is_manufactured —
--     mirrored from product.MakeVsBuyChanged via MakeVsBuyChangedHandler;
--     seeded with type-derived defaults on product.ProductCreated). A row
--     missing for a product_id is treated conservatively as both flags
--     false → ManufacturingRequestedHandler rejects.
--
--   * Discontinued-at timestamp (mirrored from product.ProductDiscontinued
--     via ProductDiscontinuedHandler). BomService.addLine reads this
--     to reject new BOM lines that name a discontinued component; distinct
--     signal from both-flags-false, which can also occur on a freshly-
--     seeded never-classified row.
--
--   * Active-BOM pointer (active_bom_header_id — mirrored from
--     product.ActiveBomChanged via ActiveBomChangedHandler).
--
--   * Materials-cost rollup output (materials_cost + currency_code +
--     materials_cost_reason + materials_cost_captured_at) — locally
--     computed by MaterialsCostRollupService rather than mirrored. NULL
--     materials_cost = "inputs_missing"; reason values: 'supplier_price_change',
--     'bom_activated', 'inputs_missing', etc. materials_cost_captured_at
--     carries the event-time of the upstream trigger (StandardCostChanged
--     / SupplierProductPriceChanged / BomActivated), distinct from the
--     row-level updated_at.
--
-- Replaces the previous narrow tables product_replenishment +
-- product_active_bom + product_materials_cost — they were named after
-- individual facets rather than after manufacturing's view of the
-- aggregate, against the cardinality-based naming rule.
CREATE TABLE manufacturing.product_card (
    product_id                    UUID PRIMARY KEY,
    is_purchased                  BOOLEAN NOT NULL DEFAULT false,
    is_manufactured               BOOLEAN NOT NULL DEFAULT false,
    discontinued_at               TIMESTAMPTZ,
    active_bom_header_id          UUID,
    materials_cost                NUMERIC(18, 6),
    currency_code                 CHAR(3),
    materials_cost_reason         VARCHAR(40) NOT NULL DEFAULT 'inputs_missing',
    materials_cost_captured_at    TIMESTAMPTZ,
    updated_at                    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TRIGGER trg_product_card_updated_at
    BEFORE UPDATE ON manufacturing.product_card
    FOR EACH ROW EXECUTE FUNCTION shared.set_updated_at();

-- §2.8 Slice C: manufacturing-side projection of product.ApprovedVendorListChanged.
-- Mirrors purchasing.product_approved_vendor — duplicate projection across services
-- is the accepted cost of cross-schema isolation. Read by the rollup engine to
-- find the preferred supplier when computing materialsCost for a purchased item.
CREATE TABLE manufacturing.product_approved_vendor (
    product_id UUID NOT NULL,
    supplier_id UUID NOT NULL,
    supplier_code VARCHAR(50),
    supplier_name VARCHAR(200),
    is_preferred BOOLEAN NOT NULL DEFAULT false,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (product_id, supplier_id)
);

CREATE INDEX idx_product_approved_vendor_mfg_preferred
    ON manufacturing.product_approved_vendor(product_id)
    WHERE is_preferred = true;

CREATE TRIGGER trg_product_approved_vendor_mfg_updated_at
    BEFORE UPDATE ON manufacturing.product_approved_vendor
    FOR EACH ROW EXECUTE FUNCTION shared.set_updated_at();

-- Resources that perform work-order operations: a CNC machine, an assembly
-- station, a finishing booth. Standalone, no children.
CREATE TABLE manufacturing.work_center (
    work_center_id UUID PRIMARY KEY DEFAULT shared.uuid_generate_v7(),
    work_center_code VARCHAR(50) NOT NULL UNIQUE,
    name VARCHAR(200) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'inactive')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);


CREATE TABLE manufacturing.bom_header (
    bom_header_id UUID PRIMARY KEY DEFAULT shared.uuid_generate_v7(),
    finished_product_id UUID NOT NULL,
    finished_product_sku VARCHAR(50) NOT NULL,
    finished_product_name VARCHAR(200) NOT NULL,
    version VARCHAR(20) NOT NULL DEFAULT '1',
    status VARCHAR(30) NOT NULL DEFAULT 'draft' CHECK (status IN ('draft', 'active', 'inactive')),
    row_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by VARCHAR(64),
    last_modified_by VARCHAR(64),
    UNIQUE (finished_product_id, version)
);

-- Only one BOM per finished_product_id may be 'active' at a time.
CREATE UNIQUE INDEX uq_bom_active_per_product
    ON manufacturing.bom_header(finished_product_id) WHERE status = 'active';

CREATE TRIGGER trg_bom_header_updated_at
    BEFORE UPDATE ON manufacturing.bom_header
    FOR EACH ROW EXECUTE FUNCTION shared.set_updated_at();

CREATE TABLE manufacturing.bom_line (
    bom_line_id UUID PRIMARY KEY DEFAULT shared.uuid_generate_v7(),
    bom_header_id UUID NOT NULL REFERENCES manufacturing.bom_header(bom_header_id) ON DELETE RESTRICT,
    line_number INT NOT NULL,
    component_product_id UUID NOT NULL,
    component_sku VARCHAR(50) NOT NULL,
    component_name VARCHAR(200) NOT NULL,
    -- Component classification: 'raw' for purchased materials,
    -- 'sub_assembly' if the component itself has an active BOM (recursive case).
    -- Saga / planning logic is responsible for recursing on sub_assembly rows.
    component_kind VARCHAR(20) NOT NULL DEFAULT 'raw' CHECK (
        component_kind IN ('raw', 'sub_assembly')
    ),
    quantity_per_finished_unit NUMERIC(18, 6) NOT NULL CHECK (quantity_per_finished_unit > 0),
    scrap_factor_percent NUMERIC(9, 4) NOT NULL DEFAULT 0,
    UNIQUE (bom_header_id, line_number),
    -- A BOM may not list the same component twice.
    UNIQUE (bom_header_id, component_product_id),
    -- A finished product cannot be its own component.
    CHECK (component_product_id <> bom_header_id) -- noop type-wise; real cycle prevention is in app code
);

CREATE INDEX idx_bom_line_component_product_id ON manufacturing.bom_line(component_product_id);

CREATE TABLE manufacturing.work_order (
    work_order_id UUID PRIMARY KEY DEFAULT shared.uuid_generate_v7(),
    work_order_number VARCHAR(50) NOT NULL UNIQUE,
    -- Source disambiguation: a work order is either standalone ("manual"),
    -- traced to a sales-order line (make-to-order), or traced to a
    -- §2.35 inventory.replenishment_request (stock replenishment).
    sales_order_header_id UUID,
    sales_order_line_id UUID,
    -- §2.35 Slice C: populated when a stock-replenishment WO is released by
    -- manufacturing.ReplenishmentRequestedHandler. Mutually exclusive with
    -- the sales-order columns (see CHECK below). Slice E's close-the-loop
    -- handler reads this when the WO completes to flip the replenishment
    -- status → 'fulfilled'.
    replenishment_request_id UUID,
    parent_work_order_id UUID REFERENCES manufacturing.work_order(work_order_id) ON DELETE RESTRICT,
    finished_product_id UUID NOT NULL,
    finished_product_sku VARCHAR(50) NOT NULL,
    finished_product_name VARCHAR(200) NOT NULL,
    bom_header_id UUID REFERENCES manufacturing.bom_header(bom_header_id),
    planned_quantity NUMERIC(18, 4) NOT NULL CHECK (planned_quantity > 0),
    completed_quantity NUMERIC(18, 4) NOT NULL DEFAULT 0,
    scrapped_quantity NUMERIC(18, 4) NOT NULL DEFAULT 0,
    status VARCHAR(40) NOT NULL DEFAULT 'planned' CHECK (
        status IN (
            'planned', 'material_check_pending', 'waiting_for_materials', 'released',
            'in_progress', 'partially_completed', 'completed', 'closed', 'cancelled', 'blocked'
        )
    ),
    material_status VARCHAR(40) NOT NULL DEFAULT 'not_checked' CHECK (
        material_status IN (
            'not_checked', 'reservation_pending', 'reserved', 'partially_reserved', 'shortage', 'issued'
        )
    ),
    planned_start_date DATE,
    planned_end_date DATE,
    actual_start_at TIMESTAMPTZ,
    actual_completed_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by VARCHAR(64),
    last_modified_by VARCHAR(64),
    -- Quantity invariants: completed + scrapped cannot exceed planned.
    CHECK (completed_quantity >= 0),
    CHECK (scrapped_quantity >= 0),
    CHECK (completed_quantity + scrapped_quantity <= planned_quantity),
    -- §2.35 Slice C: origin is one of three mutually-exclusive shapes:
    --   1) manual          — all three origin columns NULL
    --   2) make-to-order   — sales_order_header_id + sales_order_line_id both set,
    --                        replenishment_request_id NULL
    --   3) stock replenish — replenishment_request_id set, sales-order cols NULL
    -- (parent_work_order_id is independent — sub-assembly children inherit the
    -- parent's origin and copy the parent's identifiers on the existing release path.)
    CHECK (
        (sales_order_header_id IS NULL AND sales_order_line_id IS NULL AND replenishment_request_id IS NULL)
        OR (sales_order_header_id IS NOT NULL AND sales_order_line_id IS NOT NULL AND replenishment_request_id IS NULL)
        OR (sales_order_header_id IS NULL AND sales_order_line_id IS NULL AND replenishment_request_id IS NOT NULL)
    )
);

CREATE INDEX idx_work_order_status ON manufacturing.work_order(status);
CREATE INDEX idx_work_order_sales_order_header_id
    ON manufacturing.work_order(sales_order_header_id) WHERE sales_order_header_id IS NOT NULL;
CREATE INDEX idx_work_order_finished_product_id
    ON manufacturing.work_order(finished_product_id);
CREATE INDEX idx_work_order_parent
    ON manufacturing.work_order(parent_work_order_id) WHERE parent_work_order_id IS NOT NULL;

-- §2.35 Slice E: lets the close-the-loop handler find the WO that fulfils
-- a given replenishment_request without scanning the whole table.
CREATE INDEX idx_work_order_replenishment_request
    ON manufacturing.work_order(replenishment_request_id) WHERE replenishment_request_id IS NOT NULL;

CREATE TRIGGER trg_work_order_updated_at
    BEFORE UPDATE ON manufacturing.work_order
    FOR EACH ROW EXECUTE FUNCTION shared.set_updated_at();

CREATE TABLE manufacturing.work_order_material (
    work_order_material_id UUID PRIMARY KEY DEFAULT shared.uuid_generate_v7(),
    work_order_id UUID NOT NULL REFERENCES manufacturing.work_order(work_order_id) ON DELETE RESTRICT,
    component_product_id UUID NOT NULL,
    component_sku VARCHAR(50) NOT NULL,
    component_name VARCHAR(200) NOT NULL,
    required_quantity NUMERIC(18, 4) NOT NULL CHECK (required_quantity >= 0),
    reserved_quantity NUMERIC(18, 4) NOT NULL DEFAULT 0,
    issued_quantity NUMERIC(18, 4) NOT NULL DEFAULT 0,
    shortage_quantity NUMERIC(18, 4) NOT NULL DEFAULT 0,
    unit_cost NUMERIC(18, 6) NOT NULL DEFAULT 0,
    total_cost NUMERIC(18, 2) NOT NULL DEFAULT 0,
    status VARCHAR(30) NOT NULL DEFAULT 'required' CHECK (
        status IN ('required', 'reserved', 'partially_reserved', 'shortage', 'issued')
    ),
    -- One row per component per work order (was missing in v1).
    UNIQUE (work_order_id, component_product_id),
    CHECK (reserved_quantity >= 0 AND reserved_quantity <= required_quantity),
    CHECK (issued_quantity >= 0 AND issued_quantity <= required_quantity)
);

CREATE INDEX idx_work_order_material_work_order_id
    ON manufacturing.work_order_material(work_order_id);
CREATE INDEX idx_work_order_material_component_id
    ON manufacturing.work_order_material(component_product_id);

-- Routing: the template sequence of operations for producing a finished
-- product. One active routing per finished_product_id, mirroring bom_header's
-- active-BOM pattern.
CREATE TABLE manufacturing.routing_header (
    routing_header_id UUID PRIMARY KEY DEFAULT shared.uuid_generate_v7(),
    finished_product_id UUID NOT NULL,
    finished_product_sku VARCHAR(50) NOT NULL,
    finished_product_name VARCHAR(200) NOT NULL,
    version VARCHAR(20) NOT NULL DEFAULT '1',
    status VARCHAR(30) NOT NULL DEFAULT 'draft' CHECK (status IN ('draft', 'active', 'inactive')),
    row_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (finished_product_id, version)
);

CREATE UNIQUE INDEX uq_routing_active_per_product
    ON manufacturing.routing_header(finished_product_id) WHERE status = 'active';

CREATE TRIGGER trg_routing_header_updated_at
    BEFORE UPDATE ON manufacturing.routing_header
    FOR EACH ROW EXECUTE FUNCTION shared.set_updated_at();

CREATE TABLE manufacturing.routing_operation (
    routing_operation_id UUID PRIMARY KEY DEFAULT shared.uuid_generate_v7(),
    routing_header_id UUID NOT NULL REFERENCES manufacturing.routing_header(routing_header_id) ON DELETE RESTRICT,
    operation_sequence INT NOT NULL,
    operation_code VARCHAR(40) NOT NULL,
    description VARCHAR(200),
    work_center_id UUID NOT NULL REFERENCES manufacturing.work_center(work_center_id),
    planned_setup_minutes NUMERIC(10, 2) NOT NULL DEFAULT 0 CHECK (planned_setup_minutes >= 0),
    planned_run_minutes NUMERIC(10, 2) NOT NULL CHECK (planned_run_minutes > 0),
    UNIQUE (routing_header_id, operation_sequence)
);

CREATE INDEX idx_routing_operation_work_center_id
    ON manufacturing.routing_operation(work_center_id);

-- Per-instance copy of the routing snapshotted at work-order release. The
-- snapshot decouples the work order from later edits to the routing template
-- (the same pattern as work_order_material wrt bom_line).
CREATE TABLE manufacturing.work_order_operation (
    work_order_operation_id UUID PRIMARY KEY DEFAULT shared.uuid_generate_v7(),
    work_order_id UUID NOT NULL REFERENCES manufacturing.work_order(work_order_id) ON DELETE RESTRICT,
    operation_sequence INT NOT NULL,
    operation_code VARCHAR(40) NOT NULL,
    description VARCHAR(200),
    work_center_id UUID NOT NULL REFERENCES manufacturing.work_center(work_center_id),
    planned_setup_minutes NUMERIC(10, 2) NOT NULL DEFAULT 0 CHECK (planned_setup_minutes >= 0),
    planned_run_minutes NUMERIC(10, 2) NOT NULL CHECK (planned_run_minutes > 0),
    actual_minutes NUMERIC(10, 2) NOT NULL DEFAULT 0 CHECK (actual_minutes >= 0),
    status VARCHAR(20) NOT NULL DEFAULT 'planned' CHECK (
        status IN ('planned', 'in_progress', 'completed', 'skipped')
    ),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    UNIQUE (work_order_id, operation_sequence)
);

CREATE INDEX idx_work_order_operation_work_order_id
    ON manufacturing.work_order_operation(work_order_id);
CREATE INDEX idx_work_order_operation_work_center_id
    ON manufacturing.work_order_operation(work_center_id);

CREATE TABLE manufacturing.production_report (
    production_report_id UUID PRIMARY KEY DEFAULT shared.uuid_generate_v7(),
    work_order_id UUID NOT NULL REFERENCES manufacturing.work_order(work_order_id),
    reported_quantity NUMERIC(18, 4) NOT NULL CHECK (reported_quantity > 0),
    scrapped_quantity NUMERIC(18, 4) NOT NULL DEFAULT 0 CHECK (scrapped_quantity >= 0),
    report_type VARCHAR(30) NOT NULL CHECK (report_type IN ('partial_completion', 'final_completion')),
    reported_by VARCHAR(100),
    reported_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    notes TEXT
);

CREATE INDEX idx_production_report_work_order_id
    ON manufacturing.production_report(work_order_id);

CREATE TABLE manufacturing.make_to_order_saga (
    saga_id UUID PRIMARY KEY DEFAULT shared.uuid_generate_v7(),
    -- Nullable since §2.37 Slice 1: make-to-stock replenishment work orders run
    -- this same WO-lifecycle saga but have no originating sales order. (Saga is
    -- still named make_to_order_saga; rename deferred — see dev-todo §2.39.)
    sales_order_header_id UUID,
    sales_order_line_id UUID,
    work_order_id UUID UNIQUE,
    saga_state VARCHAR(50) NOT NULL CHECK (
        saga_state IN (
            'started', 'work_order_created', 'bom_exploded', 'raw_material_reservation_requested',
            'raw_materials_reserved', 'raw_material_shortage', 'purchase_requisition_requested',
            'waiting_for_purchased_materials', 'production_released', 'production_started',
            'production_completed', 'finished_goods_received', 'completed', 'compensating',
            'compensated', 'failed'
        )
    ),
    current_step VARCHAR(100),
    data JSONB NOT NULL DEFAULT '{}'::jsonb,
    last_error TEXT,
    retry_count INT NOT NULL DEFAULT 0,
    next_retry_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    lease_owner VARCHAR(100),
    lease_expires_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ
);

CREATE INDEX idx_make_to_order_saga_state
    ON manufacturing.make_to_order_saga(saga_state);
CREATE INDEX idx_make_to_order_saga_sales_order_header_id
    ON manufacturing.make_to_order_saga(sales_order_header_id);
CREATE INDEX idx_make_to_order_saga_due
    ON manufacturing.make_to_order_saga(next_retry_at)
    WHERE saga_state NOT IN ('completed', 'compensated', 'failed');
CREATE INDEX idx_make_to_order_saga_data
    ON manufacturing.make_to_order_saga USING gin (data jsonb_path_ops);

CREATE TRIGGER trg_make_to_order_saga_updated_at
    BEFORE UPDATE ON manufacturing.make_to_order_saga
    FOR EACH ROW EXECUTE FUNCTION shared.set_updated_at();

-- Status history for work orders.
CREATE TABLE manufacturing.work_order_status_history (
    history_id UUID NOT NULL DEFAULT shared.uuid_generate_v7(),
    work_order_id UUID NOT NULL,
    old_status VARCHAR(40),
    new_status VARCHAR(40) NOT NULL,
    reason TEXT,
    changed_by VARCHAR(100),
    changed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (history_id, changed_at)
) PARTITION BY RANGE (changed_at);

CREATE TABLE manufacturing.work_order_status_history_default
    PARTITION OF manufacturing.work_order_status_history DEFAULT;

CREATE INDEX idx_work_order_status_history_wo
    ON manufacturing.work_order_status_history(work_order_id);


-- ----------------------------------------------------------------------------
-- MANUFACTURING: outbox / inbox
-- ----------------------------------------------------------------------------

-- manufacturing
CREATE SEQUENCE manufacturing.outbox_message_seq;
CREATE TABLE manufacturing.outbox_message (
    outbox_message_id UUID NOT NULL DEFAULT shared.uuid_generate_v7(),
    sequence_number BIGINT NOT NULL DEFAULT nextval('manufacturing.outbox_message_seq'),
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(150) NOT NULL,
    event_version INT NOT NULL DEFAULT 1,
    payload JSONB NOT NULL,
    headers JSONB NOT NULL DEFAULT '{}'::jsonb,
    correlation_id UUID,
    causation_id UUID,
    status VARCHAR(30) NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'published', 'failed')),
    retry_count INT NOT NULL DEFAULT 0,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    -- Envelope-level actor (nullable; saga-driven publishes stay null).
    actor_user_id VARCHAR(64),
    PRIMARY KEY (outbox_message_id, created_at)
) PARTITION BY RANGE (created_at);
CREATE TABLE manufacturing.outbox_message_default PARTITION OF manufacturing.outbox_message DEFAULT;
CREATE INDEX idx_manufacturing_outbox_pending ON manufacturing.outbox_message(sequence_number) WHERE status IN ('pending', 'failed');
CREATE INDEX idx_manufacturing_outbox_aggregate ON manufacturing.outbox_message(aggregate_type, aggregate_id);
CREATE INDEX idx_manufacturing_outbox_correlation ON manufacturing.outbox_message(correlation_id) WHERE correlation_id IS NOT NULL;

CREATE TABLE manufacturing.inbox_message (
    inbox_message_id UUID NOT NULL DEFAULT shared.uuid_generate_v7(),
    message_id UUID NOT NULL,
    consumer_name VARCHAR(150) NOT NULL,
    event_type VARCHAR(150) NOT NULL,
    event_version INT NOT NULL DEFAULT 1,
    source_sequence_number BIGINT,
    payload JSONB NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'processed' CHECK (status IN ('processed', 'failed')),
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (inbox_message_id, processed_at),
    UNIQUE (message_id, consumer_name, processed_at)
) PARTITION BY RANGE (processed_at);
CREATE TABLE manufacturing.inbox_message_default PARTITION OF manufacturing.inbox_message DEFAULT;


-- ----------------------------------------------------------------------------
-- MANUFACTURING: final grants (run after all DDL so newly-created tables are covered)
-- ----------------------------------------------------------------------------
GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA manufacturing TO manufacturing_service;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA manufacturing TO manufacturing_service;

-- ----------------------------------------------------------------------------
-- MANUFACTURING: seed lives in db/northwood_erp_seed.sql §SEED: MANUFACTURING.
-- ----------------------------------------------------------------------------

COMMIT;

-- ============================================================================
-- §  SERVICE: PURCHASING
-- File equivalent in multi-DB: services/purchasing/install.sql
-- ============================================================================

BEGIN;

CREATE SCHEMA IF NOT EXISTS purchasing;

-- Idempotent role creation. Roles are cluster-wide on a single PostgreSQL
-- instance, so this guard makes the script safe to re-run after DROP DATABASE.
-- After the database-per-service split, each service's install runs against
-- its own cluster (or its own database within a shared cluster) and creates
-- only its own role.
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'purchasing_service') THEN
        CREATE ROLE purchasing_service NOLOGIN;
    END IF;
END $$;

GRANT USAGE ON SCHEMA purchasing TO purchasing_service;
GRANT USAGE ON SCHEMA shared TO purchasing_service;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA shared TO purchasing_service;


CREATE TABLE purchasing.supplier (
    supplier_id UUID PRIMARY KEY DEFAULT shared.uuid_generate_v7(),
    supplier_code VARCHAR(50) NOT NULL UNIQUE,
    name VARCHAR(200) NOT NULL,
    email VARCHAR(255),
    phone VARCHAR(50),
    address TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'inactive', 'blocked')),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TRIGGER trg_supplier_updated_at
    BEFORE UPDATE ON purchasing.supplier
    FOR EACH ROW EXECUTE FUNCTION shared.set_updated_at();

-- Supplier price list — one current price per (supplier, product, currency).
-- Read-only after seed (no authoring path yet); PurchaseOrderService looks
-- up unit_price when converting a requisition to a PO.
CREATE TABLE purchasing.supplier_product_price (
    supplier_product_price_id UUID PRIMARY KEY DEFAULT shared.uuid_generate_v7(),
    supplier_id UUID NOT NULL REFERENCES purchasing.supplier(supplier_id) ON DELETE RESTRICT,
    product_id UUID NOT NULL,
    currency_code CHAR(3) NOT NULL DEFAULT 'AUD',
    unit_price NUMERIC(18, 6) NOT NULL CHECK (unit_price > 0),
    -- Effective-date range: open-ended (effective_to IS NULL) or closed.
    effective_from DATE NOT NULL DEFAULT DATE '1970-01-01',
    effective_to DATE,
    -- Tier break: 0 = base tier (every quantity); larger values are
    -- volume-discount steps (e.g. 100, 1000). Lookup picks the highest
    -- min_quantity ≤ ordered quantity.
    min_quantity NUMERIC(18, 4) NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by VARCHAR(64),
    last_modified_by VARCHAR(64),
    CONSTRAINT supplier_product_price_unique_tier
        UNIQUE (supplier_id, product_id, currency_code, effective_from, min_quantity)
);

CREATE INDEX idx_supplier_product_price_product
    ON purchasing.supplier_product_price(product_id);
CREATE INDEX idx_supplier_product_price_effective
    ON purchasing.supplier_product_price(supplier_id, product_id, currency_code, effective_from);

CREATE TRIGGER trg_supplier_product_price_updated_at
    BEFORE UPDATE ON purchasing.supplier_product_price
    FOR EACH ROW EXECUTE FUNCTION shared.set_updated_at();

-- Shape A read-side projection of product master's approved-vendor list.
-- Maintained from product.ApprovedVendorListChanged.
CREATE TABLE purchasing.product_approved_vendor (
    product_approved_vendor_id UUID PRIMARY KEY DEFAULT shared.uuid_generate_v7(),
    product_id UUID NOT NULL,
    supplier_id UUID NOT NULL,
    supplier_code VARCHAR(50),
    supplier_name VARCHAR(200),
    is_preferred BOOLEAN NOT NULL DEFAULT false,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (product_id, supplier_id)
);

CREATE INDEX idx_product_approved_vendor_product
    ON purchasing.product_approved_vendor(product_id);

-- Consumer-side denormalized card per Product (purchasing's view). Single
-- attribute today: discontinued_at (read by PurchaseRequisitionService /
-- PurchaseOrderService to reject new commitments to retired SKUs). Seeded
-- and populated on product.ProductDiscontinued. Future-proofed under the
-- _card naming convention so additional purchasing-side product facets land
-- here as additional columns rather than separate tables. See
-- docs/conventions.md → Consumer-side denormalized tables.
CREATE TABLE purchasing.product_card (
    product_id      UUID PRIMARY KEY,
    -- Nullable: future purchasing-side seed-on-Created handlers may insert
    -- the row before any ProductDiscontinued has fired. Today every row is
    -- inserted with discontinued_at populated, but DiscontinuedProductLookup
    -- filters on `discontinued_at IS NOT NULL` so the column can relax to
    -- nullable without changing read semantics.
    discontinued_at TIMESTAMPTZ
);

CREATE TABLE purchasing.purchase_requisition_header (
    purchase_requisition_header_id UUID PRIMARY KEY DEFAULT shared.uuid_generate_v7(),
    requisition_number VARCHAR(50) NOT NULL UNIQUE,
    -- Source columns: a requisition either originates manually, from low-stock
    -- (legacy/schema-prep), from a work-order shortage (legacy/historical
    -- only — §2.35 retired the producer), or from a §2.35
    -- inventory.replenishment_request (the new automatic-replenishment loop —
    -- covers both reorder-point breaches AND ex-WO-shortage triggers, which
    -- now route through inventory's ReplenishmentRequest).
    --
    -- 'work_order_shortage' is kept in the CHECK because historical rows may
    -- still reference it; new rows from Java code use 'stock_replenishment'
    -- instead (see purchasing.PurchaseRequisitionService.createForStockReplenishment).
    source_type VARCHAR(40) NOT NULL DEFAULT 'manual' CHECK (
        source_type IN ('manual', 'low_stock', 'work_order_shortage', 'stock_replenishment')
    ),
    source_product_id UUID,                      -- set when source_type = 'low_stock'
    source_work_order_id UUID,                   -- set when source_type = 'work_order_shortage' (historical)
    source_replenishment_request_id UUID,        -- §2.35: set when source_type = 'stock_replenishment'
    status VARCHAR(30) NOT NULL DEFAULT 'draft' CHECK (
        status IN ('draft', 'pending_approval', 'approved', 'rejected', 'converted', 'cancelled')
    ),
    requested_by VARCHAR(100),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    submitted_at TIMESTAMPTZ,
    approved_at TIMESTAMPTZ,
    converted_at TIMESTAMPTZ,
    created_by VARCHAR(64),
    last_modified_by VARCHAR(64),
    CHECK (
        (source_type = 'manual' AND source_product_id IS NULL AND source_work_order_id IS NULL AND source_replenishment_request_id IS NULL) OR
        (source_type = 'low_stock' AND source_product_id IS NOT NULL AND source_work_order_id IS NULL AND source_replenishment_request_id IS NULL) OR
        (source_type = 'work_order_shortage' AND source_product_id IS NULL AND source_work_order_id IS NOT NULL AND source_replenishment_request_id IS NULL) OR
        (source_type = 'stock_replenishment' AND source_product_id IS NULL AND source_work_order_id IS NULL AND source_replenishment_request_id IS NOT NULL)
    )
);

-- §2.35 Slice E lookup: lets the close-the-loop handler find the PR that
-- fulfils a given replenishment_request without scanning the whole table.
CREATE INDEX idx_purchase_requisition_header_replenishment_request
    ON purchasing.purchase_requisition_header(source_replenishment_request_id)
    WHERE source_replenishment_request_id IS NOT NULL;

CREATE TRIGGER trg_purchase_requisition_header_updated_at
    BEFORE UPDATE ON purchasing.purchase_requisition_header
    FOR EACH ROW EXECUTE FUNCTION shared.set_updated_at();

CREATE TABLE purchasing.purchase_requisition_line (
    purchase_requisition_line_id UUID PRIMARY KEY DEFAULT shared.uuid_generate_v7(),
    purchase_requisition_header_id UUID NOT NULL REFERENCES purchasing.purchase_requisition_header(purchase_requisition_header_id) ON DELETE RESTRICT,
    line_number INT NOT NULL,
    product_id UUID NOT NULL,
    product_sku VARCHAR(50) NOT NULL,
    product_name VARCHAR(200) NOT NULL,
    requested_quantity NUMERIC(18, 4) NOT NULL CHECK (requested_quantity > 0),
    required_date DATE,
    suggested_supplier_id UUID,
    suggested_supplier_name VARCHAR(200),
    status VARCHAR(30) NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'converted', 'cancelled')),
    UNIQUE (purchase_requisition_header_id, line_number)
);

CREATE INDEX idx_purchase_requisition_header_status ON purchasing.purchase_requisition_header(status);
CREATE INDEX idx_purchase_requisition_line_product_id
    ON purchasing.purchase_requisition_line(product_id);

CREATE TABLE purchasing.purchase_order_header (
    purchase_order_header_id UUID PRIMARY KEY DEFAULT shared.uuid_generate_v7(),
    purchase_order_number VARCHAR(50) NOT NULL UNIQUE,
    supplier_id UUID NOT NULL REFERENCES purchasing.supplier(supplier_id) ON DELETE RESTRICT,
    supplier_code VARCHAR(50) NOT NULL,
    supplier_name VARCHAR(200) NOT NULL,
    purchase_requisition_header_id UUID REFERENCES purchasing.purchase_requisition_header(purchase_requisition_header_id) ON DELETE RESTRICT,
    order_date DATE NOT NULL DEFAULT CURRENT_DATE,
    expected_receipt_date DATE,
    status VARCHAR(40) NOT NULL DEFAULT 'draft' CHECK (
        status IN (
            'draft', 'pending_approval', 'approved', 'sent', 'partially_received', 'received',
            'partially_invoiced', 'invoiced', 'paid', 'closed', 'cancelled'
        )
    ),
    currency_code CHAR(3) NOT NULL DEFAULT 'AUD',
    exchange_rate NUMERIC(18, 8) NOT NULL DEFAULT 1.0 CHECK (exchange_rate > 0),
    exchange_rate_captured_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    subtotal_amount NUMERIC(18, 2) NOT NULL DEFAULT 0,
    tax_amount NUMERIC(18, 2) NOT NULL DEFAULT 0,
    total_amount NUMERIC(18, 2) NOT NULL DEFAULT 0,
    received_amount NUMERIC(18, 2) NOT NULL DEFAULT 0,
    invoiced_amount NUMERIC(18, 2) NOT NULL DEFAULT 0,
    paid_amount NUMERIC(18, 2) NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    approved_at TIMESTAMPTZ,
    sent_at TIMESTAMPTZ,
    closed_at TIMESTAMPTZ,
    created_by VARCHAR(64),
    last_modified_by VARCHAR(64),
    CHECK (received_amount >= 0 AND received_amount <= total_amount),
    CHECK (invoiced_amount >= 0 AND invoiced_amount <= total_amount),
    CHECK (paid_amount >= 0 AND paid_amount <= invoiced_amount)
);

CREATE TRIGGER trg_purchase_order_header_updated_at
    BEFORE UPDATE ON purchasing.purchase_order_header
    FOR EACH ROW EXECUTE FUNCTION shared.set_updated_at();

CREATE TABLE purchasing.purchase_order_line (
    purchase_order_line_id UUID PRIMARY KEY DEFAULT shared.uuid_generate_v7(),
    purchase_order_header_id UUID NOT NULL REFERENCES purchasing.purchase_order_header(purchase_order_header_id) ON DELETE RESTRICT,
    purchase_requisition_line_id UUID,
    line_number INT NOT NULL,
    product_id UUID NOT NULL,
    product_sku VARCHAR(50) NOT NULL,
    product_name VARCHAR(200) NOT NULL,
    ordered_quantity NUMERIC(18, 4) NOT NULL CHECK (ordered_quantity > 0),
    received_quantity NUMERIC(18, 4) NOT NULL DEFAULT 0,
    invoiced_quantity NUMERIC(18, 4) NOT NULL DEFAULT 0,
    unit_price NUMERIC(18, 6) NOT NULL CHECK (unit_price >= 0),
    tax_code VARCHAR(20),
    tax_rate NUMERIC(9, 6) NOT NULL DEFAULT 0,
    tax_amount NUMERIC(18, 2) NOT NULL DEFAULT 0,
    line_total NUMERIC(18, 2) NOT NULL CHECK (line_total >= 0),
    status VARCHAR(30) NOT NULL DEFAULT 'open' CHECK (
        status IN ('open', 'partially_received', 'received', 'invoiced', 'closed', 'cancelled')
    ),
    UNIQUE (purchase_order_header_id, line_number),
    CHECK (received_quantity >= 0 AND received_quantity <= ordered_quantity),
    CHECK (invoiced_quantity >= 0 AND invoiced_quantity <= ordered_quantity)
);

CREATE INDEX idx_purchase_order_header_supplier_id ON purchasing.purchase_order_header(supplier_id);
CREATE INDEX idx_purchase_order_header_status ON purchasing.purchase_order_header(status);
CREATE INDEX idx_purchase_order_header_order_date ON purchasing.purchase_order_header(order_date);
CREATE INDEX idx_purchase_order_line_product_id ON purchasing.purchase_order_line(product_id);

CREATE TABLE purchasing.purchase_to_pay_saga (
    saga_id UUID PRIMARY KEY DEFAULT shared.uuid_generate_v7(),
    purchase_order_header_id UUID NOT NULL UNIQUE,
    saga_state VARCHAR(50) NOT NULL CHECK (
        saga_state IN (
            'started', 'purchase_order_approved', 'waiting_for_goods', 'goods_received',
            'supplier_invoice_approved', 'supplier_partially_paid', 'completed', 'failed'
        )
    ),
    current_step VARCHAR(100),
    data JSONB NOT NULL DEFAULT '{}'::jsonb,
    last_error TEXT,
    retry_count INT NOT NULL DEFAULT 0,
    next_retry_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    lease_owner VARCHAR(100),
    lease_expires_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ
);

CREATE INDEX idx_purchase_to_pay_saga_state ON purchasing.purchase_to_pay_saga(saga_state);
CREATE INDEX idx_purchase_to_pay_saga_due
    ON purchasing.purchase_to_pay_saga(next_retry_at)
    WHERE saga_state NOT IN ('completed', 'failed');
CREATE INDEX idx_purchase_to_pay_saga_data
    ON purchasing.purchase_to_pay_saga USING gin (data jsonb_path_ops);

CREATE TRIGGER trg_purchase_to_pay_saga_updated_at
    BEFORE UPDATE ON purchasing.purchase_to_pay_saga
    FOR EACH ROW EXECUTE FUNCTION shared.set_updated_at();

-- Status history for purchase orders.
CREATE TABLE purchasing.purchase_order_status_history (
    history_id UUID NOT NULL DEFAULT shared.uuid_generate_v7(),
    purchase_order_header_id UUID NOT NULL,
    old_status VARCHAR(40),
    new_status VARCHAR(40) NOT NULL,
    reason TEXT,
    changed_by VARCHAR(100),
    changed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (history_id, changed_at)
) PARTITION BY RANGE (changed_at);

CREATE TABLE purchasing.purchase_order_status_history_default
    PARTITION OF purchasing.purchase_order_status_history DEFAULT;

CREATE INDEX idx_po_status_history_po
    ON purchasing.purchase_order_status_history(purchase_order_header_id);


-- ----------------------------------------------------------------------------
-- PURCHASING: outbox / inbox
-- ----------------------------------------------------------------------------

-- purchasing
CREATE SEQUENCE purchasing.outbox_message_seq;
CREATE TABLE purchasing.outbox_message (
    outbox_message_id UUID NOT NULL DEFAULT shared.uuid_generate_v7(),
    sequence_number BIGINT NOT NULL DEFAULT nextval('purchasing.outbox_message_seq'),
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(150) NOT NULL,
    event_version INT NOT NULL DEFAULT 1,
    payload JSONB NOT NULL,
    headers JSONB NOT NULL DEFAULT '{}'::jsonb,
    correlation_id UUID,
    causation_id UUID,
    status VARCHAR(30) NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'published', 'failed')),
    retry_count INT NOT NULL DEFAULT 0,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    -- Envelope-level actor (nullable; saga-driven publishes stay null).
    actor_user_id VARCHAR(64),
    PRIMARY KEY (outbox_message_id, created_at)
) PARTITION BY RANGE (created_at);
CREATE TABLE purchasing.outbox_message_default PARTITION OF purchasing.outbox_message DEFAULT;
CREATE INDEX idx_purchasing_outbox_pending ON purchasing.outbox_message(sequence_number) WHERE status IN ('pending', 'failed');
CREATE INDEX idx_purchasing_outbox_aggregate ON purchasing.outbox_message(aggregate_type, aggregate_id);
CREATE INDEX idx_purchasing_outbox_correlation ON purchasing.outbox_message(correlation_id) WHERE correlation_id IS NOT NULL;

CREATE TABLE purchasing.inbox_message (
    inbox_message_id UUID NOT NULL DEFAULT shared.uuid_generate_v7(),
    message_id UUID NOT NULL,
    consumer_name VARCHAR(150) NOT NULL,
    event_type VARCHAR(150) NOT NULL,
    event_version INT NOT NULL DEFAULT 1,
    source_sequence_number BIGINT,
    payload JSONB NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'processed' CHECK (status IN ('processed', 'failed')),
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (inbox_message_id, processed_at),
    UNIQUE (message_id, consumer_name, processed_at)
) PARTITION BY RANGE (processed_at);
CREATE TABLE purchasing.inbox_message_default PARTITION OF purchasing.inbox_message DEFAULT;


-- ----------------------------------------------------------------------------
-- PURCHASING: final grants (run after all DDL so newly-created tables are covered)
-- ----------------------------------------------------------------------------
GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA purchasing TO purchasing_service;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA purchasing TO purchasing_service;

-- ----------------------------------------------------------------------------
-- PURCHASING: seed lives in db/northwood_erp_seed.sql §SEED: PURCHASING.
-- ----------------------------------------------------------------------------

COMMIT;

-- ============================================================================
-- §  SERVICE: FINANCE
-- File equivalent in multi-DB: services/finance/install.sql
-- ============================================================================

BEGIN;

CREATE SCHEMA IF NOT EXISTS finance;

-- Idempotent role creation. Roles are cluster-wide on a single PostgreSQL
-- instance, so this guard makes the script safe to re-run after DROP DATABASE.
-- After the database-per-service split, each service's install runs against
-- its own cluster (or its own database within a shared cluster) and creates
-- only its own role.
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'finance_service') THEN
        CREATE ROLE finance_service NOLOGIN;
    END IF;
END $$;

GRANT USAGE ON SCHEMA finance TO finance_service;
GRANT USAGE ON SCHEMA shared TO finance_service;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA shared TO finance_service;


-- Consolidated finance-side denormalized card per Product (see
-- docs/conventions.md → Consumer-side denormalized tables). All
-- attribute columns are 1:1 with Product and share Product's lifecycle:
-- seeded on product.ProductCreated (stub row with all attributes NULL),
-- populated by attribute-change events (product.StandardCostChanged,
-- product.ValuationClassChanged), stamped with discontinued_at on
-- product.ProductDiscontinued. Replaces the previous narrow tables
-- finance.product_standard_cost and finance.product_valuation_class.
--
-- Read paths:
--   * JournalEntryService.inventoryAccountForProduct /
--     cogsAccountForProduct branch on valuation_class.
--   * ShipmentPostedCogsHandler reads standard_cost when posting COGS,
--     trusting finance's authoritative number over whatever the warehouse
--     clerk typed onto the shipment line.
CREATE TABLE finance.product_card (
    product_id      UUID PRIMARY KEY,
    standard_cost   NUMERIC(18, 6),
    currency_code   CHAR(3),
    -- Wire-format values mirror product.domain.ValuationClass.dbValue()
    -- (product-events) and the matching CHECK on product.product.valuation_class.
    valuation_class VARCHAR(50) CHECK (
        valuation_class IS NULL
        OR valuation_class IN ('raw_materials', 'finished_goods', 'semi_finished_goods')
    ),
    discontinued_at TIMESTAMPTZ,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TRIGGER trg_product_card_updated_at
    BEFORE UPDATE ON finance.product_card
    FOR EACH ROW EXECUTE FUNCTION shared.set_updated_at();

-- purchasing.PurchaseOrderCreated + inventory.GoodsReceived. One row per
-- purchase-order line. Used by 3-way match: invoice quantity must not exceed
-- received_quantity, invoice unit_price must match unit_price (within
-- tolerance — phase 4 skips the price check while PO unit_price is still
-- hardcoded to 0). invoiced_quantity tracks cumulative invoiced amount so
-- repeat invoices can detect over-invoicing.
CREATE TABLE finance.purchase_order_line_facts (
    purchase_order_line_id UUID PRIMARY KEY,
    purchase_order_header_id UUID NOT NULL,
    supplier_id UUID,
    supplier_name VARCHAR(200),
    currency_code CHAR(3) NOT NULL DEFAULT 'AUD',
    product_id UUID NOT NULL,
    product_sku VARCHAR(50) NOT NULL,
    product_name VARCHAR(200) NOT NULL,
    ordered_quantity NUMERIC(18, 4) NOT NULL,
    unit_price NUMERIC(18, 6) NOT NULL DEFAULT 0,
    received_quantity NUMERIC(18, 4) NOT NULL DEFAULT 0,
    invoiced_quantity NUMERIC(18, 4) NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_po_line_facts_po_header_id
    ON finance.purchase_order_line_facts(purchase_order_header_id);

CREATE TRIGGER trg_purchase_order_line_facts_updated_at
    BEFORE UPDATE ON finance.purchase_order_line_facts
    FOR EACH ROW EXECUTE FUNCTION shared.set_updated_at();

CREATE TABLE finance.gl_account (
    gl_account_id UUID PRIMARY KEY DEFAULT shared.uuid_generate_v7(),
    account_code VARCHAR(50) NOT NULL UNIQUE,
    account_name VARCHAR(200) NOT NULL,
    account_type VARCHAR(40) NOT NULL CHECK (
        account_type IN ('asset', 'liability', 'equity', 'revenue', 'expense')
    ),
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE finance.tax_code (
    tax_code VARCHAR(20) PRIMARY KEY,
    description VARCHAR(200) NOT NULL,
    rate NUMERIC(9, 6) NOT NULL CHECK (rate >= 0),
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE finance.customer_invoice_header (
    customer_invoice_header_id UUID PRIMARY KEY DEFAULT shared.uuid_generate_v7(),
    invoice_number VARCHAR(50) NOT NULL UNIQUE,
    sales_order_header_id UUID NOT NULL,
    customer_id UUID NOT NULL,
    customer_code VARCHAR(50),
    customer_name VARCHAR(200) NOT NULL,
    invoice_date DATE NOT NULL DEFAULT CURRENT_DATE,
    due_date DATE,
    status VARCHAR(30) NOT NULL DEFAULT 'draft' CHECK (
        status IN ('draft', 'posted', 'partially_paid', 'paid', 'cancelled')
    ),
    -- §2.31 Slice B: discriminator between the two AR commercial patterns.
    -- 'commercial' = invoice created at shipment, posts Dr AR / Cr Revenue at
    -- creation, payment posts Dr Cash / Cr AR (Northwood's existing flow).
    -- 'prepayment' = invoice created at order placement, NO GL at creation,
    -- payment posts Dr Cash / Cr 2110 Customer Deposits; shipment reclassifies
    -- Dr 2110 / Cr Revenue (Treatment A — revenue recognised at shipment, the
    -- goods-delivered performance obligation). Default keeps legacy rows on
    -- the commercial path.
    invoice_type VARCHAR(20) NOT NULL DEFAULT 'commercial' CHECK (
        invoice_type IN ('commercial', 'prepayment')
    ),
    currency_code CHAR(3) NOT NULL DEFAULT 'AUD',
    exchange_rate NUMERIC(18, 8) NOT NULL DEFAULT 1.0 CHECK (exchange_rate > 0),
    exchange_rate_captured_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    subtotal_amount NUMERIC(18, 2) NOT NULL DEFAULT 0,
    tax_amount NUMERIC(18, 2) NOT NULL DEFAULT 0,
    total_amount NUMERIC(18, 2) NOT NULL DEFAULT 0,
    -- paid_amount maintained by trigger from finance.payment_allocation.
    paid_amount NUMERIC(18, 2) NOT NULL DEFAULT 0,
    outstanding_amount NUMERIC(18, 2) GENERATED ALWAYS AS (total_amount - paid_amount) STORED,
    -- Flipped true by inbox handler on sales.CustomerDeactivated; read by the
    -- future AR-collections UI to surface outstanding invoices for retired
    -- customers (§1F.3).
    flagged_for_collections BOOLEAN NOT NULL DEFAULT false,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    posted_at TIMESTAMPTZ,
    -- §2.31 Slice C: stamped when the deferred-revenue Dr 2110 / Cr Revenue
    -- pair is posted at shipment (prepayment invoices only). Null means
    -- revenue is still deferred (commercial invoice; or prepayment invoice
    -- whose shipment hasn't posted yet). Non-null gates the redelivery of
    -- ShipmentPosted at finance — the journal pair is posted at most once.
    revenue_recognized_at TIMESTAMPTZ,
    created_by VARCHAR(64),
    last_modified_by VARCHAR(64),
    CHECK (paid_amount >= 0 AND paid_amount <= total_amount)
);

CREATE TRIGGER trg_customer_invoice_header_updated_at
    BEFORE UPDATE ON finance.customer_invoice_header
    FOR EACH ROW EXECUTE FUNCTION shared.set_updated_at();

CREATE TABLE finance.customer_invoice_line (
    customer_invoice_line_id UUID PRIMARY KEY DEFAULT shared.uuid_generate_v7(),
    customer_invoice_header_id UUID NOT NULL REFERENCES finance.customer_invoice_header(customer_invoice_header_id) ON DELETE RESTRICT,
    line_number INT NOT NULL,
    sales_order_line_id UUID,
    product_id UUID,
    product_sku VARCHAR(50),
    product_name VARCHAR(200),
    quantity NUMERIC(18, 4) NOT NULL CHECK (quantity > 0),
    unit_price NUMERIC(18, 6) NOT NULL CHECK (unit_price >= 0),
    tax_code VARCHAR(20) REFERENCES finance.tax_code(tax_code),
    tax_rate NUMERIC(9, 6) NOT NULL DEFAULT 0,
    tax_amount NUMERIC(18, 2) NOT NULL DEFAULT 0,
    line_total NUMERIC(18, 2) NOT NULL CHECK (line_total >= 0),
    UNIQUE (customer_invoice_header_id, line_number)
);

CREATE INDEX idx_customer_invoice_header_sales_order_header_id ON finance.customer_invoice_header(sales_order_header_id);
CREATE INDEX idx_customer_invoice_header_customer_id ON finance.customer_invoice_header(customer_id);
CREATE INDEX idx_customer_invoice_header_status ON finance.customer_invoice_header(status);

CREATE TABLE finance.supplier_invoice_header (
    supplier_invoice_header_id UUID PRIMARY KEY DEFAULT shared.uuid_generate_v7(),
    supplier_invoice_number VARCHAR(50) NOT NULL,
    internal_invoice_number VARCHAR(50) NOT NULL UNIQUE,
    purchase_order_header_id UUID NOT NULL,
    goods_receipt_header_id UUID,
    supplier_id UUID NOT NULL,
    supplier_code VARCHAR(50),
    supplier_name VARCHAR(200) NOT NULL,
    invoice_date DATE NOT NULL DEFAULT CURRENT_DATE,
    due_date DATE,
    status VARCHAR(40) NOT NULL DEFAULT 'draft' CHECK (
        status IN (
            'draft', 'three_way_match_pending', 'three_way_match_passed', 'three_way_match_failed',
            'approved', 'posted', 'partially_paid', 'paid', 'on_hold', 'cancelled'
        )
    ),
    match_status VARCHAR(40) NOT NULL DEFAULT 'not_matched' CHECK (
        match_status IN ('not_matched', 'matched', 'variance', 'failed')
    ),
    currency_code CHAR(3) NOT NULL DEFAULT 'AUD',
    exchange_rate NUMERIC(18, 8) NOT NULL DEFAULT 1.0 CHECK (exchange_rate > 0),
    exchange_rate_captured_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    subtotal_amount NUMERIC(18, 2) NOT NULL DEFAULT 0,
    tax_amount NUMERIC(18, 2) NOT NULL DEFAULT 0,
    total_amount NUMERIC(18, 2) NOT NULL DEFAULT 0,
    paid_amount NUMERIC(18, 2) NOT NULL DEFAULT 0,
    outstanding_amount NUMERIC(18, 2) GENERATED ALWAYS AS (total_amount - paid_amount) STORED,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    approved_at TIMESTAMPTZ,
    posted_at TIMESTAMPTZ,
    created_by VARCHAR(64),
    last_modified_by VARCHAR(64),
    CHECK (paid_amount >= 0 AND paid_amount <= total_amount)
);

CREATE TRIGGER trg_supplier_invoice_header_updated_at
    BEFORE UPDATE ON finance.supplier_invoice_header
    FOR EACH ROW EXECUTE FUNCTION shared.set_updated_at();

CREATE TABLE finance.supplier_invoice_line (
    supplier_invoice_line_id UUID PRIMARY KEY DEFAULT shared.uuid_generate_v7(),
    supplier_invoice_header_id UUID NOT NULL REFERENCES finance.supplier_invoice_header(supplier_invoice_header_id) ON DELETE RESTRICT,
    line_number INT NOT NULL,
    purchase_order_line_id UUID,
    goods_receipt_line_id UUID,
    product_id UUID,
    product_sku VARCHAR(50),
    product_name VARCHAR(200),
    quantity NUMERIC(18, 4) NOT NULL CHECK (quantity > 0),
    unit_price NUMERIC(18, 6) NOT NULL CHECK (unit_price >= 0),
    tax_code VARCHAR(20) REFERENCES finance.tax_code(tax_code),
    tax_rate NUMERIC(9, 6) NOT NULL DEFAULT 0,
    tax_amount NUMERIC(18, 2) NOT NULL DEFAULT 0,
    line_total NUMERIC(18, 2) NOT NULL CHECK (line_total >= 0),
    UNIQUE (supplier_invoice_header_id, line_number)
);

CREATE INDEX idx_supplier_invoice_header_purchase_order_header_id ON finance.supplier_invoice_header(purchase_order_header_id);
CREATE INDEX idx_supplier_invoice_header_supplier_id ON finance.supplier_invoice_header(supplier_id);
CREATE INDEX idx_supplier_invoice_header_status ON finance.supplier_invoice_header(status);

-- Payments: NO single source_invoice_id. Allocations live in their own table
-- so a single payment can settle many invoices, and on-account / unallocated
-- amounts are representable.
CREATE TABLE finance.payment (
    payment_id UUID PRIMARY KEY DEFAULT shared.uuid_generate_v7(),
    payment_number VARCHAR(50) NOT NULL UNIQUE,
    payment_direction VARCHAR(20) NOT NULL CHECK (payment_direction IN ('incoming', 'outgoing')),
    payment_type VARCHAR(40) NOT NULL CHECK (payment_type IN ('customer_payment', 'supplier_payment')),
    -- Two nullable references; exactly one must be set, by check.
    customer_id UUID,
    -- supplier_id is intentionally NOT a foreign key. In v2 it referenced
    -- purchasing.supplier, but that's a cross-schema FK and after the
    -- database-per-service split it becomes physically impossible. Supplier
    -- identity is published by purchasing as events; finance keeps its own
    -- projection and ensures consistency at write time, not via the database.
    supplier_id UUID,
    party_name VARCHAR(200) NOT NULL,
    payment_date DATE NOT NULL DEFAULT CURRENT_DATE,
    payment_method VARCHAR(30) NOT NULL CHECK (
        payment_method IN ('bank_transfer', 'cash', 'card', 'cheque')
    ),
    currency_code CHAR(3) NOT NULL DEFAULT 'AUD',
    exchange_rate NUMERIC(18, 8) NOT NULL DEFAULT 1.0 CHECK (exchange_rate > 0),
    exchange_rate_captured_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    amount NUMERIC(18, 2) NOT NULL CHECK (amount > 0),
    -- amount_allocated = SUM(payment_allocation.allocated_amount) for this payment.
    -- Maintained by trigger; constraint ensures we never over-allocate.
    amount_allocated NUMERIC(18, 2) NOT NULL DEFAULT 0,
    status VARCHAR(30) NOT NULL DEFAULT 'draft' CHECK (
        status IN ('draft', 'posted', 'cancelled', 'reversed')
    ),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    posted_at TIMESTAMPTZ,
    created_by VARCHAR(64),
    last_modified_by VARCHAR(64),
    CHECK (amount_allocated >= 0 AND amount_allocated <= amount),
    CHECK (
        (payment_type = 'customer_payment' AND customer_id IS NOT NULL AND supplier_id IS NULL) OR
        (payment_type = 'supplier_payment' AND supplier_id IS NOT NULL AND customer_id IS NULL)
    )
);

CREATE INDEX idx_payment_customer_id
    ON finance.payment(customer_id) WHERE customer_id IS NOT NULL;
CREATE INDEX idx_payment_supplier_id
    ON finance.payment(supplier_id) WHERE supplier_id IS NOT NULL;
CREATE INDEX idx_payment_payment_date ON finance.payment(payment_date);

CREATE TRIGGER trg_payment_updated_at
    BEFORE UPDATE ON finance.payment
    FOR EACH ROW EXECUTE FUNCTION shared.set_updated_at();

-- Payment allocations: one row per (payment, invoice) settlement line.
-- Two nullable invoice columns rather than polymorphic source_invoice_id.
CREATE TABLE finance.payment_allocation (
    allocation_id UUID PRIMARY KEY DEFAULT shared.uuid_generate_v7(),
    payment_id UUID NOT NULL REFERENCES finance.payment(payment_id) ON DELETE RESTRICT,
    customer_invoice_header_id UUID REFERENCES finance.customer_invoice_header(customer_invoice_header_id) ON DELETE RESTRICT,
    supplier_invoice_header_id UUID REFERENCES finance.supplier_invoice_header(supplier_invoice_header_id) ON DELETE RESTRICT,
    allocated_amount NUMERIC(18, 2) NOT NULL CHECK (allocated_amount > 0),
    allocated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    status VARCHAR(20) NOT NULL DEFAULT 'posted' CHECK (status IN ('posted', 'reversed')),
    CHECK (
        (customer_invoice_header_id IS NOT NULL)::int + (supplier_invoice_header_id IS NOT NULL)::int = 1
    )
);

CREATE INDEX idx_payment_allocation_payment ON finance.payment_allocation(payment_id);
CREATE INDEX idx_payment_allocation_customer_invoice
    ON finance.payment_allocation(customer_invoice_header_id) WHERE customer_invoice_header_id IS NOT NULL;
CREATE INDEX idx_payment_allocation_supplier_invoice
    ON finance.payment_allocation(supplier_invoice_header_id) WHERE supplier_invoice_header_id IS NOT NULL;

-- Trigger: maintain payment.amount_allocated and the relevant invoice's paid_amount.
CREATE OR REPLACE FUNCTION finance.maintain_allocation_totals()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    delta NUMERIC(18, 2);
BEGIN
    IF TG_OP = 'INSERT' THEN
        -- Only posted allocations contribute to totals.
        IF NEW.status = 'posted' THEN
            UPDATE finance.payment
            SET amount_allocated = amount_allocated + NEW.allocated_amount
            WHERE payment_id = NEW.payment_id;

            IF NEW.customer_invoice_header_id IS NOT NULL THEN
                UPDATE finance.customer_invoice_header
                SET paid_amount = paid_amount + NEW.allocated_amount,
                    status = CASE
                        WHEN paid_amount + NEW.allocated_amount >= total_amount THEN 'paid'
                        WHEN paid_amount + NEW.allocated_amount > 0 THEN 'partially_paid'
                        ELSE status
                    END
                WHERE customer_invoice_header_id = NEW.customer_invoice_header_id;
            ELSE
                UPDATE finance.supplier_invoice_header
                SET paid_amount = paid_amount + NEW.allocated_amount,
                    status = CASE
                        WHEN paid_amount + NEW.allocated_amount >= total_amount THEN 'paid'
                        WHEN paid_amount + NEW.allocated_amount > 0 THEN 'partially_paid'
                        ELSE status
                    END
                WHERE supplier_invoice_header_id = NEW.supplier_invoice_header_id;
            END IF;
        END IF;
        RETURN NEW;

    ELSIF TG_OP = 'UPDATE' THEN
        -- Only the posted -> reversed flip is a meaningful update for totals.
        IF OLD.status = 'posted' AND NEW.status = 'reversed' THEN
            delta := -OLD.allocated_amount;
        ELSIF OLD.status = 'reversed' AND NEW.status = 'posted' THEN
            delta := OLD.allocated_amount;
        ELSE
            delta := 0;
        END IF;

        IF delta <> 0 THEN
            UPDATE finance.payment
            SET amount_allocated = amount_allocated + delta
            WHERE payment_id = NEW.payment_id;

            IF NEW.customer_invoice_header_id IS NOT NULL THEN
                UPDATE finance.customer_invoice_header
                SET paid_amount = paid_amount + delta
                WHERE customer_invoice_header_id = NEW.customer_invoice_header_id;
            ELSE
                UPDATE finance.supplier_invoice_header
                SET paid_amount = paid_amount + delta
                WHERE supplier_invoice_header_id = NEW.supplier_invoice_header_id;
            END IF;
        END IF;
        RETURN NEW;

    ELSIF TG_OP = 'DELETE' THEN
        -- Defensive: deletes shouldn't happen on posted allocations, but mirror reversal.
        IF OLD.status = 'posted' THEN
            UPDATE finance.payment
            SET amount_allocated = amount_allocated - OLD.allocated_amount
            WHERE payment_id = OLD.payment_id;

            IF OLD.customer_invoice_header_id IS NOT NULL THEN
                UPDATE finance.customer_invoice_header
                SET paid_amount = paid_amount - OLD.allocated_amount
                WHERE customer_invoice_header_id = OLD.customer_invoice_header_id;
            ELSE
                UPDATE finance.supplier_invoice_header
                SET paid_amount = paid_amount - OLD.allocated_amount
                WHERE supplier_invoice_header_id = OLD.supplier_invoice_header_id;
            END IF;
        END IF;
        RETURN OLD;
    END IF;
    RETURN NULL;
END;
$$;

CREATE TRIGGER trg_payment_allocation_totals
    AFTER INSERT OR UPDATE OR DELETE ON finance.payment_allocation
    FOR EACH ROW EXECUTE FUNCTION finance.maintain_allocation_totals();

-- Journal entries: deferred-trigger enforcement of debit/credit balance.
CREATE TABLE finance.journal_entry_header (
    journal_entry_header_id UUID PRIMARY KEY DEFAULT shared.uuid_generate_v7(),
    journal_number VARCHAR(50) NOT NULL UNIQUE,
    posting_date DATE NOT NULL DEFAULT CURRENT_DATE,
    source_module VARCHAR(40) NOT NULL CHECK (
        source_module IN ('sales', 'inventory', 'manufacturing', 'purchasing', 'finance')
    ),
    source_document_type VARCHAR(50) NOT NULL,
    source_document_id UUID NOT NULL,
    description TEXT,
    status VARCHAR(30) NOT NULL DEFAULT 'draft' CHECK (status IN ('draft', 'posted', 'reversed')),
    -- For multi-currency: total_debit_base/total_credit_base in base currency,
    -- maintained for invariant checking. The line-level balance check uses base.
    currency_code CHAR(3) NOT NULL DEFAULT 'AUD',
    exchange_rate NUMERIC(18, 8) NOT NULL DEFAULT 1.0 CHECK (exchange_rate > 0),
    exchange_rate_captured_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    posted_at TIMESTAMPTZ,
    reversed_at TIMESTAMPTZ,
    created_by VARCHAR(64),
    last_modified_by VARCHAR(64)
);

CREATE TRIGGER trg_journal_entry_header_updated_at
    BEFORE UPDATE ON finance.journal_entry_header
    FOR EACH ROW EXECUTE FUNCTION shared.set_updated_at();

-- Lines partitioned by posting_date for archival; PK includes posting_date.
CREATE TABLE finance.journal_entry_line (
    journal_entry_line_id UUID NOT NULL DEFAULT shared.uuid_generate_v7(),
    journal_entry_header_id UUID NOT NULL,
    line_number INT NOT NULL,
    gl_account_id UUID NOT NULL,
    account_code VARCHAR(50) NOT NULL,
    account_name VARCHAR(200) NOT NULL,
    debit_amount NUMERIC(18, 2) NOT NULL DEFAULT 0 CHECK (debit_amount >= 0),
    credit_amount NUMERIC(18, 2) NOT NULL DEFAULT 0 CHECK (credit_amount >= 0),
    description TEXT,
    posting_date DATE NOT NULL,
    PRIMARY KEY (journal_entry_line_id, posting_date),
    UNIQUE (journal_entry_header_id, line_number, posting_date),
    CHECK (debit_amount > 0 OR credit_amount > 0),
    CHECK (NOT (debit_amount > 0 AND credit_amount > 0))
) PARTITION BY RANGE (posting_date);

CREATE TABLE finance.journal_entry_line_default
    PARTITION OF finance.journal_entry_line DEFAULT;

CREATE INDEX idx_journal_entry_line_journal_entry
    ON finance.journal_entry_line(journal_entry_header_id);
CREATE INDEX idx_journal_entry_line_gl_account_id
    ON finance.journal_entry_line(gl_account_id);

CREATE INDEX idx_journal_entry_header_source
    ON finance.journal_entry_header(source_module, source_document_type, source_document_id);
CREATE INDEX idx_journal_entry_header_posting_date
    ON finance.journal_entry_header(posting_date);

-- Deferred constraint trigger: when a journal entry is posted, debits must equal credits.
CREATE OR REPLACE FUNCTION finance.enforce_journal_balance()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    total_debit NUMERIC(18, 2);
    total_credit NUMERIC(18, 2);
    je_status VARCHAR(30);
BEGIN
    SELECT status INTO je_status
    FROM finance.journal_entry_header
    WHERE journal_entry_header_id = COALESCE(NEW.journal_entry_header_id, OLD.journal_entry_header_id);

    -- Only enforce when the parent journal is being posted.
    IF je_status IN ('posted', 'reversed') THEN
        SELECT COALESCE(SUM(debit_amount), 0), COALESCE(SUM(credit_amount), 0)
        INTO total_debit, total_credit
        FROM finance.journal_entry_line
        WHERE journal_entry_header_id = COALESCE(NEW.journal_entry_header_id, OLD.journal_entry_header_id);

        IF total_debit <> total_credit THEN
            RAISE EXCEPTION
                'Journal entry % is unbalanced: debit=% credit=%',
                COALESCE(NEW.journal_entry_header_id, OLD.journal_entry_header_id), total_debit, total_credit
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;

    RETURN COALESCE(NEW, OLD);
END;
$$;

-- Constraint triggers fire at end of statement (deferrable).
CREATE CONSTRAINT TRIGGER trg_journal_line_balance
    AFTER INSERT OR UPDATE OR DELETE ON finance.journal_entry_line
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION finance.enforce_journal_balance();

-- Also re-check when the journal itself is posted (lines may already be in place).
CREATE OR REPLACE FUNCTION finance.enforce_journal_balance_on_post()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    total_debit NUMERIC(18, 2);
    total_credit NUMERIC(18, 2);
BEGIN
    IF NEW.status IN ('posted', 'reversed') AND
       (OLD.status IS DISTINCT FROM NEW.status) THEN
        SELECT COALESCE(SUM(debit_amount), 0), COALESCE(SUM(credit_amount), 0)
        INTO total_debit, total_credit
        FROM finance.journal_entry_line
        WHERE journal_entry_header_id = NEW.journal_entry_header_id;

        IF total_debit <> total_credit THEN
            RAISE EXCEPTION
                'Journal entry % cannot be posted: debit=% credit=%',
                NEW.journal_entry_header_id, total_debit, total_credit
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_journal_entry_header_balance_on_post
    BEFORE UPDATE ON finance.journal_entry_header
    FOR EACH ROW EXECUTE FUNCTION finance.enforce_journal_balance_on_post();

-- Posted-document immutability. Once a journal/invoice/payment row is posted,
-- only specific status transitions are allowed.
CREATE OR REPLACE FUNCTION finance.guard_journal_immutability()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status = 'posted' THEN
        IF NEW.status NOT IN ('posted', 'reversed') THEN
            RAISE EXCEPTION 'Posted journal entries are immutable; only reversal is allowed';
        END IF;
        -- Allow ONLY status / reversed_at / version / updated_at to change.
        -- Compare the full immutable subset as a tuple; IS DISTINCT FROM is null-safe.
        IF (NEW.journal_number, NEW.posting_date, NEW.source_module,
            NEW.source_document_type, NEW.source_document_id, NEW.description,
            NEW.currency_code, NEW.exchange_rate, NEW.exchange_rate_captured_at,
            NEW.created_at, NEW.posted_at)
           IS DISTINCT FROM
           (OLD.journal_number, OLD.posting_date, OLD.source_module,
            OLD.source_document_type, OLD.source_document_id, OLD.description,
            OLD.currency_code, OLD.exchange_rate, OLD.exchange_rate_captured_at,
            OLD.created_at, OLD.posted_at)
        THEN
            RAISE EXCEPTION 'Posted journal entries cannot have non-status fields modified';
        END IF;
    ELSIF OLD.status = 'reversed' THEN
        RAISE EXCEPTION 'Reversed journal entries are immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_journal_entry_header_immutability
    BEFORE UPDATE ON finance.journal_entry_header
    FOR EACH ROW EXECUTE FUNCTION finance.guard_journal_immutability();

-- Likewise prevent modifying lines on a posted journal.
CREATE OR REPLACE FUNCTION finance.guard_journal_line_immutability()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    parent_status VARCHAR(30);
BEGIN
    SELECT status INTO parent_status
    FROM finance.journal_entry_header
    WHERE journal_entry_header_id = COALESCE(NEW.journal_entry_header_id, OLD.journal_entry_header_id);

    IF parent_status IN ('posted', 'reversed') AND TG_OP <> 'INSERT' THEN
        -- Allow inserts only during initial draft creation; once parent is posted,
        -- no DML on lines.
        RAISE EXCEPTION 'Cannot modify journal lines once the journal is posted';
    ELSIF parent_status IN ('posted', 'reversed') AND TG_OP = 'INSERT' THEN
        RAISE EXCEPTION 'Cannot insert journal lines for a posted/reversed journal';
    END IF;
    RETURN COALESCE(NEW, OLD);
END;
$$;

CREATE TRIGGER trg_journal_entry_line_immutability
    BEFORE INSERT OR UPDATE OR DELETE ON finance.journal_entry_line
    FOR EACH ROW EXECUTE FUNCTION finance.guard_journal_line_immutability();

-- Same pattern for posted customer/supplier invoices and payments.
-- Customer invoices: once posted, the only legitimate changes are status
-- transitions (posted -> partially_paid -> paid, or posted -> cancelled),
-- paid_amount maintained by the allocation trigger, version, and updated_at.
-- Everything else is locked. We use IS DISTINCT FROM on a tuple of all
-- immutable columns rather than enumerating <>-checks per column, so adding
-- a new immutable column to the table doesn't silently bypass the guard.
CREATE OR REPLACE FUNCTION finance.guard_customer_invoice_immutability()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status IN ('posted', 'partially_paid', 'paid') THEN
        IF (NEW.invoice_number, NEW.sales_order_header_id, NEW.customer_id,
            NEW.customer_code, NEW.customer_name, NEW.invoice_date, NEW.due_date,
            NEW.currency_code, NEW.exchange_rate,
            NEW.subtotal_amount, NEW.tax_amount, NEW.total_amount,
            NEW.created_at, NEW.posted_at)
           IS DISTINCT FROM
           (OLD.invoice_number, OLD.sales_order_header_id, OLD.customer_id,
            OLD.customer_code, OLD.customer_name, OLD.invoice_date, OLD.due_date,
            OLD.currency_code, OLD.exchange_rate,
            OLD.subtotal_amount, OLD.tax_amount, OLD.total_amount,
            OLD.created_at, OLD.posted_at)
        THEN
            RAISE EXCEPTION 'Posted customer invoices cannot have non-status fields modified; reverse and re-issue instead';
        END IF;
    ELSIF OLD.status = 'cancelled' THEN
        RAISE EXCEPTION 'Cancelled invoices are immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_customer_invoice_header_immutability
    BEFORE UPDATE ON finance.customer_invoice_header
    FOR EACH ROW EXECUTE FUNCTION finance.guard_customer_invoice_immutability();

-- Supplier invoices: same pattern as customer, but with the supplier-side
-- columns and the three-way-match audit fields locked once posted.
-- match_status is settled at approval time and must not drift afterwards.
CREATE OR REPLACE FUNCTION finance.guard_supplier_invoice_immutability()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status IN ('posted', 'partially_paid', 'paid') THEN
        IF (NEW.supplier_invoice_number, NEW.internal_invoice_number,
            NEW.purchase_order_header_id, NEW.goods_receipt_header_id,
            NEW.supplier_id, NEW.supplier_code, NEW.supplier_name,
            NEW.invoice_date, NEW.due_date, NEW.match_status,
            NEW.currency_code, NEW.exchange_rate,
            NEW.subtotal_amount, NEW.tax_amount, NEW.total_amount,
            NEW.created_at, NEW.approved_at, NEW.posted_at)
           IS DISTINCT FROM
           (OLD.supplier_invoice_number, OLD.internal_invoice_number,
            OLD.purchase_order_header_id, OLD.goods_receipt_header_id,
            OLD.supplier_id, OLD.supplier_code, OLD.supplier_name,
            OLD.invoice_date, OLD.due_date, OLD.match_status,
            OLD.currency_code, OLD.exchange_rate,
            OLD.subtotal_amount, OLD.tax_amount, OLD.total_amount,
            OLD.created_at, OLD.approved_at, OLD.posted_at)
        THEN
            RAISE EXCEPTION 'Posted supplier invoices cannot have non-status fields modified; reverse and re-issue instead';
        END IF;
    ELSIF OLD.status = 'cancelled' THEN
        RAISE EXCEPTION 'Cancelled invoices are immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_supplier_invoice_header_immutability
    BEFORE UPDATE ON finance.supplier_invoice_header
    FOR EACH ROW EXECUTE FUNCTION finance.guard_supplier_invoice_immutability();

-- Payments: only status (posted -> reversed) and amount_allocated (maintained
-- by the allocation trigger) and version/updated_at may change after posting.
-- Note: we deliberately DO list amount_allocated as mutable by NOT including
-- it in the comparison tuple — its movement is the allocation trigger's job.
CREATE OR REPLACE FUNCTION finance.guard_payment_immutability()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status = 'posted' THEN
        IF NEW.status NOT IN ('posted', 'reversed') THEN
            RAISE EXCEPTION 'Posted payments can only be reversed, not edited';
        END IF;
        IF (NEW.payment_number, NEW.payment_direction, NEW.payment_type,
            NEW.customer_id, NEW.supplier_id, NEW.party_name,
            NEW.payment_date, NEW.payment_method,
            NEW.currency_code, NEW.exchange_rate, NEW.amount,
            NEW.created_at, NEW.posted_at)
           IS DISTINCT FROM
           (OLD.payment_number, OLD.payment_direction, OLD.payment_type,
            OLD.customer_id, OLD.supplier_id, OLD.party_name,
            OLD.payment_date, OLD.payment_method,
            OLD.currency_code, OLD.exchange_rate, OLD.amount,
            OLD.created_at, OLD.posted_at)
        THEN
            RAISE EXCEPTION 'Posted payments cannot have non-status fields modified';
        END IF;
    ELSIF OLD.status IN ('cancelled', 'reversed') THEN
        RAISE EXCEPTION 'Cancelled/reversed payments are immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_payment_immutability
    BEFORE UPDATE ON finance.payment
    FOR EACH ROW EXECUTE FUNCTION finance.guard_payment_immutability();

-- Status histories.
CREATE TABLE finance.invoice_status_history (
    history_id UUID NOT NULL DEFAULT shared.uuid_generate_v7(),
    invoice_kind VARCHAR(20) NOT NULL CHECK (invoice_kind IN ('customer', 'supplier')),
    invoice_id UUID NOT NULL,
    old_status VARCHAR(40),
    new_status VARCHAR(40) NOT NULL,
    reason TEXT,
    changed_by VARCHAR(100),
    changed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (history_id, changed_at)
) PARTITION BY RANGE (changed_at);

CREATE TABLE finance.invoice_status_history_default
    PARTITION OF finance.invoice_status_history DEFAULT;

CREATE INDEX idx_invoice_status_history_invoice
    ON finance.invoice_status_history(invoice_kind, invoice_id);

CREATE TABLE finance.payment_status_history (
    history_id UUID NOT NULL DEFAULT shared.uuid_generate_v7(),
    payment_id UUID NOT NULL,
    old_status VARCHAR(30),
    new_status VARCHAR(30) NOT NULL,
    reason TEXT,
    changed_by VARCHAR(100),
    changed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (history_id, changed_at)
) PARTITION BY RANGE (changed_at);

CREATE TABLE finance.payment_status_history_default
    PARTITION OF finance.payment_status_history DEFAULT;

CREATE INDEX idx_payment_status_history_payment
    ON finance.payment_status_history(payment_id);


-- ----------------------------------------------------------------------------
-- FINANCE: exchange rates
-- ----------------------------------------------------------------------------
-- Data of record for currency conversion. Every transaction header that
-- carries an exchange_rate snapshot stamps an exchange_rate_captured_at
-- alongside it; the snapshot is auditable against this table for the
-- corresponding effective_date. The Money value object in shared-kernel is
-- deliberately amount + currency only — conversion is a service concern that
-- looks up rates here.
CREATE TABLE finance.exchange_rate (
    exchange_rate_id UUID PRIMARY KEY DEFAULT shared.uuid_generate_v7(),
    from_currency CHAR(3) NOT NULL,
    to_currency CHAR(3) NOT NULL,
    effective_date DATE NOT NULL,
    rate NUMERIC(18, 8) NOT NULL CHECK (rate > 0),
    source VARCHAR(50),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (from_currency, to_currency, effective_date),
    CHECK (from_currency <> to_currency)
);

CREATE INDEX idx_exchange_rate_lookup
    ON finance.exchange_rate(from_currency, to_currency, effective_date DESC);


-- ----------------------------------------------------------------------------
-- FINANCE: outbox / inbox
-- ----------------------------------------------------------------------------

-- finance
CREATE SEQUENCE finance.outbox_message_seq;
CREATE TABLE finance.outbox_message (
    outbox_message_id UUID NOT NULL DEFAULT shared.uuid_generate_v7(),
    sequence_number BIGINT NOT NULL DEFAULT nextval('finance.outbox_message_seq'),
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(150) NOT NULL,
    event_version INT NOT NULL DEFAULT 1,
    payload JSONB NOT NULL,
    headers JSONB NOT NULL DEFAULT '{}'::jsonb,
    correlation_id UUID,
    causation_id UUID,
    status VARCHAR(30) NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'published', 'failed')),
    retry_count INT NOT NULL DEFAULT 0,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    -- Envelope-level actor (nullable; saga-driven publishes stay null).
    actor_user_id VARCHAR(64),
    PRIMARY KEY (outbox_message_id, created_at)
) PARTITION BY RANGE (created_at);
CREATE TABLE finance.outbox_message_default PARTITION OF finance.outbox_message DEFAULT;
CREATE INDEX idx_finance_outbox_pending ON finance.outbox_message(sequence_number) WHERE status IN ('pending', 'failed');
CREATE INDEX idx_finance_outbox_aggregate ON finance.outbox_message(aggregate_type, aggregate_id);
CREATE INDEX idx_finance_outbox_correlation ON finance.outbox_message(correlation_id) WHERE correlation_id IS NOT NULL;

CREATE TABLE finance.inbox_message (
    inbox_message_id UUID NOT NULL DEFAULT shared.uuid_generate_v7(),
    message_id UUID NOT NULL,
    consumer_name VARCHAR(150) NOT NULL,
    event_type VARCHAR(150) NOT NULL,
    event_version INT NOT NULL DEFAULT 1,
    source_sequence_number BIGINT,
    payload JSONB NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'processed' CHECK (status IN ('processed', 'failed')),
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (inbox_message_id, processed_at),
    UNIQUE (message_id, consumer_name, processed_at)
) PARTITION BY RANGE (processed_at);
CREATE TABLE finance.inbox_message_default PARTITION OF finance.inbox_message DEFAULT;


-- ----------------------------------------------------------------------------
-- FINANCE: final grants (run after all DDL so newly-created tables are covered)
-- ----------------------------------------------------------------------------
GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA finance TO finance_service;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA finance TO finance_service;

-- ----------------------------------------------------------------------------
-- FINANCE: seed lives in db/northwood_erp_seed.sql §SEED: FINANCE.
-- ----------------------------------------------------------------------------

COMMIT;

-- ============================================================================
-- §  SERVICE: REPORTING
-- File equivalent in multi-DB: services/reporting/install.sql
-- ============================================================================

BEGIN;

CREATE SCHEMA IF NOT EXISTS reporting;

-- Idempotent role creation. Roles are cluster-wide on a single PostgreSQL
-- instance, so this guard makes the script safe to re-run after DROP DATABASE.
-- After the database-per-service split, each service's install runs against
-- its own cluster (or its own database within a shared cluster) and creates
-- only its own role.
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'reporting_service') THEN
        CREATE ROLE reporting_service NOLOGIN;
    END IF;
END $$;

GRANT USAGE ON SCHEMA reporting TO reporting_service;
GRANT USAGE ON SCHEMA shared TO reporting_service;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA shared TO reporting_service;


CREATE TABLE reporting.sales_order_360_view (
    sales_order_header_id UUID PRIMARY KEY,
    order_number VARCHAR(50) NOT NULL,
    customer_id UUID NOT NULL,
    customer_name VARCHAR(200) NOT NULL,
    order_date DATE NOT NULL,
    requested_delivery_date DATE,
    -- Mirrors of cross-cutting status; this is the read-side, source-of-truth
    -- for "where is this order?" UI without polluting the command model.
    order_status VARCHAR(40) NOT NULL,
    stock_status VARCHAR(40) NOT NULL,
    manufacturing_status VARCHAR(40) NOT NULL,
    shipment_status VARCHAR(40) NOT NULL,
    invoice_status VARCHAR(40) NOT NULL,
    payment_status VARCHAR(40) NOT NULL,
    -- §2.31 Slice A: commercial terms surfaced on the SO-360 view; projected
    -- from sales.SalesOrderPlaced.paymentTerms. UI uses this to render a
    -- "Prepayment" lozenge (and later, an "awaiting prepayment" state).
    payment_terms VARCHAR(20) NOT NULL DEFAULT 'on_shipment',
    currency_code CHAR(3) NOT NULL DEFAULT 'AUD',
    total_amount NUMERIC(18, 2) NOT NULL DEFAULT 0,
    invoiced_amount NUMERIC(18, 2) NOT NULL DEFAULT 0,
    paid_amount NUMERIC(18, 2) NOT NULL DEFAULT 0,
    outstanding_amount NUMERIC(18, 2) NOT NULL DEFAULT 0,
    has_shortage BOOLEAN NOT NULL DEFAULT false,
    shortage_summary TEXT,
    last_event_type VARCHAR(100),
    last_event_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Projected from upstream aggregate's last_modified_by stamp.
    last_modified_by VARCHAR(64)
);

CREATE INDEX idx_sales_order_360_customer_id ON reporting.sales_order_360_view(customer_id);
CREATE INDEX idx_sales_order_360_status ON reporting.sales_order_360_view(order_status);

CREATE TABLE reporting.available_to_promise_view (
    product_id UUID PRIMARY KEY,
    product_sku VARCHAR(50) NOT NULL,
    product_name VARCHAR(200) NOT NULL,
    on_hand_quantity NUMERIC(18, 4) NOT NULL DEFAULT 0,
    reserved_for_sales NUMERIC(18, 4) NOT NULL DEFAULT 0,
    reserved_for_production NUMERIC(18, 4) NOT NULL DEFAULT 0,
    available_quantity NUMERIC(18, 4) NOT NULL DEFAULT 0,
    incoming_from_production NUMERIC(18, 4) NOT NULL DEFAULT 0,
    incoming_from_purchase NUMERIC(18, 4) NOT NULL DEFAULT 0,
    earliest_available_date DATE,
    stock_status VARCHAR(40) NOT NULL DEFAULT 'unknown' CHECK (
        stock_status IN ('unknown', 'available', 'low_stock', 'out_of_stock', 'incoming')
    ),
    -- Stamped from product.ProductDiscontinued; UI consumers filter / grey out
    -- on IS NOT NULL (§1F.1).
    discontinued_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_available_to_promise_sku ON reporting.available_to_promise_view(product_sku);
CREATE INDEX idx_available_to_promise_stock_status
    ON reporting.available_to_promise_view(stock_status);

CREATE TABLE reporting.production_planning_board (
    work_order_id UUID PRIMARY KEY,
    work_order_number VARCHAR(50) NOT NULL,
    sales_order_header_id UUID,
    order_number VARCHAR(50),
    finished_product_id UUID NOT NULL,
    finished_product_sku VARCHAR(50) NOT NULL,
    finished_product_name VARCHAR(200) NOT NULL,
    planned_quantity NUMERIC(18, 4) NOT NULL,
    completed_quantity NUMERIC(18, 4) NOT NULL DEFAULT 0,
    work_order_status VARCHAR(40) NOT NULL,
    material_status VARCHAR(40) NOT NULL,
    shortage_materials_count INT NOT NULL DEFAULT 0,
    shortage_summary TEXT,
    open_purchase_orders_count INT NOT NULL DEFAULT 0,
    expected_material_available_date DATE,
    planned_start_date DATE,
    planned_end_date DATE,
    priority VARCHAR(20) NOT NULL DEFAULT 'normal' CHECK (priority IN ('low', 'normal', 'high', 'urgent')),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_production_planning_board_status
    ON reporting.production_planning_board(work_order_status);
CREATE INDEX idx_production_planning_board_material_status
    ON reporting.production_planning_board(material_status);

CREATE TABLE reporting.material_shortage_view (
    -- material_product_id is the natural key; no surrogate.
    material_product_id UUID PRIMARY KEY,
    material_sku VARCHAR(50) NOT NULL,
    material_name VARCHAR(200) NOT NULL,
    required_quantity NUMERIC(18, 4) NOT NULL DEFAULT 0,
    available_quantity NUMERIC(18, 4) NOT NULL DEFAULT 0,
    shortage_quantity NUMERIC(18, 4) NOT NULL DEFAULT 0,
    affected_work_orders_count INT NOT NULL DEFAULT 0,
    affected_sales_orders_count INT NOT NULL DEFAULT 0,
    open_purchase_orders_count INT NOT NULL DEFAULT 0,
    incoming_purchase_quantity NUMERIC(18, 4) NOT NULL DEFAULT 0,
    expected_receipt_date DATE,
    status VARCHAR(40) NOT NULL DEFAULT 'open' CHECK (
        status IN ('open', 'purchase_requested', 'purchase_ordered', 'resolved')
    ),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_material_shortage_view_status ON reporting.material_shortage_view(status);

-- Per-customer status projection so dashboard widgets stop counting deactivated
-- customers as active. Updates land from sales.CustomerDeactivated inbox
-- handler (§1F.3); a future CustomerRegistered consumer will seed 'active' rows.
CREATE TABLE reporting.customer_dashboard_status (
    customer_id     UUID PRIMARY KEY,
    status          VARCHAR(20) NOT NULL CHECK (status IN ('active', 'inactive')),
    deactivated_at  TIMESTAMPTZ,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE reporting.purchase_order_tracking_view (
    purchase_order_header_id UUID PRIMARY KEY,
    purchase_order_number VARCHAR(50) NOT NULL,
    supplier_id UUID NOT NULL,
    supplier_name VARCHAR(200) NOT NULL,
    po_status VARCHAR(40) NOT NULL,
    order_date DATE NOT NULL,
    expected_receipt_date DATE,
    currency_code CHAR(3) NOT NULL DEFAULT 'AUD',
    ordered_amount NUMERIC(18, 2) NOT NULL DEFAULT 0,
    received_amount NUMERIC(18, 2) NOT NULL DEFAULT 0,
    invoiced_amount NUMERIC(18, 2) NOT NULL DEFAULT 0,
    paid_amount NUMERIC(18, 2) NOT NULL DEFAULT 0,
    outstanding_amount NUMERIC(18, 2) NOT NULL DEFAULT 0,
    receipt_status VARCHAR(40) NOT NULL DEFAULT 'not_received',
    invoice_status VARCHAR(40) NOT NULL DEFAULT 'not_invoiced',
    payment_status VARCHAR(40) NOT NULL DEFAULT 'unpaid',
    match_status VARCHAR(40) NOT NULL DEFAULT 'not_matched',
    -- Stamped from purchasing.PurchaseOrderApproved; captures the wall-clock
    -- moment po_status flipped to 'sent' (§1F.4).
    approved_at TIMESTAMPTZ,
    last_goods_receipt_header_id UUID,
    last_supplier_invoice_header_id UUID,
    last_payment_id UUID,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Projected from upstream aggregate's last_modified_by stamp.
    last_modified_by VARCHAR(64),
    -- Non-null when source PR.source_type = 'work_order_shortage'; lets the
    -- production-planning board compute open_purchase_orders_count per WO.
    source_work_order_id UUID
);

CREATE INDEX idx_po_tracking_supplier_id ON reporting.purchase_order_tracking_view(supplier_id);
CREATE INDEX idx_po_tracking_status ON reporting.purchase_order_tracking_view(po_status);

-- §2.35 Slice F: replenishment history view. One row per ReplenishmentRequest,
-- driven by the four §2.35 events:
--   inventory.ReplenishmentRequested            → INSERT row in 'requested'
--   manufacturing.ReplenishmentDispatched       → UPDATE status → 'dispatched',
--                                                 dispatched_aggregate_kind='work_order'
--   purchasing.ReplenishmentDispatched          → UPDATE status → 'dispatched',
--                                                 dispatched_aggregate_kind='purchase_requisition'
--   inventory.ReplenishmentFulfilled            → UPDATE status → 'fulfilled'
--
-- Product SKU/name are joined at query time from
-- reporting.available_to_promise_view (which carries them keyed by product_id).
-- Warehouse code is denormalised at request time from… actually warehouse
-- identity isn't projected into reporting today; the controller exposes
-- warehouse_id only. UI can resolve via the existing
-- inventory.warehouse endpoint if it needs to display the code.
-- No CHECK constraints on enumerated columns: this is a downstream
-- projection that must tolerate out-of-order arrival with placeholder
-- values ('(pending)' / zero quantities) until the request event catches
-- up. The producer (inventory.replenishment_request) already enforces the
-- wire-format invariants. Same pattern as other reporting views.
CREATE TABLE reporting.replenishment_history_view (
    replenishment_request_id    UUID PRIMARY KEY,
    product_id                  UUID NOT NULL DEFAULT '00000000-0000-0000-0000-000000000000',
    warehouse_id                UUID NOT NULL DEFAULT '00000000-0000-0000-0000-000000000000',
    requested_quantity          NUMERIC(18, 4) NOT NULL DEFAULT 0,
    target_service              VARCHAR(20) NOT NULL DEFAULT '(pending)',
    reason                      VARCHAR(40) NOT NULL DEFAULT '(pending)',
    status                      VARCHAR(20) NOT NULL DEFAULT 'requested',
    dispatched_aggregate_kind   VARCHAR(30),
    dispatched_aggregate_id     UUID,
    requested_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    dispatched_at               TIMESTAMPTZ,
    fulfilled_at                TIMESTAMPTZ,
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_replenishment_history_product
    ON reporting.replenishment_history_view(product_id);
CREATE INDEX idx_replenishment_history_status
    ON reporting.replenishment_history_view(status);

CREATE TRIGGER trg_replenishment_history_view_updated_at
    BEFORE UPDATE ON reporting.replenishment_history_view
    FOR EACH ROW EXECUTE FUNCTION shared.set_updated_at();

-- Financial dashboard now keyed by (date, currency_code) to support multi-currency.
CREATE TABLE reporting.financial_dashboard_daily (
    dashboard_date DATE NOT NULL,
    currency_code CHAR(3) NOT NULL DEFAULT 'AUD',
    sales_revenue NUMERIC(18, 2) NOT NULL DEFAULT 0,
    cost_of_goods_sold NUMERIC(18, 2) NOT NULL DEFAULT 0,
    gross_profit NUMERIC(18, 2) NOT NULL DEFAULT 0,
    inventory_value NUMERIC(18, 2) NOT NULL DEFAULT 0,
    wip_value NUMERIC(18, 2) NOT NULL DEFAULT 0,
    accounts_receivable NUMERIC(18, 2) NOT NULL DEFAULT 0,
    accounts_payable NUMERIC(18, 2) NOT NULL DEFAULT 0,
    cash_received NUMERIC(18, 2) NOT NULL DEFAULT 0,
    cash_paid NUMERIC(18, 2) NOT NULL DEFAULT 0,
    open_sales_orders_count INT NOT NULL DEFAULT 0,
    open_purchase_orders_count INT NOT NULL DEFAULT 0,
    open_work_orders_count INT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (dashboard_date, currency_code)
);

CREATE TABLE reporting.projection_checkpoint (
    projection_name VARCHAR(100) PRIMARY KEY,
    -- Outbox cursor: BIGINT sequence_number, NOT created_at, to avoid the
    -- transaction-commit-ordering vs row-write-ordering trap.
    last_sequence_number BIGINT,
    last_event_type VARCHAR(100),
    last_processed_at TIMESTAMPTZ,
    checkpoint_data JSONB NOT NULL DEFAULT '{}'::jsonb,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Reporting-side denormalized card per Product (see docs/conventions.md →
-- Consumer-side denormalized tables). Single attribute today (standard_cost +
-- currency_code) joined with available_to_promise_view at snapshot time to
-- compute inventory_value. Fed by product.StandardCostChanged inbox handler;
-- mirrors the standard_cost column on finance.product_card (reporting
-- maintains its own copy because per-service search_path forbids
-- cross-schema reads).
CREATE TABLE reporting.product_card (
    product_id UUID PRIMARY KEY,
    standard_cost NUMERIC(18, 6) NOT NULL,
    currency_code CHAR(3) NOT NULL DEFAULT 'AUD',
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TRIGGER trg_reporting_product_card_updated_at
    BEFORE UPDATE ON reporting.product_card
    FOR EACH ROW EXECUTE FUNCTION shared.set_updated_at();


-- ----------------------------------------------------------------------------
-- REPORTING: outbox / inbox
-- ----------------------------------------------------------------------------

-- reporting (inbox only — read models consume, they don't publish).
CREATE TABLE reporting.inbox_message (
    inbox_message_id UUID NOT NULL DEFAULT shared.uuid_generate_v7(),
    message_id UUID NOT NULL,
    consumer_name VARCHAR(150) NOT NULL,
    event_type VARCHAR(150) NOT NULL,
    event_version INT NOT NULL DEFAULT 1,
    source_sequence_number BIGINT,
    payload JSONB NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'processed' CHECK (status IN ('processed', 'failed')),
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (inbox_message_id, processed_at),
    UNIQUE (message_id, consumer_name, processed_at)
) PARTITION BY RANGE (processed_at);
CREATE TABLE reporting.inbox_message_default PARTITION OF reporting.inbox_message DEFAULT;


-- ----------------------------------------------------------------------------
-- REPORTING: final grants (run after all DDL so newly-created tables are covered)
-- ----------------------------------------------------------------------------
GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA reporting TO reporting_service;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA reporting TO reporting_service;

-- ----------------------------------------------------------------------------
-- REPORTING: seed lives in db/northwood_erp_seed.sql §SEED: REPORTING.
-- Read models are otherwise populated by projection consumers draining
-- events from the bus into reporting.inbox_message and applying them to
-- the read tables. The product_card cache is seeded so that day-1
-- inventory_value computation works ahead of the first StandardCostChanged
-- event; subsequent events update individual rows.
-- ----------------------------------------------------------------------------

COMMIT;

-- ============================================================================
-- Installation complete.
-- ============================================================================
