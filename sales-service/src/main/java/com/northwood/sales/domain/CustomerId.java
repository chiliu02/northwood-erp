package com.northwood.sales.domain;

import java.util.Objects;
import java.util.UUID;

/** Strongly-typed customer identifier. */
public record CustomerId(UUID value) {
    public CustomerId {
        Objects.requireNonNull(value, "value");
    }

    public static CustomerId newId() {
        return new CustomerId(UUID.randomUUID());
    }

    public static CustomerId of(UUID value) {
        return new CustomerId(value);
    }
}
