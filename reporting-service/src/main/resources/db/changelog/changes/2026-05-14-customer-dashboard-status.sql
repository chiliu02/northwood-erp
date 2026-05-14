--liquibase formatted sql

--changeset northwood:2026-05-14-customer-dashboard-status
--comment: §1F.3 — per-customer status projection so dashboard widgets stop counting deactivated customers as active. Updates land from sales.CustomerDeactivated; a future CustomerRegistered consumer will seed 'active' rows (§1F.4).
CREATE TABLE IF NOT EXISTS reporting.customer_dashboard_status (
    customer_id     UUID PRIMARY KEY,
    status          VARCHAR(20) NOT NULL CHECK (status IN ('active', 'inactive')),
    deactivated_at  TIMESTAMPTZ NULL,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
--rollback DROP TABLE IF EXISTS reporting.customer_dashboard_status;
