package com.northwood.purchasing.domain;

import com.northwood.shared.domain.Assert;
import java.util.UUID;

public record SupplierProductPriceId(UUID value) {

    public SupplierProductPriceId {
        Assert.notNull(value, "value");
    }

    public static SupplierProductPriceId of(UUID value) {
        return new SupplierProductPriceId(value);
    }

    public static SupplierProductPriceId newId() {
        return new SupplierProductPriceId(UUID.randomUUID());
    }
}
