package com.northwood.manufacturing.domain;

import java.util.Objects;
import java.util.UUID;

public record RoutingId(UUID value) {
    public RoutingId {
        Objects.requireNonNull(value, "value");
    }

    public static RoutingId of(UUID value) {
        return new RoutingId(value);
    }
}
