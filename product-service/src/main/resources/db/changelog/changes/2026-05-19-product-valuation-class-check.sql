--liquibase formatted sql

--changeset northwood:2026-05-19-product-valuation-class-check
--comment: §2.0.f — add CHECK on product.product.valuation_class matching the new product.domain.ValuationClass enum (product-events). Mirrored on finance.product_card.valuation_class via the finance-service changeset of the same date. Wire-format values: 'raw_materials', 'finished_goods', 'semi_finished_goods'. Idempotent against a fresh baseline-provisioned DB (where the constraint already lives via the updated northwood_erp.sql). ALTER TABLE ... ADD CONSTRAINT IF NOT EXISTS is not portable, so a DO block guards it.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.table_constraints
         WHERE table_schema = 'product'
           AND table_name = 'product'
           AND constraint_name = 'product_valuation_class_check'
    ) THEN
        ALTER TABLE product.product
            ADD CONSTRAINT product_valuation_class_check
            CHECK (valuation_class IS NULL
                   OR valuation_class IN ('raw_materials', 'finished_goods', 'semi_finished_goods'));
    END IF;
END $$;
--rollback ALTER TABLE product.product DROP CONSTRAINT IF EXISTS product_valuation_class_check;
