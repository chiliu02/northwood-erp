package com.northwood.reporting.infrastructure.persistence;

import com.northwood.reporting.application.dto.SalesOrder360View;
import com.northwood.reporting.application.SalesOrder360QueryPort;
import java.sql.Date;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcSalesOrder360QueryPort implements SalesOrder360QueryPort {

    private static final String SELECT_ALL = """
        SELECT sales_order_header_id, order_number,
               customer_id, customer_name,
               order_date, requested_delivery_date,
               order_status, stock_status, manufacturing_status,
               shipment_status, invoice_status, payment_status,
               currency_code,
               total_amount, invoiced_amount, paid_amount, outstanding_amount,
               has_shortage, shortage_summary,
               last_event_type, last_event_at, updated_at
          FROM reporting.sales_order_360_view
        """;

    private static final RowMapper<SalesOrder360View> MAPPER = (rs, n) -> {
        Date orderDate = rs.getDate("order_date");
        Date reqDate = rs.getDate("requested_delivery_date");
        Timestamp lastEventAt = rs.getTimestamp("last_event_at");
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        return new SalesOrder360View(
            rs.getObject("sales_order_header_id", UUID.class),
            rs.getString("order_number"),
            rs.getObject("customer_id", UUID.class),
            rs.getString("customer_name"),
            orderDate == null ? null : orderDate.toLocalDate(),
            reqDate == null ? null : reqDate.toLocalDate(),
            rs.getString("order_status"),
            rs.getString("stock_status"),
            rs.getString("manufacturing_status"),
            rs.getString("shipment_status"),
            rs.getString("invoice_status"),
            rs.getString("payment_status"),
            rs.getString("currency_code"),
            rs.getBigDecimal("total_amount"),
            rs.getBigDecimal("invoiced_amount"),
            rs.getBigDecimal("paid_amount"),
            rs.getBigDecimal("outstanding_amount"),
            rs.getBoolean("has_shortage"),
            rs.getString("shortage_summary"),
            rs.getString("last_event_type"),
            lastEventAt == null ? null : lastEventAt.toInstant(),
            updatedAt == null ? null : updatedAt.toInstant()
        );
    };

    private final JdbcTemplate jdbc;

    public JdbcSalesOrder360QueryPort(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<SalesOrder360View> findBySalesOrderId(UUID salesOrderHeaderId) {
        List<SalesOrder360View> rows = jdbc.query(
            SELECT_ALL + " WHERE sales_order_header_id = ?",
            MAPPER, salesOrderHeaderId
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public List<SalesOrder360View> findAll() {
        return jdbc.query(
            SELECT_ALL + " ORDER BY updated_at DESC NULLS LAST",
            MAPPER
        );
    }

}
