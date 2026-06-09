package com.northwood.sales.domain;

import com.northwood.shared.domain.Assert;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * One line on a sales order. Lives inside the {@link SalesOrder} aggregate —
 * never persisted independently, never referenced by FK from outside the
 * aggregate. Quantity invariants are enforced here; line status is the
 * fulfilment-side projection of saga progress and is mutated by the aggregate.
 */
public final class SalesOrderLine {

    private final UUID lineId;
    private final int lineNumber;
    private final UUID productId;
    private final String productSku;
    private final String productName;
    // Mutable post-placement via the SalesOrder amendment mutators (addLine /
    // changeLine / removeLine) — pre-shipment only, guarded on the aggregate.
    private BigDecimal orderedQuantity;
    private BigDecimal unitPrice;
    private final BigDecimal taxRate;
    private BigDecimal reservedQuantity;
    private BigDecimal manufacturingRequiredQuantity;
    private BigDecimal shippedQuantity;
    private SalesOrder.LineStatus lineStatus;

    /**
     * Canonical constructor — used by reconstitution, which supplies the
     * cumulative {@code shippedQuantity} read back from the row.
     */
    public SalesOrderLine(
        UUID lineId,
        int lineNumber,
        UUID productId,
        String productSku,
        String productName,
        BigDecimal orderedQuantity,
        BigDecimal unitPrice,
        BigDecimal taxRate,
        BigDecimal reservedQuantity,
        BigDecimal manufacturingRequiredQuantity,
        BigDecimal shippedQuantity,
        SalesOrder.LineStatus lineStatus
    ) {
        Assert.argument(orderedQuantity.signum() > 0, "orderedQuantity must be > 0");
        Assert.argument(unitPrice.signum() >= 0, "unitPrice must be >= 0");
        this.lineId = Assert.notNull(lineId, "lineId");
        this.lineNumber = lineNumber;
        this.productId = Assert.notNull(productId, "productId");
        this.productSku = Assert.notNull(productSku, "productSku");
        this.productName = Assert.notNull(productName, "productName");
        this.orderedQuantity = orderedQuantity;
        this.unitPrice = unitPrice;
        this.taxRate = taxRate == null ? BigDecimal.ZERO : taxRate;
        this.reservedQuantity = reservedQuantity == null ? BigDecimal.ZERO : reservedQuantity;
        this.manufacturingRequiredQuantity = manufacturingRequiredQuantity == null ? BigDecimal.ZERO : manufacturingRequiredQuantity;
        this.shippedQuantity = shippedQuantity == null ? BigDecimal.ZERO : shippedQuantity;
        this.lineStatus = lineStatus;
    }

    /** New-line / place-order constructor — nothing shipped yet. */
    public SalesOrderLine(
        UUID lineId,
        int lineNumber,
        UUID productId,
        String productSku,
        String productName,
        BigDecimal orderedQuantity,
        BigDecimal unitPrice,
        BigDecimal taxRate,
        BigDecimal reservedQuantity,
        BigDecimal manufacturingRequiredQuantity,
        SalesOrder.LineStatus lineStatus
    ) {
        this(lineId, lineNumber, productId, productSku, productName, orderedQuantity, unitPrice, taxRate,
            reservedQuantity, manufacturingRequiredQuantity, BigDecimal.ZERO, lineStatus);
    }

    public BigDecimal lineSubtotal() {
        return orderedQuantity.multiply(unitPrice).setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal taxAmount() {
        return lineSubtotal().multiply(taxRate).setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal lineTotal() {
        return lineSubtotal().add(taxAmount());
    }

    void markReserved(BigDecimal quantity) {
        this.reservedQuantity = quantity;
        this.lineStatus = quantity.compareTo(orderedQuantity) >= 0
            ? SalesOrder.LineStatus.RESERVED
            : SalesOrder.LineStatus.PARTIALLY_RESERVED;
    }

    /**
     * Amend the line's ordered quantity and/or unit price (line-amendment flow).
     * Package-private — only the {@link SalesOrder} aggregate calls this, after
     * its own amendable-window guard. Re-applies the same invariants as the
     * constructor.
     */
    void amend(BigDecimal newOrderedQuantity, BigDecimal newUnitPrice) {
        Assert.argument(newOrderedQuantity != null && newOrderedQuantity.signum() > 0, "orderedQuantity must be > 0");
        Assert.argument(newUnitPrice != null && newUnitPrice.signum() >= 0, "unitPrice must be >= 0");
        this.orderedQuantity = newOrderedQuantity;
        this.unitPrice = newUnitPrice;
    }

    /**
     * Soft-cancel the line (line-amendment removal). The row is kept so the line
     * id stays resolvable for inventory to release against; totals exclude
     * cancelled lines. Package-private — driven by {@link SalesOrder#removeLine}.
     */
    void cancelLine() {
        this.lineStatus = SalesOrder.LineStatus.CANCELLED;
    }

    public boolean isCancelled() {
        return lineStatus == SalesOrder.LineStatus.CANCELLED;
    }

    /**
     * Add {@code quantity} to this line's cumulative shipped quantity and move
     * its status to {@code shipped} (cumulative meets ordered) or
     * {@code partially_shipped} (still short). Mirrors the DB CHECK
     * {@code shipped_quantity <= ordered_quantity} as an aggregate invariant.
     * Called by {@link SalesOrder#recordShipped} once per matched shipment line.
     */
    void recordShipment(BigDecimal quantity) {
        Assert.argument(quantity != null && quantity.signum() > 0, "shipped quantity must be > 0");
        BigDecimal next = shippedQuantity.add(quantity);
        Assert.state(next.compareTo(orderedQuantity) <= 0,
            "cumulative shipped quantity " + next + " would exceed ordered quantity "
                + orderedQuantity + " for line " + lineId);
        this.shippedQuantity = next;
        this.lineStatus = next.compareTo(orderedQuantity) >= 0
            ? SalesOrder.LineStatus.SHIPPED
            : SalesOrder.LineStatus.PARTIALLY_SHIPPED;
    }

    public UUID lineId()                              { return lineId; }
    public int lineNumber()                           { return lineNumber; }
    public UUID productId()                           { return productId; }
    public String productSku()                        { return productSku; }
    public String productName()                       { return productName; }
    public BigDecimal orderedQuantity()               { return orderedQuantity; }
    public BigDecimal unitPrice()                     { return unitPrice; }
    public BigDecimal taxRate()                       { return taxRate; }
    public BigDecimal reservedQuantity()              { return reservedQuantity; }
    public BigDecimal manufacturingRequiredQuantity() { return manufacturingRequiredQuantity; }
    public BigDecimal shippedQuantity()               { return shippedQuantity; }
    /** Ordered minus cumulative shipped, floored at zero — the outstanding backorder. */
    public BigDecimal backorderedQuantity()           { return orderedQuantity.subtract(shippedQuantity).max(BigDecimal.ZERO); }
    public SalesOrder.LineStatus lineStatus()         { return lineStatus; }
}
