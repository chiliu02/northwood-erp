package com.northwood.sales.infrastructure.metrics;

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
 * Per-state row count for sales-order fulfilment sagas, exposed as
 * {@code northwood.saga.count{type="sales_order_fulfilment", state="..."}}.
 * The Grafana showcase board's "bus health" row uses this to render a
 * saga-state bar chart.
 *
 * <p>A {@link MultiGauge} is the right Micrometer shape here — the set of
 * states isn't fixed (an unused state's gauge disappears, a new state
 * appears) and a single registry call refreshes the whole picture. Polled
 * every 15s by a {@link Scheduled} method; matches the Prometheus scrape
 * cadence (no point updating faster than the scrape consumes).
 *
 * <p>SQL is a single {@code SELECT saga_state, COUNT(*) GROUP BY saga_state}
 * on the saga table — sub-millisecond on the existing
 * {@code idx_sales_order_fulfilment_saga_state} index. No transaction needed.
 */
@Component
public class SagaStateMetrics {

    private static final Logger log = LoggerFactory.getLogger(SagaStateMetrics.class);
    private static final String SAGA_TYPE = "sales_order_fulfilment";

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
                "SELECT saga_state, COUNT(*) AS n FROM sales.sales_order_fulfilment_saga GROUP BY saga_state",
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
