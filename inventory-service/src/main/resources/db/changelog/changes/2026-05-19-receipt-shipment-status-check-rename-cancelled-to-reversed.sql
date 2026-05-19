--liquibase formatted sql

--changeset northwood:2026-05-19-receipt-shipment-status-check-rename-cancelled-to-reversed
--comment: §2.0.b — replace `cancelled` with `reversed` in the goods_receipt_header + shipment_header status CHECK. Aligns the schema with the domain semantics: a physical receipt/shipment can be reversed (counter-stock-movement) but not cancelled after posting. No data migration — production code only ever writes 'posted'; the dropped 'cancelled' value was schema-prep that the §2.0 design discussion rejected.

ALTER TABLE inventory.goods_receipt_header DROP CONSTRAINT goods_receipt_header_status_check;
ALTER TABLE inventory.goods_receipt_header ADD CONSTRAINT goods_receipt_header_status_check
    CHECK (status IN ('draft', 'posted', 'reversed'));

ALTER TABLE inventory.shipment_header DROP CONSTRAINT shipment_header_status_check;
ALTER TABLE inventory.shipment_header ADD CONSTRAINT shipment_header_status_check
    CHECK (status IN ('draft', 'posted', 'reversed'));

--rollback ALTER TABLE inventory.goods_receipt_header DROP CONSTRAINT goods_receipt_header_status_check;
--rollback ALTER TABLE inventory.goods_receipt_header ADD CONSTRAINT goods_receipt_header_status_check CHECK (status IN ('draft', 'posted', 'cancelled'));
--rollback ALTER TABLE inventory.shipment_header DROP CONSTRAINT shipment_header_status_check;
--rollback ALTER TABLE inventory.shipment_header ADD CONSTRAINT shipment_header_status_check CHECK (status IN ('draft', 'posted', 'cancelled'));
