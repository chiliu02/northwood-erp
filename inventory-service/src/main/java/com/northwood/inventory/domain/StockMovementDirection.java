package com.northwood.inventory.domain;

import com.northwood.shared.domain.Assert;
/**
 * Mirrors the schema CHECK on {@code inventory.stock_movement.direction}.
 * The string values must match the database literals exactly.
 *
 * <p>Top-level in {@code inventory.domain} for the same reason as
 * {@link StockMovementType} — {@code stock_movement} has no aggregate to
 * nest on.
 */
public enum StockMovementDirection {
    IN("in"),
    OUT("out");

    private final String dbValue;

    StockMovementDirection(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    public static StockMovementDirection fromDb(String value) {
        for (StockMovementDirection d : values()) {
            if (d.dbValue.equals(value)) return d;
        }
        throw Assert.unknownValue("stock_movement direction", value);
    }
}
