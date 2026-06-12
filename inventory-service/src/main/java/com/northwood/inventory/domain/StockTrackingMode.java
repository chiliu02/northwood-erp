package com.northwood.inventory.domain;

import com.northwood.shared.domain.Assert;
/** Whether quantities are tracked at warehouse level for this SKU. */
public enum StockTrackingMode {
    TRACKED("tracked"),
    NOT_TRACKED("not_tracked");

    private final String code;

    StockTrackingMode(String code) { this.code = code; }

    public String code() { return code; }

    public static StockTrackingMode fromCode(String value) {
        for (StockTrackingMode m : values()) {
            if (m.code.equals(value)) return m;
        }
        throw Assert.unknownValue("stock_tracking_mode", value);
    }
}
