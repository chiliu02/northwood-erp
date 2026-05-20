package com.northwood.manufacturing.domain;

import com.northwood.shared.domain.Assert;
import java.util.UUID;

public record RoutingId(UUID value) {
    public RoutingId {
        Assert.notNull(value, "value");
    }

    public static RoutingId of(UUID value) {
        return new RoutingId(value);
    }
}
