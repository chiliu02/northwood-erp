--liquibase formatted sql

--changeset northwood:2026-05-15-product-pricing-nullable-price
--comment: Sales product_card is now seeded on ProductCreated (stub with NULL price+currency); SalesPriceChanged fills both in. Relaxes NOT NULL on sales_price / currency_code and drops the AUD default so the stub-row's NULLs read as "not yet priced".
ALTER TABLE sales.product_card ALTER COLUMN sales_price DROP NOT NULL;
ALTER TABLE sales.product_card ALTER COLUMN currency_code DROP NOT NULL;
ALTER TABLE sales.product_card ALTER COLUMN currency_code DROP DEFAULT;
--rollback ALTER TABLE sales.product_card ALTER COLUMN sales_price SET NOT NULL;
--rollback ALTER TABLE sales.product_card ALTER COLUMN currency_code SET NOT NULL;
--rollback ALTER TABLE sales.product_card ALTER COLUMN currency_code SET DEFAULT 'AUD';
