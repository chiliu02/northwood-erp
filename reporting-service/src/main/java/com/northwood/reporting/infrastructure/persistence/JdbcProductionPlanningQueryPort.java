package com.northwood.reporting.infrastructure.persistence;

import com.northwood.reporting.application.dto.ProductionPlanningView;
import com.northwood.reporting.application.ProductionPlanningQueryPort;
import java.sql.Date;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcProductionPlanningQueryPort implements ProductionPlanningQueryPort {

    private static final String SELECT_ALL = """
        SELECT work_order_id, work_order_number,
               sales_order_header_id, order_number,
               finished_product_id, finished_product_sku, finished_product_name,
               planned_quantity, completed_quantity,
               work_order_status, material_status,
               shortage_materials_count, shortage_summary,
               open_purchase_orders_count,
               expected_material_available_date, planned_start_date, planned_end_date,
               priority, updated_at
          FROM reporting.production_planning_board
        """;

    private static final RowMapper<ProductionPlanningView> MAPPER = (rs, n) -> {
        Date expectedDate = rs.getDate("expected_material_available_date");
        Date plannedStart = rs.getDate("planned_start_date");
        Date plannedEnd = rs.getDate("planned_end_date");
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        return new ProductionPlanningView(
            rs.getObject("work_order_id", UUID.class),
            rs.getString("work_order_number"),
            rs.getObject("sales_order_header_id", UUID.class),
            rs.getString("order_number"),
            rs.getObject("finished_product_id", UUID.class),
            rs.getString("finished_product_sku"),
            rs.getString("finished_product_name"),
            rs.getBigDecimal("planned_quantity"),
            rs.getBigDecimal("completed_quantity"),
            rs.getString("work_order_status"),
            rs.getString("material_status"),
            rs.getInt("shortage_materials_count"),
            rs.getString("shortage_summary"),
            rs.getInt("open_purchase_orders_count"),
            expectedDate == null ? null : expectedDate.toLocalDate(),
            plannedStart == null ? null : plannedStart.toLocalDate(),
            plannedEnd == null ? null : plannedEnd.toLocalDate(),
            rs.getString("priority"),
            updatedAt == null ? null : updatedAt.toInstant()
        );
    };

    private final JdbcTemplate jdbc;

    public JdbcProductionPlanningQueryPort(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ProductionPlanningView> findByWorkOrderId(UUID workOrderId) {
        List<ProductionPlanningView> rows = jdbc.query(
            SELECT_ALL + " WHERE work_order_id = ?",
            MAPPER, workOrderId
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public List<ProductionPlanningView> findAll() {
        return jdbc.query(
            SELECT_ALL + " ORDER BY updated_at DESC NULLS LAST",
            MAPPER
        );
    }

}
