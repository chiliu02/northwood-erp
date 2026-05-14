package com.northwood.manufacturing.domain;

import java.util.Objects;
import java.util.UUID;

public record WorkOrderId(UUID value) {
    public WorkOrderId {
        Objects.requireNonNull(value, "value");
    }

    public static WorkOrderId newId() {
        return new WorkOrderId(UUID.randomUUID());
    }

    public static WorkOrderId of(UUID value) {
        return new WorkOrderId(value);
    }
}
