package com.northwood.reporting.infrastructure.persistence;

import com.northwood.reporting.application.dto.AvailableToPromiseView;
import com.northwood.reporting.application.AvailableToPromiseQueryPort;
import java.sql.Date;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAvailableToPromiseQueryPort implements AvailableToPromiseQueryPort {

    private static final String SELECT_BASE = """
        SELECT product_id, product_sku, product_name,
               on_hand_quantity, reserved_for_sales, reserved_for_production,
               available_quantity, incoming_from_production, incoming_from_purchase,
               earliest_available_date, stock_status, updated_at
          FROM reporting.available_to_promise_view
        """;

    private static final RowMapper<AvailableToPromiseView> MAPPER = (rs, n) -> {
        Date earliest = rs.getDate("earliest_available_date");
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        return new AvailableToPromiseView(
            rs.getObject("product_id", UUID.class),
            rs.getString("product_sku"),
            rs.getString("product_name"),
            rs.getBigDecimal("on_hand_quantity"),
            rs.getBigDecimal("reserved_for_sales"),
            rs.getBigDecimal("reserved_for_production"),
            rs.getBigDecimal("available_quantity"),
            rs.getBigDecimal("incoming_from_production"),
            rs.getBigDecimal("incoming_from_purchase"),
            earliest == null ? null : earliest.toLocalDate(),
            rs.getString("stock_status"),
            updatedAt == null ? null : updatedAt.toInstant()
        );
    };

    private final JdbcTemplate jdbc;

    public JdbcAvailableToPromiseQueryPort(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<AvailableToPromiseView> findAll() {
        return jdbc.query(SELECT_BASE + " ORDER BY product_sku", MAPPER);
    }

    @Override
    public Optional<AvailableToPromiseView> findByProductId(UUID productId) {
        List<AvailableToPromiseView> rows = jdbc.query(
            SELECT_BASE + " WHERE product_id = ?", MAPPER, productId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

}
