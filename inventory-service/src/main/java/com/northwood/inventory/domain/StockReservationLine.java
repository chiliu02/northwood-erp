package com.northwood.inventory.domain;

import com.northwood.shared.domain.Assert;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * One line of a stock reservation. Owned entirely by the {@link StockReservation}
 * aggregate — no FKs in or out except to its parent header.
 */
public final class StockReservationLine {

    private final UUID lineId;
    private final UUID productId;
    private final String productSku;
    private final String productName;
    private final BigDecimal requestedQuantity;
    private final BigDecimal reservedQuantity;
    private final BigDecimal shortageQuantity;
    private final StockReservation.Status status;

    public StockReservationLine(
        UUID lineId,
        UUID productId,
        String productSku,
        String productName,
        BigDecimal requestedQuantity,
        BigDecimal reservedQuantity,
        BigDecimal shortageQuantity,
        StockReservation.Status status
    ) {
        Assert.argument(requestedQuantity.signum() > 0, "requestedQuantity must be > 0");
        this.lineId = Assert.notNull(lineId, "lineId");
        this.productId = Assert.notNull(productId, "productId");
        this.productSku = Assert.notNull(productSku, "productSku");
        this.productName = Assert.notNull(productName, "productName");
        this.requestedQuantity = requestedQuantity;
        this.reservedQuantity = reservedQuantity == null ? BigDecimal.ZERO : reservedQuantity;
        this.shortageQuantity = shortageQuantity == null ? BigDecimal.ZERO : shortageQuantity;
        this.status = status;
    }

    public UUID lineId()                  { return lineId; }
    public UUID productId()               { return productId; }
    public String productSku()            { return productSku; }
    public String productName()           { return productName; }
    public BigDecimal requestedQuantity() { return requestedQuantity; }
    public BigDecimal reservedQuantity()  { return reservedQuantity; }
    public BigDecimal shortageQuantity()  { return shortageQuantity; }
    public StockReservation.Status status()      { return status; }
}
