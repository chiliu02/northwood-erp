package com.northwood.inventory.domain;

import com.northwood.shared.domain.Assert;
/**
 * Mirrors the schema CHECK on {@code inventory.stock_movement.movement_type}.
 * The string values must match the database literals exactly.
 *
 * <p>Top-level in {@code inventory.domain} rather than nested on an aggregate:
 * {@code stock_movement} is an append-only audit row written by
 * {@link com.northwood.inventory.application.StockMovementWriter}, not an
 * aggregate root with a {@code *Repository}. The enum-vs-aggregate framing
 * in {@code docs/conventions.md} → *Aggregate enumerated fields* still
 * applies; the host here is the package because there's no aggregate to nest
 * on.
 */
public enum StockMovementType {
    PURCHASE_RECEIPT("purchase_receipt"),
    SALES_SHIPMENT("sales_shipment"),
    /** Schema-prep — not currently produced by Java. */
    MATERIAL_ISSUE("material_issue"),
    FINISHED_GOODS_RECEIPT("finished_goods_receipt"),
    STOCK_ADJUSTMENT_IN("stock_adjustment_in"),
    STOCK_ADJUSTMENT_OUT("stock_adjustment_out"),
    /** Schema-prep — not currently produced by Java. */
    RESERVATION_RELEASE("reservation_release");

    private final String code;

    StockMovementType(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static StockMovementType fromCode(String value) {
        for (StockMovementType t : values()) {
            if (t.code.equals(value)) return t;
        }
        throw Assert.unknownValue("stock_movement movement_type", value);
    }
}
