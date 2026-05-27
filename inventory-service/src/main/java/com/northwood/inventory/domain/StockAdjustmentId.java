package com.northwood.inventory.domain;

import java.util.UUID;

public record StockAdjustmentId(UUID value) {

    public static StockAdjustmentId newId() {
        return new StockAdjustmentId(UUID.randomUUID());
    }

    public static StockAdjustmentId of(UUID value) {
        return new StockAdjustmentId(value);
    }
}
