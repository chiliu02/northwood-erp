package com.northwood.testharness.inmemory.sales;

import com.northwood.product.domain.ReplenishmentStrategy;
import com.northwood.sales.application.saga.SalesOrderLineSnapshotPort;
import com.northwood.sales.domain.SalesOrder;
import com.northwood.sales.domain.SalesOrderId;
import com.northwood.sales.domain.SalesOrderLine;
import com.northwood.sales.domain.SalesOrderRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Reads sales-order lines straight from the in-memory aggregate store.
 * Production has the JDBC variant that reads {@code sales.sales_order_line}
 * directly (denormalised for the saga worker's read pattern); the harness
 * has the aggregate already in memory, so we just project off it.
 */
public final class InMemorySalesOrderLineSnapshotPort implements SalesOrderLineSnapshotPort {

    private final SalesOrderRepository orders;

    /**
     * Products configured order-pegged ({@code to_order}). The aggregate
     * doesn't carry the product-master replenishment strategy (it's projected
     * onto {@code sales.product_card} in production), so a test opts a product in
     * here to exercise the make-/buy-to-order fulfilment path.
     */
    private final Set<UUID> orderPeggedProductIds = new HashSet<>();

    public InMemorySalesOrderLineSnapshotPort(SalesOrderRepository orders) {
        this.orders = orders;
    }

    /** Mark a product order-pegged so its SO lines reserve dedicated supply. */
    public InMemorySalesOrderLineSnapshotPort markOrderPegged(UUID productId) {
        orderPeggedProductIds.add(productId);
        return this;
    }

    @Override
    public List<LineSnapshot> findLines(UUID salesOrderHeaderId) {
        SalesOrder order = orders.findById(SalesOrderId.of(salesOrderHeaderId)).orElse(null);
        if (order == null) {
            return List.of();
        }
        List<LineSnapshot> out = new ArrayList<>();
        for (SalesOrderLine line : order.lines()) {
            out.add(new LineSnapshot(
                line.lineId(),
                line.lineNumber(),
                line.productId(),
                line.productSku(),
                line.productName(),
                line.orderedQuantity(),
                // The in-memory SalesOrder aggregate doesn't carry the product's
                // replenishment strategy (it's a product-master facet projected
                // onto sales.product_card); default to_stock unless a test opted
                // the product into order-pegged via markOrderPegged.
                (orderPeggedProductIds.contains(line.productId())
                    ? ReplenishmentStrategy.TO_ORDER
                    : ReplenishmentStrategy.TO_STOCK).dbValue()
            ));
        }
        return out;
    }
}
