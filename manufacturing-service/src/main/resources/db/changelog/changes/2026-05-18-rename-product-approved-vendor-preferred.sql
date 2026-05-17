--liquibase formatted sql

--changeset northwood:2026-05-18-rename-product-approved-vendor-preferred splitStatements:false
--comment: Align manufacturing.product_approved_vendor.preferred with the project's is_ boolean-prefix convention. product.approved_vendor and purchasing.product_approved_vendor both use is_preferred; manufacturing's copy drifted when §2.8 Slice C landed. The partial index's WHERE clause is rewritten automatically by Postgres when the column is renamed.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'manufacturing'
          AND table_name = 'product_approved_vendor'
          AND column_name = 'preferred'
    ) THEN
        ALTER TABLE manufacturing.product_approved_vendor RENAME COLUMN preferred TO is_preferred;
    END IF;
END $$;
--rollback DO $$ BEGIN IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'manufacturing' AND table_name = 'product_approved_vendor' AND column_name = 'is_preferred') THEN ALTER TABLE manufacturing.product_approved_vendor RENAME COLUMN is_preferred TO preferred; END IF; END $$;
