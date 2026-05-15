package com.northwood.manufacturing.domain;

import java.util.Objects;
import java.util.UUID;

public record BomLineId(UUID value) {

    public BomLineId {
        Objects.requireNonNull(value, "value");
    }

    public static BomLineId of(UUID value) {
        return new BomLineId(value);
    }

    public static BomLineId newId() {
        return new BomLineId(UUID.randomUUID());
    }
}
