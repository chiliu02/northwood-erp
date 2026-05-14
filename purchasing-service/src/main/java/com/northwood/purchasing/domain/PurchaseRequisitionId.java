package com.northwood.purchasing.domain;

import java.util.UUID;

public record PurchaseRequisitionId(UUID value) {

    public static PurchaseRequisitionId newId() {
        return new PurchaseRequisitionId(UUID.randomUUID());
    }

    public static PurchaseRequisitionId of(UUID value) {
        return new PurchaseRequisitionId(value);
    }
}
