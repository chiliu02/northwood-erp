package com.northwood.inventory.domain;

import java.util.UUID;

public record ShipmentId(UUID value) {

    public static ShipmentId newId() {
        return new ShipmentId(UUID.randomUUID());
    }

    public static ShipmentId of(UUID value) {
        return new ShipmentId(value);
    }
}
