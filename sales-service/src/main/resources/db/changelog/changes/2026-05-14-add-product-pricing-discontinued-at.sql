--liquibase formatted sql

--changeset northwood:2026-05-14-add-product-pricing-discontinued-at
--comment: §1F.1 — stamp ProductDiscontinued onto sales.product_pricing so placeOrder can reject discontinued products via ProductPricingLookup
ALTER TABLE sales.product_pricing
    ADD COLUMN IF NOT EXISTS discontinued_at TIMESTAMPTZ NULL;
--rollback ALTER TABLE sales.product_pricing DROP COLUMN IF EXISTS discontinued_at;
