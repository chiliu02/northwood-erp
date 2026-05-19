--liquibase formatted sql

-- §2.0.f — finance-side CHECK on finance.product_card.valuation_class matching
-- the new product.domain.ValuationClass enum (product-events). Legacy dev DBs
-- booted from the previous northwood_erp.sql seeded SA rows with the typo
-- 'semi_finished_good' (singular, copy-pasted from product_type) instead of
-- the plural 'semi_finished_goods' the JournalEntryService policy switches
-- expect. The first changeset normalises legacy rows before the second
-- changeset adds the CHECK. Split into two changesets because Liquibase
-- formatted-SQL splits on ';' even inside PL/pgSQL DO blocks.

--changeset northwood:2026-05-19-normalise-valuation-class-singular-to-plural
UPDATE finance.product_card
   SET valuation_class = 'semi_finished_goods'
 WHERE valuation_class = 'semi_finished_good';
--rollback UPDATE finance.product_card SET valuation_class = 'semi_finished_good' WHERE valuation_class = 'semi_finished_goods';

--changeset northwood:2026-05-19-product-card-valuation-class-check
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.table_constraints
         WHERE table_schema = 'finance'
           AND table_name = 'product_card'
           AND constraint_name = 'product_card_valuation_class_check'
    ) THEN
        ALTER TABLE finance.product_card
            ADD CONSTRAINT product_card_valuation_class_check
            CHECK (valuation_class IS NULL
                   OR valuation_class IN ('raw_materials', 'finished_goods', 'semi_finished_goods'));
    END IF;
END $$;
--rollback ALTER TABLE finance.product_card DROP CONSTRAINT IF EXISTS product_card_valuation_class_check;
