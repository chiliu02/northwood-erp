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
 * across the 7 Northwood services). Inbox-only services (reporting today)
 * register the gauge too but report 0 forever — the SQL still works
 * (search_path picks up the local schema's empty {@code outbox_message}
 * table; reporting doesn't have one, so the gauge would error on first scrape
 * if reporting had this AutoConfiguration active — see note below).
 *
 * <p><b>Reporting-service note:</b> reporting has no {@code outbox_message}
 * table in its schema. To avoid scrape-time errors we catch and report -1
 * instead of throwing; Grafana renders -1 as "n/a" via a value-mapping in
 * the dashboard JSON.
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
        return Gauge.builder("northwood.outbox.pending", () -> countPending(jdbc))
            .description("Outbox rows in status='pending' awaiting publication by the drainer")
            .tag("service", application)
            .baseUnit("rows")
            .register(registry);
    }

    private static double countPending(JdbcTemplate jdbc) {
        try {
            Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_message WHERE status = 'pending'",
                Long.class
            );
            return count == null ? 0.0 : count.doubleValue();
        } catch (Exception ex) {
            // Reporting-service has no outbox_message table — emit -1 so the
            // dashboard can value-map it to "n/a" without blocking the scrape.
            log.debug("outbox.pending gauge: query failed ({}); reporting -1", ex.getMessage());
            return -1.0;
        }
    }
}
