--liquibase formatted sql

--changeset northwood:2026-05-14-stock-item-add-discontinued-at
--comment: §1F.1 — stamp ProductDiscontinued onto inventory.stock_item so future reorder-alert logic can suppress alerts for retired SKUs.
ALTER TABLE inventory.stock_item
    ADD COLUMN IF NOT EXISTS discontinued_at TIMESTAMPTZ NULL;
--rollback ALTER TABLE inventory.stock_item DROP COLUMN IF EXISTS discontinued_at;
