package com.northwood.reporting.infrastructure.persistence;

import com.northwood.reporting.application.ReplenishmentHistoryQueryPort;
import com.northwood.reporting.application.dto.ReplenishmentHistoryView;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcReplenishmentHistoryQueryPort implements ReplenishmentHistoryQueryPort {

    private static final RowMapper<ReplenishmentHistoryView> ROW_MAPPER = (rs, n) -> {
        Timestamp dispatchedAt = rs.getTimestamp("dispatched_at");
        Timestamp fulfilledAt = rs.getTimestamp("fulfilled_at");
        return new ReplenishmentHistoryView(
            rs.getObject("replenishment_request_id", UUID.class),
            rs.getObject("product_id", UUID.class),
            rs.getString("product_sku"),
            rs.getString("product_name"),
            rs.getObject("warehouse_id", UUID.class),
            rs.getBigDecimal("requested_quantity"),
            rs.getString("target_service"),
            rs.getString("reason"),
            rs.getString("status"),
            rs.getString("dispatched_aggregate_kind"),
            rs.getObject("dispatched_aggregate_id", UUID.class),
            rs.getTimestamp("requested_at").toInstant(),
            dispatchedAt == null ? null : dispatchedAt.toInstant(),
            fulfilledAt == null ? null : fulfilledAt.toInstant()
        );
    };

    private static final String BASE_SELECT = """
        SELECT h.replenishment_request_id, h.product_id, h.warehouse_id,
               h.requested_quantity, h.target_service, h.reason, h.status,
               h.dispatched_aggregate_kind, h.dispatched_aggregate_id,
               h.requested_at, h.dispatched_at, h.fulfilled_at,
               atp.product_sku, atp.product_name
        FROM reporting.replenishment_history_view h
        LEFT JOIN reporting.available_to_promise_view atp ON atp.product_id = h.product_id
        """;

    private final JdbcTemplate jdbc;

    public JdbcReplenishmentHistoryQueryPort(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ReplenishmentHistoryView> findAll(int limit) {
        return jdbc.query(
            BASE_SELECT + " ORDER BY h.requested_at DESC LIMIT ?",
            ROW_MAPPER, limit
        );
    }

    @Override
    public List<ReplenishmentHistoryView> findRecentForProduct(UUID productId, int limit) {
        return jdbc.query(
            BASE_SELECT + " WHERE h.product_id = ? ORDER BY h.requested_at DESC LIMIT ?",
            ROW_MAPPER, productId, limit
        );
    }
}
