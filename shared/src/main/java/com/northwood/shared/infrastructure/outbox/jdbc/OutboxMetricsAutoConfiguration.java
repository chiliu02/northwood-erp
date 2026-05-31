package com.northwood.shared.infrastructure.outbox.jdbc;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * §1D.5: registers a {@code northwood.outbox.pending{service="..."}} gauge so
 * the showcase Grafana dashboard's "bus health" row can plot per-service
 * outbox backpressure. The gauge runs a {@code SELECT COUNT(*)} on every
 * Prometheus scrape (~15s, see {@code db/prometheus/prometheus.yml}). Cheap
 * indexed-COUNT on the {@code status='pending'} index — measured in
 * sub-millisecond on a healthy box.
 *
 * <p>Auto-configured for any service that depends on the shared module and
 * has Micrometer on the classpath (Boot 4 + §1D.1 deps make that universal
 * across the 7 Northwood services). {@code outbox_message} is a per-service
 * table, so inbox-only services (reporting today) have none on their
 * {@code search_path}. Those services <b>skip the gauge entirely</b> — the
 * bean factory probes for the table once at startup with {@code to_regclass}
 * (which returns NULL rather than erroring on a missing relation) and returns
 * no gauge when it's absent, so reporting never runs the per-scrape COUNT and
 * never logs the "relation outbox_message does not exist" Postgres error.
 *
 * <p>For the services that <i>do</i> have the table, a query failure at scrape
 * time is now a "shouldn't happen" condition (logged WARN) and emits -1, which
 * the dashboard value-maps to "n/a" as an error indicator.
 */
@AutoConfiguration
@ConditionalOnClass(MeterRegistry.class)
public class OutboxMetricsAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(OutboxMetricsAutoConfiguration.class);

    @Bean
    public Gauge outboxPendingGauge(
        JdbcTemplate jdbc,
        MeterRegistry registry,
        @Value("${spring.application.name:northwood}") String application
    ) {
        if (!outboxTableExists(jdbc)) {
            // Inbox-only service (e.g. reporting) — no outbox_message on the
            // search_path. Skip registration rather than erroring every scrape.
            log.info("outbox.pending gauge disabled for '{}': no outbox_message table on search_path", application);
            return null;
        }
        return Gauge.builder("northwood.outbox.pending", () -> countPending(jdbc))
            .description("Outbox rows in status='pending' awaiting publication by the drainer")
            .tag("service", application)
            .baseUnit("rows")
            .register(registry);
    }

    private static boolean outboxTableExists(JdbcTemplate jdbc) {
        try {
            // to_regclass returns NULL (no error) when the relation is absent
            // from the search_path, so this probes existence without throwing.
            return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT to_regclass('outbox_message') IS NOT NULL", Boolean.class));
        } catch (Exception ex) {
            // Unexpected probe failure — fail open and register anyway;
            // countPending's own guard absorbs a genuinely broken query.
            log.warn("outbox_message existence probe failed ({}); registering gauge anyway", ex.getMessage());
            return true;
        }
    }

    private static double countPending(JdbcTemplate jdbc) {
        try {
            Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_message WHERE status = 'pending'",
                Long.class
            );
            return count == null ? 0.0 : count.doubleValue();
        } catch (Exception ex) {
            // The table existed at startup, so a failure here is unexpected —
            // emit -1 (dashboard maps to "n/a") and log loudly.
            log.warn("outbox.pending gauge: query failed ({}); reporting -1", ex.getMessage());
            return -1.0;
        }
    }
}
