--liquibase formatted sql

--changeset northwood:2026-05-14-customer-invoice-flag-for-collections
--comment: §1F.3 — flag outstanding customer invoices for collections when the named customer is deactivated. Read by future AR-collections UI; set by inbox handler on sales.CustomerDeactivated.
ALTER TABLE finance.customer_invoice_header
    ADD COLUMN IF NOT EXISTS flagged_for_collections BOOLEAN NOT NULL DEFAULT false;
--rollback ALTER TABLE finance.customer_invoice_header DROP COLUMN IF EXISTS flagged_for_collections;
