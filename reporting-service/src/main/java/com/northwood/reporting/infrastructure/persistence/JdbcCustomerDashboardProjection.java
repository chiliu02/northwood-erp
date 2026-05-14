package com.northwood.reporting.infrastructure.persistence;

import com.northwood.reporting.application.inbox.CustomerDashboardProjection;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcCustomerDashboardProjection implements CustomerDashboardProjection {

    private static final Logger log = LoggerFactory.getLogger(JdbcCustomerDashboardProjection.class);

    private final JdbcTemplate jdbc;

    public JdbcCustomerDashboardProjection(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void recordCustomerDeactivated(UUID customerId, Instant deactivatedAt) {
        jdbc.update("""
            INSERT INTO reporting.customer_dashboard_status (customer_id, status, deactivated_at)
            VALUES (?, 'inactive', ?)
            ON CONFLICT (customer_id) DO UPDATE
                SET status = 'inactive',
                    deactivated_at = EXCLUDED.deactivated_at,
                    updated_at = now()
            """,
            customerId, Timestamp.from(deactivatedAt)
        );
        log.info("flagged reporting.customer_dashboard_status customer_id={} → inactive (at={})",
            customerId, deactivatedAt);
    }
}
