package com.northwood.inventory.domain.replenishment;

import java.util.UUID;

public record ReplenishmentRequestId(UUID value) {

    public static ReplenishmentRequestId newId() {
        return new ReplenishmentRequestId(UUID.randomUUID());
    }

    public static ReplenishmentRequestId of(UUID value) {
        return new ReplenishmentRequestId(value);
    }
}
