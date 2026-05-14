package com.northwood.reporting.application.inbox;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Maintains {@code reporting.production_planning_board}. One row per work
 * order (top-level and sub-assembly children both surface), stitched
 * together from manufacturing + inventory events.
 *
 * <p>Order-tolerant by design: every method uses
 * {@code INSERT ... ON CONFLICT DO UPDATE}.
 *
 * <p>Application-side port; JDBC implementation lives in
 * {@code infrastructure/persistence/JdbcProductionPlanningProjection}.
 */
public interface ProductionPlanningProjection {

    void createFromWorkOrder(
        UUID workOrderId,
        String workOrderNumber,
        UUID salesOrderHeaderId,
        UUID finishedProductId,
        String finishedProductSku,
        String finishedProductName,
        BigDecimal plannedQuantity,
        Instant occurredAt);

    void recordOperationCompleted(UUID workOrderId, Instant occurredAt);

    void recordWorkOrderCompleted(
        UUID workOrderId,
        BigDecimal completedQuantity,
        Instant occurredAt);

    /**
     * §3.8: flip the production-board row to {@code 'cancelled'} on
     * {@code manufacturing.WorkOrderCancelled}.
     */
    void recordWorkOrderCancelled(UUID workOrderId, Instant occurredAt);

    void recordRawMaterialsReserved(
        UUID workOrderId,
        String reservationStatus,
        Instant occurredAt);

    /** §3.5: write the new priority onto the row. */
    void recordPriorityChanged(UUID workOrderId, String priority, Instant occurredAt);

    void recordShortageDetected(
        UUID workOrderId,
        int shortageCount,
        String shortageSummary,
        Instant occurredAt);

    /**
     * §2.1: write the latest open-PO count for a work order onto its
     * planning-board row. Driven by reporting handlers for shortage-driven
     * PO lifecycle events (created / goods received / paid). The count is
     * computed by the tracking projection ({@code countOpenForWorkOrder});
     * this method is the dumb sink. Order-tolerant via INSERT ... ON CONFLICT
     * so a PO event for a WO whose board row hasn't been created yet leaves
     * a stub that the late {@code WorkOrderCreated} upsert will backfill
     * without zeroing the count.
     */
    void setOpenPoCount(UUID workOrderId, int count, Instant occurredAt);
}
