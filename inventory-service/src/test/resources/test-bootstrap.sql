-- Minimal Postgres bootstrap for the inventory-service integration test.
-- We deliberately don't replay db/northwood_erp.sql wholesale (too much
-- unrelated schema for one test); just the inventory pieces the seam
-- exercises: shared functions, inventory.warehouse + seed row,
-- inventory.stock_item, inventory.stock_balance, inventory.inbox_message.
-- The (currently empty) Liquibase master changelog runs against this on
-- test boot; future changesets must stay idempotent and reference only
-- tables that exist here — add any new prerequisites to this file too.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE SCHEMA IF NOT EXISTS shared;
CREATE SCHEMA IF NOT EXISTS inventory;

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
    uuid_bytes :=
        set_byte(set_byte(set_byte(set_byte(set_byte(set_byte(rand_bytes, 0,
            (get_byte(rand_bytes, 0) & 15) | 112),
            1, get_byte(rand_bytes, 1)),
            2, (get_byte(rand_bytes, 2) & 63) | 128),
            3, get_byte(rand_bytes, 3)),
            4, get_byte(rand_bytes, 4)),
            5, get_byte(rand_bytes, 5));
    RETURN encode(
        decode(lpad(to_hex(unix_ts_ms), 12, '0'), 'hex') || uuid_bytes,
        'hex'
    )::UUID;
END;
$$;

CREATE OR REPLACE FUNCTION shared.set_updated_at()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END;
$$;

CREATE TABLE inventory.warehouse (
    warehouse_id UUID PRIMARY KEY DEFAULT shared.uuid_generate_v7(),
    warehouse_code VARCHAR(50) NOT NULL UNIQUE,
    name VARCHAR(200) NOT NULL,
    address TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'inactive')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Seed warehouse referenced by the cabinet stock-balance fixtures (UUID
-- 00000000-0000-7000-8000-000000000020). Without this row, the cabinet INSERTs
-- would fail the warehouse_id FK.
INSERT INTO inventory.warehouse (warehouse_id, warehouse_code, name)
VALUES ('00000000-0000-7000-8000-000000000020', 'WH-MAIN', 'Main Warehouse');

CREATE TABLE inventory.stock_item (
    stock_item_id UUID PRIMARY KEY DEFAULT shared.uuid_generate_v7(),
    product_id UUID NOT NULL UNIQUE,
    product_sku VARCHAR(50) NOT NULL,
    product_name VARCHAR(200) NOT NULL,
    product_type VARCHAR(30) NOT NULL,
    base_uom_code VARCHAR(20) NOT NULL,
    stock_tracking_mode VARCHAR(20) NOT NULL DEFAULT 'tracked',
    reorder_point NUMERIC(18, 4) NOT NULL DEFAULT 0,
    reorder_quantity NUMERIC(18, 4) NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE inventory.stock_balance (
    stock_balance_id UUID PRIMARY KEY DEFAULT shared.uuid_generate_v7(),
    warehouse_id UUID NOT NULL REFERENCES inventory.warehouse(warehouse_id),
    product_id UUID NOT NULL,
    on_hand_quantity NUMERIC(18, 4) NOT NULL DEFAULT 0,
    reserved_quantity NUMERIC(18, 4) NOT NULL DEFAULT 0,
    available_quantity NUMERIC(18, 4) GENERATED ALWAYS AS (on_hand_quantity - reserved_quantity) STORED,
    average_cost NUMERIC(18, 6) NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (warehouse_id, product_id),
    CHECK (on_hand_quantity >= 0),
    CHECK (reserved_quantity >= 0),
    CHECK (on_hand_quantity >= reserved_quantity)
);

-- Outbox table is empty in this test (the seam exercises the inbox path), but
-- the @KafkaProfile-gated OutboxDrainScheduler's @Scheduled drainer fires every
-- second once Spring starts and queries this table. Without the table the
-- drainer logs an alarming "relation inventory.outbox_message does not exist"
-- stack trace each tick — test still passes because Spring's scheduler
-- swallows the exception, but the noise is misleading. The seed-empty table
-- + sequence + partition silence it.
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
    PRIMARY KEY (outbox_message_id, created_at)
) PARTITION BY RANGE (created_at);

CREATE TABLE inventory.outbox_message_default
    PARTITION OF inventory.outbox_message DEFAULT;

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

CREATE TABLE inventory.inbox_message_default
    PARTITION OF inventory.inbox_message DEFAULT;

-- Seed row for the test SKU. version=1 so the projection update goes through
-- JdbcStockItemRepository.save's UPDATE path (mirrors the dev DB seed bump).
INSERT INTO inventory.stock_item (
    product_id, product_sku, product_name, product_type, base_uom_code,
    stock_tracking_mode, reorder_point, reorder_quantity, version
) VALUES (
    '00000000-0000-7000-8000-000000000001',
    'FG-TABLE-001', 'Wooden Dining Table', 'finished_good', 'EA',
    'tracked', 2, 5, 1
);
