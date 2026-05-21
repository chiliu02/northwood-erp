package com.northwood.product.domain;

import com.northwood.shared.domain.Assert;
import java.util.UUID;

/** Strongly-typed product identifier. */
public record ProductId(UUID value) {
    public ProductId {
        Assert.notNull(value, "value");
    }

    public static ProductId newId() {
        return new ProductId(UUID.randomUUID());
    }

    public static ProductId of(UUID value) {
        return new ProductId(value);
    }
}
