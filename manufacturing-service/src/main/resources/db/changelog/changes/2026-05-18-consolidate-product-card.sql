--liquibase formatted sql

-- §2.23.5: collapse manufacturing.product_replenishment + product_active_bom
-- + product_materials_cost into manufacturing.product_card per the
-- cardinality-based projection-table convention (docs/conventions.md →
-- Consumer-side denormalized tables). Three sub-changesets: create the
-- consolidated table, migrate data from each predecessor (preconditioned so
-- they skip on fresh-volume boots where the predecessor tables never
-- existed), drop the three old tables.

--changeset northwood:2026-05-18-product-card-create splitStatements:false
CREATE TABLE IF NOT EXISTS manufacturing.product_card (
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
DO $$
BEGIN
    BEGIN
        CREATE TRIGGER trg_product_card_updated_at
            BEFORE UPDATE ON manufacturing.product_card
            FOR EACH ROW EXECUTE FUNCTION shared.set_updated_at();
    EXCEPTION
        WHEN duplicate_object THEN NULL;
    END;
END $$;
--rollback DROP TABLE IF EXISTS manufacturing.product_card;

--changeset northwood:2026-05-18-migrate-replenishment-to-product-card
--preconditions onFail:MARK_RAN onError:MARK_RAN
--precondition-sql-check expectedResult:1 SELECT count(*) FROM information_schema.tables WHERE table_schema = 'manufacturing' AND table_name = 'product_replenishment'
INSERT INTO manufacturing.product_card (product_id, is_purchased, is_manufactured, discontinued_at)
SELECT product_id, is_purchased, is_manufactured, discontinued_at FROM manufacturing.product_replenishment
ON CONFLICT (product_id) DO UPDATE SET
    is_purchased = EXCLUDED.is_purchased,
    is_manufactured = EXCLUDED.is_manufactured,
    discontinued_at = EXCLUDED.discontinued_at;
--rollback empty

--changeset northwood:2026-05-18-migrate-active-bom-to-product-card
--preconditions onFail:MARK_RAN onError:MARK_RAN
--precondition-sql-check expectedResult:1 SELECT count(*) FROM information_schema.tables WHERE table_schema = 'manufacturing' AND table_name = 'product_active_bom'
INSERT INTO manufacturing.product_card (product_id, active_bom_header_id)
SELECT product_id, active_bom_header_id FROM manufacturing.product_active_bom
ON CONFLICT (product_id) DO UPDATE SET
    active_bom_header_id = EXCLUDED.active_bom_header_id;
--rollback empty

--changeset northwood:2026-05-18-migrate-materials-cost-to-product-card
--preconditions onFail:MARK_RAN onError:MARK_RAN
--precondition-sql-check expectedResult:1 SELECT count(*) FROM information_schema.tables WHERE table_schema = 'manufacturing' AND table_name = 'product_materials_cost'
INSERT INTO manufacturing.product_card (product_id, materials_cost, currency_code, materials_cost_reason, materials_cost_captured_at)
SELECT product_id, materials_cost, currency_code, reason, captured_at FROM manufacturing.product_materials_cost
ON CONFLICT (product_id) DO UPDATE SET
    materials_cost = EXCLUDED.materials_cost,
    currency_code = EXCLUDED.currency_code,
    materials_cost_reason = EXCLUDED.materials_cost_reason,
    materials_cost_captured_at = EXCLUDED.materials_cost_captured_at;
--rollback empty

--changeset northwood:2026-05-18-drop-old-product-projections
DROP TABLE IF EXISTS manufacturing.product_replenishment;
DROP TABLE IF EXISTS manufacturing.product_active_bom;
DROP TABLE IF EXISTS manufacturing.product_materials_cost;
--rollback CREATE TABLE manufacturing.product_replenishment (product_id UUID PRIMARY KEY, is_purchased BOOLEAN NOT NULL DEFAULT false, is_manufactured BOOLEAN NOT NULL DEFAULT false, discontinued_at TIMESTAMPTZ, updated_at TIMESTAMPTZ NOT NULL DEFAULT now());
--rollback CREATE TABLE manufacturing.product_active_bom (product_id UUID PRIMARY KEY, active_bom_header_id UUID, updated_at TIMESTAMPTZ NOT NULL DEFAULT now());
--rollback CREATE TABLE manufacturing.product_materials_cost (product_id UUID PRIMARY KEY, materials_cost NUMERIC(18, 6), currency_code CHAR(3), reason VARCHAR(40) NOT NULL DEFAULT 'inputs_missing', captured_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now());
