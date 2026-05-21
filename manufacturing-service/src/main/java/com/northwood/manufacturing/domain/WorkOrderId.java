package com.northwood.manufacturing.domain;

import com.northwood.shared.domain.Assert;
import java.util.UUID;

public record WorkOrderId(UUID value) {
    public WorkOrderId {
        Assert.notNull(value, "value");
    }

    public static WorkOrderId newId() {
        return new WorkOrderId(UUID.randomUUID());
    }

    public static WorkOrderId of(UUID value) {
        return new WorkOrderId(value);
    }
}
