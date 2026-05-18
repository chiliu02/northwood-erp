--liquibase formatted sql

--changeset northwood:2026-05-18-rename-product-pricing-to-product-card splitStatements:false
--comment: §2.23.1 — sales.product_pricing → sales.product_card per the cardinality-based projection-table convention (docs/conventions.md → Consumer-side denormalized tables). Also renames the BEFORE UPDATE trigger to keep names aligned. Idempotent against a fresh baseline-provisioned DB (which already has the new names).
ALTER TABLE IF EXISTS sales.product_pricing RENAME TO product_card;
DO $$
BEGIN
    BEGIN
        ALTER TRIGGER trg_product_pricing_updated_at ON sales.product_card RENAME TO trg_product_card_updated_at;
    EXCEPTION
        WHEN undefined_object OR undefined_table THEN NULL;
    END;
END $$;
--rollback ALTER TABLE IF EXISTS sales.product_card RENAME TO product_pricing;
--rollback DO $$ BEGIN BEGIN ALTER TRIGGER trg_product_card_updated_at ON sales.product_pricing RENAME TO trg_product_pricing_updated_at; EXCEPTION WHEN undefined_object OR undefined_table THEN NULL; END; END $$;
