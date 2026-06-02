package com.northwood.product.domain;

import com.northwood.shared.domain.Assert;
/**
 * Replenishment strategy — how a SKU's supply is triggered. Orthogonal to the
 * make-vs-buy axis ({@code is_manufactured} / {@code is_purchased}): the four
 * operator-facing modes (make-to-stock, make-to-order, buy-to-stock,
 * buy-to-order) are the cartesian product of the two axes, a <em>derived
 * view</em> — <strong>not</strong> a stored 4-value enum (that would duplicate
 * make-vs-buy and lose the "both" / vertically-integrated case). Mirrors the
 * schema CHECK on {@code product.product.replenishment_strategy}; the string
 * values must match the database literals exactly.
 *
 * <ul>
 *   <li>{@code to_stock} — supply is driven by stocking policy (reorder point);
 *       the default and, today, the only mode in use (REQ-INV-090).</li>
 *   <li>{@code to_order} — supply is order-pegged: a sales-order line raises
 *       dedicated supply earmarked to that line rather than drawing from /
 *       building to the shared pool (REQ-PROD-022 / REQ-INV-093).</li>
 * </ul>
 *
 * <p>Hosted in {@code product-events} (not {@code product-service}) because it
 * is the cross-service contract carried on the wire format of
 * {@link com.northwood.product.domain.events.ReplenishmentStrategyChanged} —
 * sales reads it in the fulfilment saga to choose the stock vs order-pegged
 * path. Same pattern as {@link ValuationClass} and {@link ProductType}.
 */
public enum ReplenishmentStrategy {
    TO_STOCK("to_stock"),
    TO_ORDER("to_order");

    private final String dbValue;

    ReplenishmentStrategy(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    public static ReplenishmentStrategy fromDb(String value) {
        for (ReplenishmentStrategy s : values()) {
            if (s.dbValue.equals(value)) return s;
        }
        throw Assert.unknownValue("replenishment_strategy", value);
    }
}
