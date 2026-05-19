package com.northwood.inventory.domain;

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
    /** Schema-prep — not currently produced by Java. */
    STOCK_ADJUSTMENT_IN("stock_adjustment_in"),
    /** Schema-prep — not currently produced by Java. */
    STOCK_ADJUSTMENT_OUT("stock_adjustment_out"),
    /** Schema-prep — not currently produced by Java. */
    RESERVATION_RELEASE("reservation_release");

    private final String dbValue;

    StockMovementType(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    public static StockMovementType fromDb(String value) {
        for (StockMovementType t : values()) {
            if (t.dbValue.equals(value)) return t;
        }
        throw new IllegalArgumentException("Unknown stock_movement movement_type: " + value);
    }
}
