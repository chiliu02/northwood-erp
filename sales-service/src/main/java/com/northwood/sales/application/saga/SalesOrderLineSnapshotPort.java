package com.northwood.sales.application.saga;

import com.northwood.product.domain.ReplenishmentStrategy;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Read-side snapshot of a sales order's lines, scoped to what the fulfilment
 * saga worker needs to build {@code StockReservationRequested} and
 * {@code ManufacturingRequested}. Extracted so the worker doesn't reach into
 * raw {@code JdbcTemplate} and so the harness can wire an in-memory variant
 * for E2E tests.
 *
 * <p>Lives in {@code application/saga/} (port) with the JDBC impl in
 * {@code infrastructure/saga/} so both sides of the saga split — manager and
 * snapshot reads — sit together architecturally.
 */
public interface SalesOrderLineSnapshotPort {

    /**
     * Lines in {@code line_number} order. Empty when the order has no lines —
     * caller decides whether that's an error.
     */
    List<LineSnapshot> findLines(UUID salesOrderHeaderId);

    /**
     * @param replenishmentStrategy the product's {@code to_stock} | {@code to_order}
     *     strategy, projected onto {@code sales.product_card} (§2.43). Drives the
     *     reserve-step branch via {@link #pegged()}.
     */
    record LineSnapshot(
        UUID salesOrderLineId,
        int lineNumber,
        UUID productId,
        String productSku,
        String productName,
        BigDecimal orderedQuantity,
        String replenishmentStrategy
    ) {
        /**
         * True when the product is order-pegged ({@code to_order}) — the
         * fulfilment saga raises dedicated supply for this line rather than
         * reserving from free stock.
         */
        public boolean pegged() {
            return ReplenishmentStrategy.TO_ORDER.dbValue().equals(replenishmentStrategy);
        }
    }
}
