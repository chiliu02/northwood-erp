package com.northwood.inventory.domain.replenishment;

import java.util.Optional;
import java.util.UUID;

/**
 * §2.35 Slice B: aggregate repository for {@link ReplenishmentRequest}.
 *
 * <p>Slice E adds {@code findByDispatchedAggregateId} +
 * {@code findByLinkedPurchaseOrderId} for the close-the-loop handlers; this
 * slice ships {@link #save} + {@link #findById} only.
 */
public interface ReplenishmentRequestRepository {

    Optional<ReplenishmentRequest> findById(ReplenishmentRequestId id);

    /**
     * Insert + drain pending events to the outbox in the same transaction.
     *
     * <p>The one-open-per-(product, warehouse) invariant is enforced by a
     * partial unique index in PostgreSQL; on conflict, the underlying
     * {@link org.springframework.dao.DuplicateKeyException} is propagated so
     * the caller (typically {@code ReplenishmentDetectionService}) can
     * translate it into a no-op (matching the semantic intent of "ignore the
     * second trigger while the first is still open").
     */
    void save(ReplenishmentRequest replenishmentRequest);
}
