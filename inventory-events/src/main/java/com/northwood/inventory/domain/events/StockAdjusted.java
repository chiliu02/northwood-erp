package com.northwood.inventory.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A warehouse stock adjustment has been posted — a manual inventory gain or
 * loss (cycle-count correction, damage, shrinkage, demo setup). §2.29.
 *
 * <p>The change is carried as a positive {@code quantity} magnitude plus a
 * {@code direction} ({@code in} = stock gain, {@code out} = stock loss), so
 * the wire stays consistent with {@code stock_movement.direction}. Finance is
 * the cross-context consumer: it values the delta at the product's standard
 * cost and posts the inventory-adjustment journal entry (Dr/Cr inventory vs
 * the Inventory Adjustment account) — an inventory value change is never
 * off-book.
 *
 * <p>{@code aggregateId} is the stock-adjustment id.
 */
public record StockAdjusted(
    UUID eventId,
    UUID aggregateId,
    String adjustmentNumber,
    UUID warehouseId,
    String warehouseCode,
    UUID productId,
    String productSku,
    String productName,
    String direction,
    BigDecimal quantity,
    String reason,
    Instant occurredAt
) implements DomainEvent {

    public static final String EVENT_TYPE = "inventory.StockAdjusted";

    /** {@code direction} wire value for a stock gain (on-hand increases). */
    public static final String DIRECTION_IN = "in";
    /** {@code direction} wire value for a stock loss (on-hand decreases). */
    public static final String DIRECTION_OUT = "out";

    @Override public String eventType() { return EVENT_TYPE; }
}
