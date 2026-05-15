package com.northwood.manufacturing.domain;

import java.util.Objects;
import java.util.UUID;

public record BomId(UUID value) {

    public BomId {
        Objects.requireNonNull(value, "value");
    }

    public static BomId of(UUID value) {
        return new BomId(value);
    }

    public static BomId newId() {
        return new BomId(UUID.randomUUID());
    }
}
