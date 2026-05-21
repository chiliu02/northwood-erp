package com.northwood.inventory.domain;

import com.northwood.shared.domain.Assert;
import java.util.UUID;

public record StockReservationId(UUID value) {
    public StockReservationId {
        Assert.notNull(value, "value");
    }

    public static StockReservationId newId() {
        return new StockReservationId(UUID.randomUUID());
    }

    public static StockReservationId of(UUID value) {
        return new StockReservationId(value);
    }
}
