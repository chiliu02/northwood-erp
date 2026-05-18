--liquibase formatted sql

--changeset northwood:2026-05-14-add-product-discontinued-projection
--comment: §1F.1 — purchasing-side projection of ProductDiscontinued. PurchaseRequisitionService rejects lines whose product appears here.
CREATE TABLE IF NOT EXISTS purchasing.product_card (
    product_id      UUID PRIMARY KEY,
    discontinued_at TIMESTAMPTZ
);
--rollback DROP TABLE IF EXISTS purchasing.product_card;
