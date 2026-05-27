package com.northwood.inventory.domain;

import java.util.List;
import java.util.Optional;

public interface StockAdjustmentRepository {

    Optional<StockAdjustment> findById(StockAdjustmentId id);

    /** All stock-adjustment headers, most recent first. List-view endpoint. */
    List<StockAdjustment> findAll();

    /** Insert + emit pending events to the outbox in the same transaction. */
    void save(StockAdjustment adjustment);
}
