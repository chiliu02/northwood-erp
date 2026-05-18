--liquibase formatted sql

--changeset northwood:2026-05-18-rename-product-standard-cost-to-product-card splitStatements:false
--comment: §2.23.4 — reporting.product_standard_cost → reporting.product_card per the cardinality-based projection-table convention (docs/conventions.md → Consumer-side denormalized tables). Runs AFTER the rename-captured-at changeset above so the column-rename can complete against the legacy table name before the table itself renames. Idempotent against a fresh baseline-provisioned DB (where the table is already product_card).
ALTER TABLE IF EXISTS reporting.product_standard_cost RENAME TO product_card;
DO $$
BEGIN
    BEGIN
        ALTER TRIGGER trg_reporting_product_standard_cost_updated_at ON reporting.product_card RENAME TO trg_reporting_product_card_updated_at;
    EXCEPTION
        WHEN undefined_object OR undefined_table THEN NULL;
    END;
END $$;
--rollback ALTER TABLE IF EXISTS reporting.product_card RENAME TO product_standard_cost;
--rollback DO $$ BEGIN BEGIN ALTER TRIGGER trg_reporting_product_card_updated_at ON reporting.product_standard_cost RENAME TO trg_reporting_product_standard_cost_updated_at; EXCEPTION WHEN undefined_object OR undefined_table THEN NULL; END; END $$;
