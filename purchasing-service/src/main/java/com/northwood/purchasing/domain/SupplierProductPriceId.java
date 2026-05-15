package com.northwood.purchasing.domain;

import java.util.Objects;
import java.util.UUID;

public record SupplierProductPriceId(UUID value) {

    public SupplierProductPriceId {
        Objects.requireNonNull(value, "value");
    }

    public static SupplierProductPriceId of(UUID value) {
        return new SupplierProductPriceId(value);
    }

    public static SupplierProductPriceId newId() {
        return new SupplierProductPriceId(UUID.randomUUID());
    }
}
