package com.northwood.inventory.domain;

import java.util.List;
import java.util.Optional;

public interface GoodsReceiptRepository {

    Optional<GoodsReceipt> findById(GoodsReceiptId id);

    /**
     * All goods-receipt headers (no lines), most recent first. List-view
     * endpoint only — detail fetch via {@link #findById} loads lines.
     */
    List<GoodsReceipt> findAllHeaders();

    /** Insert + emit pending events to the outbox in the same transaction. */
    void save(GoodsReceipt receipt);
}
