package com.northwood.sales.application.saga;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-side snapshot of a sales order's header + line pricing, scoped to what
 * the fulfilment saga worker needs to build a {@code PrepaymentInvoiceRequested}
 * payload. Distinct from
 * {@link SalesOrderLineSnapshotPort} — the existing port returns only
 * fulfilment-relevant line fields (qty, sku); this one carries the customer
 * header + per-line pricing (unitPrice, taxRate) that finance needs to build
 * the invoice.
 *
 * <p>Lives in {@code application/saga/} (port) with the JDBC impl in
 * {@code infrastructure/saga/} so both sides of the saga split — manager and
 * snapshot reads — sit together architecturally.
 */
public interface SalesOrderInvoiceSnapshotPort {

    /**
     * Header + lines for the given order, or empty when no such order
     * exists. Lines are in {@code line_number} order.
     */
    Optional<OrderForPrepayment> findOrderForPrepayment(UUID salesOrderHeaderId);

    record OrderForPrepayment(
        UUID salesOrderHeaderId,
        String orderNumber,
        UUID customerId,
        String customerCode,
        String customerName,
        String currencyCode,
        /** The order's deposit percent (0,100]; non-null only for deposit orders. */
        BigDecimal depositPercent,
        List<PricedLine> lines
    ) {}

    record PricedLine(
        UUID salesOrderLineId,
        int lineNumber,
        UUID productId,
        String productSku,
        String productName,
        BigDecimal orderedQuantity,
        BigDecimal unitPrice,
        BigDecimal taxRate
    ) {}
}
