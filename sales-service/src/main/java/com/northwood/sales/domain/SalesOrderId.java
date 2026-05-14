package com.northwood.sales.domain;

import java.util.Objects;
import java.util.UUID;

public record SalesOrderId(UUID value) {
    public SalesOrderId {
        Objects.requireNonNull(value, "value");
    }

    public static SalesOrderId newId() {
        return new SalesOrderId(UUID.randomUUID());
    }

    public static SalesOrderId of(UUID value) {
        return new SalesOrderId(value);
    }
}
