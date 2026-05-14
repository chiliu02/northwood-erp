package com.northwood.inventory.domain;

import java.math.BigDecimal;
import java.util.Objects;
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
    private final String status;

    public StockReservationLine(
        UUID lineId,
        UUID productId,
        String productSku,
        String productName,
        BigDecimal requestedQuantity,
        BigDecimal reservedQuantity,
        BigDecimal shortageQuantity,
        String status
    ) {
        if (requestedQuantity.signum() <= 0) {
            throw new IllegalArgumentException("requestedQuantity must be > 0");
        }
        this.lineId = Objects.requireNonNull(lineId);
        this.productId = Objects.requireNonNull(productId);
        this.productSku = Objects.requireNonNull(productSku);
        this.productName = Objects.requireNonNull(productName);
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
    public String status()                { return status; }
}
