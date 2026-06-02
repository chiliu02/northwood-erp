package com.northwood.inventory.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Aggregate repository for {@link ReplenishmentRequest}.
 */
public interface ReplenishmentRequestRepository {

    Optional<ReplenishmentRequest> findById(ReplenishmentRequestId id);

    /**
     * All order-pegged ({@code reason = order_pegged}) requests raised for a
     * sales order, any status (§2.43 Slice E). Used by the cancel/un-peg path:
     * a fulfilled pegged request's reserve is released back to the free pool; an
     * in-flight (requested/dispatched) one is dropped so its eventual completion
     * credits stock to the pool without re-pegging.
     */
    List<ReplenishmentRequest> findOrderPeggedForSalesOrder(UUID salesOrderHeaderId);

    /**
     * Find an open (or dispatched) replenishment by the downstream aggregate it's
     * been dispatched to. Returns empty when no matching row exists — e.g. a
     * {@code WorkOrderManufacturingCompleted} arrives for a WO that wasn't
     * released via the replenishment path. The close-the-loop handler treats
     * empty as "not a replenishment WO; ignore".
     *
     * <p>The lookup is keyed on {@code dispatched_aggregate_id} (not the
     * {@code _kind} column). A UUID collision across the manufacturing and
     * purchasing namespaces is vanishingly improbable; if needed, callers
     * defence-check the returned {@code dispatchedAggregateKind} matches the
     * expected one.
     */
    Optional<ReplenishmentRequest> findByDispatchedAggregateId(UUID dispatchedAggregateId);

    /**
     * Find an open (or dispatched) replenishment by the PO that fulfils it
     * (purchasing path). Stamped during {@code PurchaseOrderCreated} consumption;
     * read during {@code GoodsReceived} consumption to flip the request to
     * {@code fulfilled}.
     */
    Optional<ReplenishmentRequest> findByLinkedPurchaseOrderId(UUID purchaseOrderId);

    /**
     * Insert (when version == 0) or update (when version > 0), plus drain
     * pending events to the outbox in the same transaction.
     *
     * <p>The one-open-per-(product, warehouse) invariant is enforced by a
     * partial unique index in PostgreSQL; on insert conflict, the underlying
     * {@link org.springframework.dao.DuplicateKeyException} is propagated so
     * the caller (typically {@code ReplenishmentDetectionService}) can
     * translate it into a no-op (matching the semantic intent of "ignore the
     * second trigger while the first is still open").
     */
    void save(ReplenishmentRequest replenishmentRequest);
}
