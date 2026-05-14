package com.northwood.inventory.infrastructure.persistence;

import com.northwood.inventory.application.StockReservationQueryPort;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcStockReservationQueryPort implements StockReservationQueryPort {

    private final JdbcTemplate jdbc;

    public JdbcStockReservationQueryPort(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ReservationRow> listAll() {
        return jdbc.query("""
            SELECT h.stock_reservation_header_id,
                   h.sales_order_header_id,
                   h.work_order_id,
                   h.warehouse_id,
                   h.status,
                   h.created_at,
                   COALESCE(t.line_count, 0) AS line_count,
                   COALESCE(t.total_requested, 0) AS total_requested,
                   COALESCE(t.total_reserved, 0) AS total_reserved,
                   COALESCE(t.total_shortage, 0) AS total_shortage
            FROM inventory.stock_reservation_header h
            LEFT JOIN (
                SELECT stock_reservation_header_id,
                       COUNT(*) AS line_count,
                       SUM(requested_quantity) AS total_requested,
                       SUM(reserved_quantity) AS total_reserved,
                       SUM(shortage_quantity) AS total_shortage
                FROM inventory.stock_reservation_line
                GROUP BY stock_reservation_header_id
            ) t ON t.stock_reservation_header_id = h.stock_reservation_header_id
            ORDER BY h.created_at DESC
            """,
            (rs, n) -> new ReservationRow(
                rs.getObject("stock_reservation_header_id", UUID.class),
                rs.getObject("sales_order_header_id", UUID.class),
                rs.getObject("work_order_id", UUID.class),
                rs.getObject("warehouse_id", UUID.class),
                rs.getString("status"),
                rs.getInt("line_count"),
                rs.getBigDecimal("total_requested"),
                rs.getBigDecimal("total_reserved"),
                rs.getBigDecimal("total_shortage"),
                rs.getTimestamp("created_at").toInstant()
            )
        );
    }
}
