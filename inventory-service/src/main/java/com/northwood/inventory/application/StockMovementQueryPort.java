package com.northwood.inventory.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * CQRS read port for the stock movements list view. Stock movement rows
 * are append-only (written by {@code StockMovementWriter}) — the list
 * is observation-only audit. JDBC adapter at
 * {@code infrastructure/persistence/JdbcStockMovementQueryPort}.
 */
public interface StockMovementQueryPort {

    /** Most recent first; capped at {@code limit} rows. */
    List<MovementRow> listRecent(int limit);

    record MovementRow(
        UUID stockMovementId,
        UUID warehouseId,
        UUID productId,
        String productSku,
        String productName,
        String movementType,
        String direction,
        BigDecimal quantity,
        BigDecimal unitCost,
        BigDecimal totalCost,
        String sourceType,
        UUID sourceId,
        Instant movementDate
    ) {}
}
