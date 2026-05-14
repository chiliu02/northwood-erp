package com.northwood.inventory.domain;

/** Whether quantities are tracked at warehouse level for this SKU. */
public enum StockTrackingMode {
    TRACKED("tracked"),
    NOT_TRACKED("not_tracked");

    private final String dbValue;

    StockTrackingMode(String dbValue) { this.dbValue = dbValue; }

    public String dbValue() { return dbValue; }

    public static StockTrackingMode fromDb(String value) {
        for (StockTrackingMode m : values()) {
            if (m.dbValue.equals(value)) return m;
        }
        throw new IllegalArgumentException("Unknown stock_tracking_mode: " + value);
    }
}
