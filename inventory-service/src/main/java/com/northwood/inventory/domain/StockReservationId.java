package com.northwood.inventory.domain;

import java.util.Objects;
import java.util.UUID;

public record StockReservationId(UUID value) {
    public StockReservationId {
        Objects.requireNonNull(value, "value");
    }

    public static StockReservationId newId() {
        return new StockReservationId(UUID.randomUUID());
    }

    public static StockReservationId of(UUID value) {
        return new StockReservationId(value);
    }
}
