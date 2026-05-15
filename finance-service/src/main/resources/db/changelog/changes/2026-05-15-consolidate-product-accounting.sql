--liquibase formatted sql

--changeset northwood:2026-05-15-consolidate-product-accounting
--comment: Consolidate finance.product_standard_cost + finance.product_valuation_class into finance.product_accounting. Same lifecycle for both columns (seeded on ProductCreated, populated by attribute-change events, marked discontinued_at on ProductDiscontinued), so they belong in one row per Product. Drops the two narrow tables in favour of the unified projection — the inbox handlers and lookups are retargeted in the same slice.
CREATE TABLE IF NOT EXISTS finance.product_accounting (
    product_id      UUID PRIMARY KEY,
    standard_cost   NUMERIC(18, 6),
    currency_code   CHAR(3),
    valuation_class VARCHAR(50),
    discontinued_at TIMESTAMPTZ,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

DROP TRIGGER IF EXISTS trg_product_accounting_updated_at ON finance.product_accounting;
CREATE TRIGGER trg_product_accounting_updated_at
    BEFORE UPDATE ON finance.product_accounting
    FOR EACH ROW EXECUTE FUNCTION shared.set_updated_at();

-- Migrate data from the two predecessor tables before dropping them. The
-- INSERT only runs against the columns that exist in each predecessor, so
-- the order matters: standard_cost row first (creates the row), then
-- valuation_class row (updates it). Both use ON CONFLICT for resilience.
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = 'finance' AND table_name = 'product_standard_cost') THEN
        INSERT INTO finance.product_accounting (product_id, standard_cost, currency_code)
        SELECT product_id, standard_cost, currency_code FROM finance.product_standard_cost
        ON CONFLICT (product_id) DO UPDATE SET
            standard_cost = EXCLUDED.standard_cost,
            currency_code = EXCLUDED.currency_code;
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = 'finance' AND table_name = 'product_valuation_class') THEN
        INSERT INTO finance.product_accounting (product_id, valuation_class)
        SELECT product_id, valuation_class FROM finance.product_valuation_class
        ON CONFLICT (product_id) DO UPDATE SET
            valuation_class = EXCLUDED.valuation_class;
    END IF;
END $$;

DROP TABLE IF EXISTS finance.product_standard_cost;
DROP TABLE IF EXISTS finance.product_valuation_class;
--rollback CREATE TABLE finance.product_valuation_class (product_id UUID PRIMARY KEY, valuation_class VARCHAR(50), updated_at TIMESTAMPTZ NOT NULL DEFAULT now());
--rollback INSERT INTO finance.product_valuation_class (product_id, valuation_class) SELECT product_id, valuation_class FROM finance.product_accounting WHERE valuation_class IS NOT NULL;
--rollback DROP TABLE finance.product_accounting;
