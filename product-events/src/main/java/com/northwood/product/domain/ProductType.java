package com.northwood.product.domain;

import com.northwood.shared.domain.Assert;
/**
 * Mirrors the schema's product_type CHECK constraint. The string values must
 * match the database literals exactly.
 *
 * <p>Hosted in {@code product-events} (not {@code product-service}) because
 * it is the wire-format value carried on {@code ProductCreated.productType()}
 * — consuming services (manufacturing, finance) compare against these
 * values and need the same enum source.
 */
public enum ProductType {
    RAW_MATERIAL("raw_material"),
    FINISHED_GOOD("finished_good"),
    SEMI_FINISHED_GOOD("semi_finished_good"),
    SERVICE("service");

    private final String dbValue;

    ProductType(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    public static ProductType fromDb(String value) {
        for (ProductType t : values()) {
            if (t.dbValue.equals(value)) return t;
        }
        throw Assert.unknownValue("product_type", value);
    }
}
