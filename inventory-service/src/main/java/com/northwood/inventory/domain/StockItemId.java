package com.northwood.inventory.domain;

import java.util.Objects;
import java.util.UUID;

/** Strongly-typed stock-item identifier. */
public record StockItemId(UUID value) {
    public StockItemId {
        Objects.requireNonNull(value, "value");
    }

    public static StockItemId newId() {
        return new StockItemId(UUID.randomUUID());
    }

    public static StockItemId of(UUID value) {
        return new StockItemId(value);
    }
}
