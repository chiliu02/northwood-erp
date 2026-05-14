package com.northwood.purchasing.domain;

import java.util.Optional;

public interface PurchaseOrderRepository {

    Optional<PurchaseOrder> findById(PurchaseOrderId id);

    /** Insert + emit pending events to the outbox in the same transaction. */
    void save(PurchaseOrder purchaseOrder);
}
