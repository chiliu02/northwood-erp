package com.northwood.purchasing.domain;

import java.util.UUID;

public record PurchaseOrderId(UUID value) {

    public static PurchaseOrderId newId() {
        return new PurchaseOrderId(UUID.randomUUID());
    }

    public static PurchaseOrderId of(UUID value) {
        return new PurchaseOrderId(value);
    }
}
