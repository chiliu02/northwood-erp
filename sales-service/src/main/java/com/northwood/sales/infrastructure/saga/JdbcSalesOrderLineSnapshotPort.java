package com.northwood.sales.infrastructure.saga;

import com.northwood.sales.application.saga.SalesOrderLineSnapshotPort;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * JDBC adapter for {@link SalesOrderLineSnapshotPort}. Reads from
 * {@code sales.sales_order_line} in {@code line_number} order.
 */
@Component
public class JdbcSalesOrderLineSnapshotPort implements SalesOrderLineSnapshotPort {

    private final JdbcTemplate jdbc;

    public JdbcSalesOrderLineSnapshotPort(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<LineSnapshot> findLines(UUID salesOrderHeaderId) {
        List<LineSnapshot> out = new ArrayList<>();
        jdbc.query(
            """
            SELECT l.sales_order_line_id, l.line_number, l.product_id, l.product_sku, l.product_name,
                   l.ordered_quantity,
                   COALESCE(pc.replenishment_strategy, 'to_stock') AS replenishment_strategy,
                   COALESCE(pc.planning_time_fence_days, 0) AS planning_time_fence_days
            FROM sales.sales_order_line l
            LEFT JOIN sales.product_card pc ON pc.product_id = l.product_id
            WHERE l.sales_order_header_id = ?
            ORDER BY l.line_number
            """,
            rs -> {
                out.add(new LineSnapshot(
                    rs.getObject("sales_order_line_id", UUID.class),
                    rs.getInt("line_number"),
                    rs.getObject("product_id", UUID.class),
                    rs.getString("product_sku"),
                    rs.getString("product_name"),
                    rs.getBigDecimal("ordered_quantity"),
                    rs.getString("replenishment_strategy"),
                    rs.getInt("planning_time_fence_days")
                ));
            },
            salesOrderHeaderId
        );
        return out;
    }
}
