package com.northwood.sales.domain;

import com.northwood.shared.domain.Assert;
import java.util.UUID;

public record SalesOrderId(UUID value) {
    public SalesOrderId {
        Assert.notNull(value, "value");
    }

    public static SalesOrderId newId() {
        return new SalesOrderId(UUID.randomUUID());
    }

    public static SalesOrderId of(UUID value) {
        return new SalesOrderId(value);
    }
}
