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

    /**
     * Find any reservation header id for this work order, regardless of
     * status. Used by the retry-cancel-then-recreate flow which needs to
     * find the prior row (in any state) so it can roll back its
     * {@code stock_balance.reserved_quantity} bumps and delete it before
     * the schema's UNIQUE on {@code work_order_id} blocks the new INSERT.
     */
    Optional<UUID> findAnyHeaderIdForWorkOrder(UUID workOrderId);

    /**
     * Sibling of {@link #findAnyHeaderIdForWorkOrder} for the sales-order retry
     * case. When a SO saga un-parks from {@code purchasing_requested} and
     * re-emits {@code StockReservationRequested}, inventory needs to drop the
     * prior partial reservation before creating the new one — the schema's UNIQUE
     * on {@code stock_reservation_header.sales_order_header_id} would otherwise
     * reject the second INSERT.
     */
    Optional<UUID> findAnyHeaderIdForSalesOrder(UUID salesOrderHeaderId);

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

    // ------------------------------------------------------------
    // §1G line amendment — per-line incremental reserve / release / delta.
    // ------------------------------------------------------------

    /** The live (non-{@code released}) reservation header id for a sales order, if any. */
    Optional<UUID> findLiveHeaderIdForSalesOrder(UUID salesOrderHeaderId);

    /**
     * The live (non-{@code released}) reservation line for a given sales-order
     * line, correlated via {@code stock_reservation_line.sales_order_line_id}.
     * Empty when the order has no live reservation, or none for that line.
     */
    Optional<AmendableSalesOrderLine> findAmendableSalesOrderLine(UUID salesOrderHeaderId, UUID salesOrderLineId);

    /** Append one line to an existing reservation header (add-line amendment). */
    void appendLine(UUID stockReservationHeaderId, StockReservationLine line);

    /** Update a single reservation line's quantities + status (change / remove amendment). */
    void updateLine(
        UUID stockReservationLineId,
        BigDecimal requestedQuantity,
        BigDecimal reservedQuantity,
        BigDecimal shortageQuantity,
        String status
    );

    /**
     * Recompute a sales-order reservation header's status from its
     * non-{@code released} lines (all reserved → {@code reserved}; any short →
     * {@code partially_reserved}; nothing reserved → {@code failed}) and bump
     * its version.
     */
    void recomputeSalesOrderHeaderStatus(UUID stockReservationHeaderId);

    record ReservedLineSnapshot(UUID productId, BigDecimal reservedQuantity) {}

    /** A live reservation line targeted by a line amendment. */
    record AmendableSalesOrderLine(
        UUID stockReservationHeaderId,
        UUID stockReservationLineId,
        UUID warehouseId,
        UUID productId,
        BigDecimal requestedQuantity,
        BigDecimal reservedQuantity,
        String status
    ) {}
}
