package com.northwood.sales.infrastructure.saga;

import com.northwood.sales.application.saga.SalesOrderInvoiceSnapshotPort;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;

/**
 * JDBC adapter for {@link SalesOrderInvoiceSnapshotPort}.
 * Reads from {@code sales.sales_order_header} + {@code sales.sales_order_line}
 * — fields needed to build a {@code PrepaymentInvoiceRequested} payload.
 */
@Component
public class JdbcSalesOrderInvoiceSnapshotPort implements SalesOrderInvoiceSnapshotPort {

    private final JdbcTemplate jdbc;

    public JdbcSalesOrderInvoiceSnapshotPort(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<OrderForPrepayment> findOrderForPrepayment(UUID salesOrderHeaderId) {
        List<OrderForPrepayment> headers = jdbc.query(
            """
            SELECT order_number, customer_id, customer_code, customer_name, currency_code, deposit_percent
            FROM sales.sales_order_header
            WHERE sales_order_header_id = ?
            """,
            (rs, n) -> new OrderForPrepayment(
                salesOrderHeaderId,
                rs.getString("order_number"),
                rs.getObject("customer_id", UUID.class),
                rs.getString("customer_code"),
                rs.getString("customer_name"),
                rs.getString("currency_code"),
                rs.getBigDecimal("deposit_percent"),
                List.of()
            ),
            salesOrderHeaderId
        );
        if (headers.isEmpty()) {
            return Optional.empty();
        }
        OrderForPrepayment header = headers.get(0);

        List<PricedLine> lines = new ArrayList<>();
        RowCallbackHandler lineRow = rs -> lines.add(new PricedLine(
            rs.getObject("sales_order_line_id", UUID.class),
            rs.getInt("line_number"),
            rs.getObject("product_id", UUID.class),
            rs.getString("product_sku"),
            rs.getString("product_name"),
            rs.getBigDecimal("ordered_quantity"),
            rs.getBigDecimal("unit_price"),
            rs.getBigDecimal("tax_rate")
        ));
        jdbc.query(
            """
            SELECT sales_order_line_id, line_number, product_id, product_sku, product_name,
                   ordered_quantity, unit_price, tax_rate
            FROM sales.sales_order_line
            WHERE sales_order_header_id = ?
            ORDER BY line_number
            """,
            lineRow,
            salesOrderHeaderId
        );

        return Optional.of(new OrderForPrepayment(
            header.salesOrderHeaderId(),
            header.orderNumber(),
            header.customerId(),
            header.customerCode(),
            header.customerName(),
            header.currencyCode(),
            header.depositPercent(),
            lines
        ));
    }
}
