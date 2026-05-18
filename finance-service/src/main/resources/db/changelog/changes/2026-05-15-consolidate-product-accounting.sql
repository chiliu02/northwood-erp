--liquibase formatted sql

-- Consolidates finance.product_standard_cost + finance.product_valuation_class
-- into finance.product_card (twin of §1F.6a applied to finance). Same
-- lifecycle for both columns (seeded on ProductCreated, populated by
-- attribute-change events, marked discontinued_at on ProductDiscontinued),
-- so they belong in one row per Product. Split into four changesets because
-- Liquibase formatted-SQL splits on ';' even inside PL/pgSQL DO blocks —
-- preconditions guard the two data-migration steps so a fresh-volume dev DB
-- (where the predecessor tables were never created) marks them as ran without
-- attempting the INSERT.

--changeset northwood:2026-05-15-product-accounting-create
CREATE TABLE IF NOT EXISTS finance.product_card (
    product_id      UUID PRIMARY KEY,
    standard_cost   NUMERIC(18, 6),
    currency_code   CHAR(3),
    valuation_class VARCHAR(50),
    discontinued_at TIMESTAMPTZ,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
DROP TRIGGER IF EXISTS trg_product_card_updated_at ON finance.product_card;
CREATE TRIGGER trg_product_card_updated_at
    BEFORE UPDATE ON finance.product_card
    FOR EACH ROW EXECUTE FUNCTION shared.set_updated_at();
--rollback DROP TABLE finance.product_card;

--changeset northwood:2026-05-15-migrate-standard-cost-to-product-accounting
--preconditions onFail:MARK_RAN onError:MARK_RAN
--precondition-sql-check expectedResult:1 SELECT count(*) FROM information_schema.tables WHERE table_schema = 'finance' AND table_name = 'product_standard_cost'
INSERT INTO finance.product_card (product_id, standard_cost, currency_code)
SELECT product_id, standard_cost, currency_code FROM finance.product_standard_cost
ON CONFLICT (product_id) DO UPDATE SET
    standard_cost = EXCLUDED.standard_cost,
    currency_code = EXCLUDED.currency_code;
--rollback empty

--changeset northwood:2026-05-15-migrate-valuation-class-to-product-accounting
--preconditions onFail:MARK_RAN onError:MARK_RAN
--precondition-sql-check expectedResult:1 SELECT count(*) FROM information_schema.tables WHERE table_schema = 'finance' AND table_name = 'product_valuation_class'
INSERT INTO finance.product_card (product_id, valuation_class)
SELECT product_id, valuation_class FROM finance.product_valuation_class
ON CONFLICT (product_id) DO UPDATE SET
    valuation_class = EXCLUDED.valuation_class;
--rollback empty

--changeset northwood:2026-05-15-drop-old-product-tables
DROP TABLE IF EXISTS finance.product_standard_cost;
DROP TABLE IF EXISTS finance.product_valuation_class;
--rollback CREATE TABLE finance.product_valuation_class (product_id UUID PRIMARY KEY, valuation_class VARCHAR(50), updated_at TIMESTAMPTZ NOT NULL DEFAULT now());
--rollback INSERT INTO finance.product_valuation_class (product_id, valuation_class) SELECT product_id, valuation_class FROM finance.product_card WHERE valuation_class IS NOT NULL;
