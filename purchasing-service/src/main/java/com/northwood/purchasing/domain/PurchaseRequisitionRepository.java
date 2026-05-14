package com.northwood.purchasing.domain;

import java.util.Optional;

public interface PurchaseRequisitionRepository {

    Optional<PurchaseRequisition> findById(PurchaseRequisitionId id);

    /** All requisitions, newest activity first. Used by the operational UI list view. */
    java.util.List<PurchaseRequisition> findAll();

    /** Insert + emit pending events to the outbox in the same transaction. */
    void save(PurchaseRequisition requisition);
}
