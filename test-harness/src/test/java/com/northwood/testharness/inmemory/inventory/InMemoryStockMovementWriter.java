package com.northwood.testharness.inmemory.inventory;

import com.northwood.inventory.application.StockMovementWriter;
import com.northwood.inventory.domain.StockMovementDirection;
import com.northwood.inventory.domain.StockMovementType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * In-memory {@link StockMovementWriter}. Append-only log of audit rows;
 * tests assert by walking the {@link #all()} list.
 */
public final class InMemoryStockMovementWriter implements StockMovementWriter {

    public record Row(
        UUID warehouseId,
        UUID productId,
        String productSku,
        String productName,
        StockMovementType movementType,
        StockMovementDirection direction,
        BigDecimal quantity,
        BigDecimal unitCost,
        String sourceType,
        UUID sourceId,
        UUID sourceLineId
    ) {}

    private final List<Row> rows = new ArrayList<>();

    @Override
    public void record(
        UUID warehouseId, UUID productId,
        String productSku, String productName,
        StockMovementType movementType, StockMovementDirection direction,
        BigDecimal quantity, BigDecimal unitCost,
        String sourceType, UUID sourceId, UUID sourceLineId
    ) {
        rows.add(new Row(
            warehouseId, productId, productSku, productName,
            movementType, direction, quantity, unitCost,
            sourceType, sourceId, sourceLineId
        ));
    }

    public List<Row> all() {
        return new ArrayList<>(rows);
    }
}
