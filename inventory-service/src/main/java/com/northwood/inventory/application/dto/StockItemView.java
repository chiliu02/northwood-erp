package com.northwood.inventory.application.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Read-side projection of an {@code inventory.stock_item} row for the wire
 * layer. Returned directly by the {@code StockItemQueryPort} (no domain
 * aggregate sits in between — see §2.22 demotion). The {@code version}
 * column is exposed for wire compatibility but no inventory-side writer
 * bumps it today; it survives as defensive optimistic-concurrency capacity
 * for a future inventory-originated stock-fact slice.
 *
 * <p>{@code onHand} / {@code reserved} / {@code available} are joined from
 * {@code inventory.stock_balance}, summed across warehouses per product
 * (zero when no balance row exists). Read-side only — the join lives in the
 * query, not in any projection write.
 */
public record StockItemView(
    UUID stockItemId,
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
    BigDecimal available,
    long version
) {}
