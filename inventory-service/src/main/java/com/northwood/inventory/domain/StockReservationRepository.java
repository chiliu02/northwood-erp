package com.northwood.inventory.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Port for {@link StockReservation} writes plus the lifecycle queries the
 * release / cancel-prior flows need (the aggregate is currently
 * write-once, immutable post-creation; status transitions are projected
 * directly onto the row).
 */
public interface StockReservationRepository {

    /** Insert a fresh reservation header + lines. Drains pending events to outbox. */
    void save(StockReservation reservation);

    Optional<StockReservation> findBySalesOrderId(UUID salesOrderId);

    /**
     * Find the live reservation header id (status in {@code reserved} /
     * {@code partially_reserved}) for the given sales order, if any.
     * Empty when no live reservation exists.
     */
    Optional<UUID> findActiveHeaderIdForSalesOrder(UUID salesOrderHeaderId);

    /** Same as {@link #findActiveHeaderIdForSalesOrder} for the work-order side. */
    Optional<UUID> findActiveHeaderIdForWorkOrder(UUID workOrderId);

    /**
     * Find any reservation header id for this work order, regardless of
     * status. Used by the retry-cancel-then-recreate flow which needs to
     * find the prior row (in any state) so it can roll back its
     * {@code stock_balance.reserved_quantity} bumps and delete it before
     * the schema's UNIQUE on {@code work_order_id} blocks the new INSERT.
     */
    Optional<UUID> findAnyHeaderIdForWorkOrder(UUID workOrderId);

    /** {@code warehouse_id} for an existing reservation header. */
    Optional<UUID> findWarehouseIdForHeader(UUID stockReservationHeaderId);

    /**
     * Per-line reservation snapshots needed to roll back the matching
     * {@code stock_balance.reserved_quantity} bumps. Returns lines with
     * {@code reserved_quantity > 0} only.
     */
    List<ReservedLineSnapshot> findReservedLines(UUID stockReservationHeaderId);

    /** Flip status to {@code 'released'} + bump version. */
    void markReleased(UUID stockReservationHeaderId);

    /**
     * Hard-delete the reservation header + lines. Used by the retry path
     * before re-inserting a fresh reservation (the schema's UNIQUE on
     * {@code work_order_id} would reject a second INSERT otherwise).
     */
    void deleteHeaderAndLines(UUID stockReservationHeaderId);

    record ReservedLineSnapshot(UUID productId, BigDecimal reservedQuantity) {}
}
