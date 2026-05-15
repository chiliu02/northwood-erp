--liquibase formatted sql

--changeset northwood:2026-05-15-trim-purchase-to-pay-saga-states
--comment: §6.B23 — trim purchase_to_pay_saga CHECK to states the Java code actually writes. Drops dead literals 'supplier_invoice_received', 'three_way_match_pending', 'three_way_match_passed', 'three_way_match_failed', 'purchase_order_closed', 'manual_review_required'. Aligns the schema with PurchaseToPaySaga.ALL_STATES.
ALTER TABLE purchasing.purchase_to_pay_saga DROP CONSTRAINT purchase_to_pay_saga_saga_state_check;
ALTER TABLE purchasing.purchase_to_pay_saga ADD CONSTRAINT purchase_to_pay_saga_saga_state_check
    CHECK (saga_state IN (
        'started', 'purchase_order_approved', 'waiting_for_goods', 'goods_received',
        'supplier_invoice_approved', 'supplier_payment_made', 'completed', 'failed'
    ));
--rollback ALTER TABLE purchasing.purchase_to_pay_saga DROP CONSTRAINT purchase_to_pay_saga_saga_state_check;
--rollback ALTER TABLE purchasing.purchase_to_pay_saga ADD CONSTRAINT purchase_to_pay_saga_saga_state_check
--rollback     CHECK (saga_state IN (
--rollback         'started', 'purchase_order_approved', 'waiting_for_goods', 'goods_received',
--rollback         'supplier_invoice_received', 'three_way_match_pending', 'three_way_match_passed',
--rollback         'three_way_match_failed', 'supplier_invoice_approved', 'supplier_payment_made',
--rollback         'purchase_order_closed', 'completed', 'manual_review_required', 'failed'
--rollback     ));
