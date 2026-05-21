package com.northwood.manufacturing.domain;

import com.northwood.shared.domain.Assert;
import java.util.UUID;

public record BomLineId(UUID value) {

    public BomLineId {
        Assert.notNull(value, "value");
    }

    public static BomLineId of(UUID value) {
        return new BomLineId(value);
    }

    public static BomLineId newId() {
        return new BomLineId(UUID.randomUUID());
    }
}
