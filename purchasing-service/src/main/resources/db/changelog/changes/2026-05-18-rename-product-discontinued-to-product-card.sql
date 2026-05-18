--liquibase formatted sql

--changeset northwood:2026-05-18-rename-product-discontinued-to-product-card
--comment: §2.23.2 — purchasing.product_discontinued → purchasing.product_card per the cardinality-based projection-table convention (docs/conventions.md → Consumer-side denormalized tables). Idempotent against a fresh baseline-provisioned DB (where the table already lives as product_card). The 2026-05-14 changeset has been retargeted to create the new name directly; this rename only fires for legacy dev DBs that ran the original 2026-05-14 against an older baseline.
ALTER TABLE IF EXISTS purchasing.product_discontinued RENAME TO product_card;
--rollback ALTER TABLE IF EXISTS purchasing.product_card RENAME TO product_discontinued;
