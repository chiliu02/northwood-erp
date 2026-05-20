package com.northwood.purchasing.infrastructure.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PostConstruct;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * §1D.5: per-state row count for purchase-to-pay sagas. See sales-service's
 * sibling class for the design rationale (MultiGauge, 15s poll matching
 * Prometheus scrape, GROUP BY query on the existing state index).
 */
@Component
public class SagaStateMetrics {

    private static final Logger log = LoggerFactory.getLogger(SagaStateMetrics.class);
    private static final String SAGA_TYPE = "purchase_to_pay";

    private final JdbcTemplate jdbc;
    private final MultiGauge gauge;

    public SagaStateMetrics(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.gauge = MultiGauge.builder("northwood.saga.count")
            .description("Active saga row count by type and state")
            .baseUnit("rows")
            .tag("type", SAGA_TYPE)
            .register(registry);
    }

    @PostConstruct
    void primeAtStartup() {
        refresh();
    }

    @Scheduled(fixedDelay = 15000)
    public void refresh() {
        try {
            List<MultiGauge.Row<?>> rows = jdbc.query(
                "SELECT saga_state, COUNT(*) AS n FROM purchasing.purchase_to_pay_saga GROUP BY saga_state",
                (rs, n) -> MultiGauge.Row.of(
                    Tags.of("state", rs.getString("saga_state")),
                    rs.getLong("n")
                )
            );
            gauge.register(rows, true);
        } catch (Exception ex) {
            log.debug("saga.count refresh failed: {}", ex.getMessage());
        }
    }
}
