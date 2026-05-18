--liquibase formatted sql

--changeset northwood:2026-05-18-rename-product-standard-cost-captured-at splitStatements:false
--comment: reporting.product_standard_cost.captured_at → updated_at. Two reasons. (1) Aligns with the dominant projection-table convention (matches finance.product_card.updated_at, sales.product_card.updated_at, etc.). (2) The table already has trigger trg_reporting_product_standard_cost_updated_at attached, which calls shared.set_updated_at() — that function writes NEW.updated_at, so the trigger was silently broken whenever the projection's ON CONFLICT DO UPDATE path fired (column "updated_at" did not exist). The rename makes the trigger functional and removes the need for the projection's SQL to set captured_at = now() explicitly.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'reporting'
          AND table_name = 'product_standard_cost'
          AND column_name = 'captured_at'
    ) THEN
        ALTER TABLE reporting.product_standard_cost RENAME COLUMN captured_at TO updated_at;
    END IF;
END $$;
--rollback DO $$ BEGIN IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'reporting' AND table_name = 'product_standard_cost' AND column_name = 'updated_at') THEN ALTER TABLE reporting.product_standard_cost RENAME COLUMN updated_at TO captured_at; END IF; END $$;
