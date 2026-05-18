--liquibase formatted sql

--changeset northwood:2026-05-18-rename-product-accounting-to-product-card splitStatements:false
--comment: §2.23.3 — finance.product_accounting → finance.product_card per the cardinality-based projection-table convention (docs/conventions.md → Consumer-side denormalized tables). Idempotent against a fresh baseline-provisioned DB (where the table already lives as product_card via the retargeted 2026-05-15 changesets). This rename only fires for legacy dev DBs that ran the original 2026-05-15-consolidate-product-accounting against an older baseline.
ALTER TABLE IF EXISTS finance.product_accounting RENAME TO product_card;
DO $$
BEGIN
    BEGIN
        ALTER TRIGGER trg_product_accounting_updated_at ON finance.product_card RENAME TO trg_product_card_updated_at;
    EXCEPTION
        WHEN undefined_object OR undefined_table THEN NULL;
    END;
END $$;
--rollback ALTER TABLE IF EXISTS finance.product_card RENAME TO product_accounting;
--rollback DO $$ BEGIN BEGIN ALTER TRIGGER trg_product_card_updated_at ON finance.product_accounting RENAME TO trg_product_accounting_updated_at; EXCEPTION WHEN undefined_object OR undefined_table THEN NULL; END; END $$;
