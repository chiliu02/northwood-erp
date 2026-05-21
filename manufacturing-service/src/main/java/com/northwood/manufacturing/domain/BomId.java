package com.northwood.manufacturing.domain;

import com.northwood.shared.domain.Assert;
import java.util.UUID;

public record BomId(UUID value) {

    public BomId {
        Assert.notNull(value, "value");
    }

    public static BomId of(UUID value) {
        return new BomId(value);
    }

    public static BomId newId() {
        return new BomId(UUID.randomUUID());
    }
}
