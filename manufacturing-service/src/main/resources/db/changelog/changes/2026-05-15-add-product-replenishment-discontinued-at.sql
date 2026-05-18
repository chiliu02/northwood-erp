--liquibase formatted sql

--changeset northwood:2026-05-15-add-product-replenishment-discontinued-at
--comment: §1.4 B.3 — manufacturing-side discontinued signal for BomEditService.addLine gating. Distinct from the (false, false) flags pair which can also mean "never classified".
ALTER TABLE IF EXISTS manufacturing.product_replenishment
    ADD COLUMN IF NOT EXISTS discontinued_at TIMESTAMPTZ;
--rollback ALTER TABLE IF EXISTS manufacturing.product_replenishment DROP COLUMN IF EXISTS discontinued_at;
