package com.northwood.reporting.infrastructure.persistence;

import com.northwood.reporting.application.dto.MaterialShortageView;
import com.northwood.reporting.application.MaterialShortageQueryPort;
import java.sql.Date;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcMaterialShortageQueryPort implements MaterialShortageQueryPort {

    private static final String SELECT_BASE = """
        SELECT material_product_id, material_sku, material_name,
               required_quantity, available_quantity, shortage_quantity,
               affected_work_orders_count, affected_sales_orders_count,
               open_purchase_orders_count, incoming_purchase_quantity,
               expected_receipt_date, status, updated_at
          FROM reporting.material_shortage_view
        """;

    private static final String STATUS_ORDER =
        " ORDER BY CASE status "
        + "  WHEN 'open' THEN 0 "
        + "  WHEN 'purchase_requested' THEN 1 "
        + "  WHEN 'purchase_ordered' THEN 2 "
        + "  WHEN 'resolved' THEN 3 "
        + "  ELSE 4 END, material_sku";

    private static final RowMapper<MaterialShortageView> MAPPER = (rs, n) -> {
        Date expectedDate = rs.getDate("expected_receipt_date");
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        return new MaterialShortageView(
            rs.getObject("material_product_id", UUID.class),
            rs.getString("material_sku"),
            rs.getString("material_name"),
            rs.getBigDecimal("required_quantity"),
            rs.getBigDecimal("available_quantity"),
            rs.getBigDecimal("shortage_quantity"),
            rs.getInt("affected_work_orders_count"),
            rs.getInt("affected_sales_orders_count"),
            rs.getInt("open_purchase_orders_count"),
            rs.getBigDecimal("incoming_purchase_quantity"),
            expectedDate == null ? null : expectedDate.toLocalDate(),
            rs.getString("status"),
            updatedAt == null ? null : updatedAt.toInstant()
        );
    };

    private final JdbcTemplate jdbc;

    public JdbcMaterialShortageQueryPort(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<MaterialShortageView> findAll() {
        return jdbc.query(SELECT_BASE + STATUS_ORDER, MAPPER);
    }

    @Override
    public List<MaterialShortageView> findActive() {
        return jdbc.query(SELECT_BASE + " WHERE status <> 'resolved'" + STATUS_ORDER, MAPPER);
    }

    @Override
    public Optional<MaterialShortageView> findByProductId(UUID materialProductId) {
        List<MaterialShortageView> rows = jdbc.query(
            SELECT_BASE + " WHERE material_product_id = ?",
            MAPPER, materialProductId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

}
