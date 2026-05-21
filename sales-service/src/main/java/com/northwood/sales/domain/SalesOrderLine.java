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
    private final BigDecimal orderedQuantity;
    private final BigDecimal unitPrice;
    private final BigDecimal taxRate;
    private BigDecimal reservedQuantity;
    private BigDecimal manufacturingRequiredQuantity;
    private SalesOrder.LineStatus lineStatus;

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
        this.lineStatus = lineStatus;
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
    public SalesOrder.LineStatus lineStatus()         { return lineStatus; }
}
