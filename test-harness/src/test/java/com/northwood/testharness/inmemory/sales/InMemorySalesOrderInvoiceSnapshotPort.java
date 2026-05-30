package com.northwood.testharness.inmemory.sales;

import com.northwood.sales.application.saga.SalesOrderInvoiceSnapshotPort;
import com.northwood.sales.domain.SalesOrder;
import com.northwood.sales.domain.SalesOrderId;
import com.northwood.sales.domain.SalesOrderLine;
import com.northwood.sales.domain.SalesOrderRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * §2.31 Slice B. Reads the sales-order header + per-line pricing straight
 * from the in-memory aggregate store. Production has the JDBC variant that
 * reads {@code sales.sales_order_header} + {@code sales.sales_order_line}
 * directly; the harness has the aggregate already in memory.
 */
public final class InMemorySalesOrderInvoiceSnapshotPort implements SalesOrderInvoiceSnapshotPort {

    private final SalesOrderRepository orders;

    public InMemorySalesOrderInvoiceSnapshotPort(SalesOrderRepository orders) {
        this.orders = orders;
    }

    @Override
    public Optional<OrderForPrepayment> findOrderForPrepayment(UUID salesOrderHeaderId) {
        SalesOrder order = orders.findById(SalesOrderId.of(salesOrderHeaderId)).orElse(null);
        if (order == null) {
            return Optional.empty();
        }
        List<PricedLine> lines = new ArrayList<>();
        for (SalesOrderLine line : order.lines()) {
            lines.add(new PricedLine(
                line.lineId(),
                line.lineNumber(),
                line.productId(),
                line.productSku(),
                line.productName(),
                line.orderedQuantity(),
                line.unitPrice(),
                line.taxRate()
            ));
        }
        return Optional.of(new OrderForPrepayment(
            salesOrderHeaderId,
            order.orderNumber(),
            order.customerId(),
            order.customerCode(),
            order.customerName(),
            order.currencyCode(),
            order.depositPercent(),
            lines
        ));
    }
}
