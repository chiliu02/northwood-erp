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

    private final String code;

    StockMovementDirection(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static StockMovementDirection fromCode(String value) {
        for (StockMovementDirection d : values()) {
            if (d.code.equals(value)) return d;
        }
        throw Assert.unknownValue("stock_movement direction", value);
    }
}
