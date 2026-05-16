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
    long version
) {}
