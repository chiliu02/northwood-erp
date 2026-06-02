package com.northwood.inventory.application.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Read-side projection of an {@code inventory.product_card} row for the wire
 * layer (the consolidated stock_item + product_card; the "stock item" name is
 * retained as the inventory-facing concept). Returned directly by the
 * {@code StockItemQueryPort} — the table is a snapshot projection of upstream
 * product-master facts, never an aggregate, so no domain root sits in between.
 * Keyed by {@code productId} (the card's primary key); there is no
 * inventory-minted surrogate id.
 *
 * <p>{@code onHand} / {@code reserved} / {@code available} are joined from
 * {@code inventory.stock_balance}, summed across warehouses per product
 * (zero when no balance row exists). Read-side only — the join lives in the
 * query, not in any projection write.
 */
public record StockItemView(
    UUID productId,
    String productSku,
    String productName,
    String productType,
    String baseUomCode,
    String trackingMode,
    BigDecimal reorderPoint,
    BigDecimal reorderQuantity,
    BigDecimal onHand,
    BigDecimal reserved,
    BigDecimal available
) {}
