--liquibase formatted sql

--changeset northwood:2026-05-14-atp-add-discontinued-at
--comment: §1F.1 — stamp ProductDiscontinued onto reporting.available_to_promise_view. Adds discontinued_at; UI consumers filter / grey out on IS NOT NULL.
ALTER TABLE reporting.available_to_promise_view
    ADD COLUMN IF NOT EXISTS discontinued_at TIMESTAMPTZ NULL;
--rollback ALTER TABLE reporting.available_to_promise_view DROP COLUMN IF EXISTS discontinued_at;
