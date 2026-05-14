--liquibase formatted sql

--changeset northwood:2026-05-14-po-tracking-add-approved-at
--comment: §1F.4 — stamp PurchaseOrderApproved onto reporting.purchase_order_tracking_view. po_status flips to 'sent', approved_at captures the wall-clock moment.
ALTER TABLE reporting.purchase_order_tracking_view
    ADD COLUMN IF NOT EXISTS approved_at TIMESTAMPTZ NULL;
--rollback ALTER TABLE reporting.purchase_order_tracking_view DROP COLUMN IF EXISTS approved_at;
