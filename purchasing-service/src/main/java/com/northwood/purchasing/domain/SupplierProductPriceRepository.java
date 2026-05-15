package com.northwood.purchasing.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for the {@link SupplierProductPrice} aggregate root — the data
 * of record for the per-supplier price list. Promoted from a row-level write
 * port 2026-05-16 (§2.17).
 *
 * <p>{@link #save} drains the aggregate's {@code pendingEvents} to the outbox
 * in the same transaction; emit events on the aggregate, never hand-rolled in
 * the application service.
 */
public interface SupplierProductPriceRepository {

    /** Look up by the natural key. Empty when no row exists for the tuple. */
    Optional<SupplierProductPrice> findByKey(UUID supplierId, UUID productId, String currencyCode);

    /**
     * Persist a new or reconstituted aggregate. New aggregates (version 0) are
     * INSERTed; existing aggregates are UPDATEd with optimistic-concurrency on
     * {@code version}. Drains {@code pullPendingEvents()} to the outbox in the
     * same transaction.
     */
    void save(SupplierProductPrice price);

    /** All rows for a supplier, ordered by {@code product_id}. Read-side listing. */
    List<SupplierProductPrice> listForSupplier(UUID supplierId);
}
