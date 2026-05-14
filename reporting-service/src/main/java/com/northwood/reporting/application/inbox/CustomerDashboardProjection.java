package com.northwood.reporting.application.inbox;

import java.time.Instant;
import java.util.UUID;

/**
 * Maintains {@code reporting.customer_dashboard_status} — one row per
 * customer-with-known-status. §1F.3 seeds rows from
 * {@code sales.CustomerDeactivated} (status = {@code 'inactive'},
 * {@code deactivated_at} stamped). A future §1F.4 {@code CustomerRegistered}
 * consumer will seed {@code 'active'} rows on registration.
 *
 * <p>Per-customer rather than a counter accumulator so the projection is
 * replay-safe (an idempotent upsert per event) and so future UI features
 * (list deactivated customers, filter on status) work without extra joins.
 * Dashboard widgets compute "deactivated count" as
 * {@code SELECT COUNT(*) WHERE status = 'inactive'} against this table.
 *
 * <p>Application-side port; JDBC implementation lives in
 * {@code infrastructure/persistence/JdbcCustomerDashboardProjection}.
 */
public interface CustomerDashboardProjection {

    void recordCustomerDeactivated(UUID customerId, Instant deactivatedAt);
}
