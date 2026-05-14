package com.northwood.testharness.inmemory.reporting;

import com.northwood.reporting.application.inbox.ProductionPlanningProjection;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * In-memory {@link ProductionPlanningProjection}. Stores the most-recent
 * priority/material-status/etc. per work order so harness tests can assert
 * the projected state.
 */
public final class InMemoryProductionPlanningProjection implements ProductionPlanningProjection {

    private static final class Row {
        String workOrderNumber;
        String priority;
        String materialStatus;
        String status;
        BigDecimal plannedQuantity;
        BigDecimal completedQuantity;
        int openPoCount;
        Instant lastEventAt;
    }

    private final Map<UUID, Row> rows = new HashMap<>();

    private Row row(UUID workOrderId) {
        return rows.computeIfAbsent(workOrderId, k -> new Row());
    }

    public Optional<String> priorityOf(UUID workOrderId) {
        Row r = rows.get(workOrderId);
        return r == null ? Optional.empty() : Optional.ofNullable(r.priority);
    }

    public Optional<String> statusOf(UUID workOrderId) {
        Row r = rows.get(workOrderId);
        return r == null ? Optional.empty() : Optional.ofNullable(r.status);
    }

    @Override
    public void createFromWorkOrder(UUID workOrderId, String workOrderNumber, UUID salesOrderHeaderId,
                                    UUID finishedProductId, String finishedProductSku, String finishedProductName,
                                    BigDecimal plannedQuantity, Instant occurredAt) {
        Row r = row(workOrderId);
        r.workOrderNumber = workOrderNumber;
        r.plannedQuantity = plannedQuantity;
        r.lastEventAt = occurredAt;
    }

    @Override
    public void recordOperationCompleted(UUID workOrderId, Instant occurredAt) {
        row(workOrderId).lastEventAt = occurredAt;
    }

    @Override
    public void recordWorkOrderCompleted(UUID workOrderId, BigDecimal completedQuantity, Instant occurredAt) {
        Row r = row(workOrderId);
        r.completedQuantity = completedQuantity;
        r.status = "completed";
        r.lastEventAt = occurredAt;
    }

    @Override
    public void recordWorkOrderCancelled(UUID workOrderId, Instant occurredAt) {
        Row r = row(workOrderId);
        r.status = "cancelled";
        r.lastEventAt = occurredAt;
    }

    @Override
    public void recordRawMaterialsReserved(UUID workOrderId, String reservationStatus, Instant occurredAt) {
        Row r = row(workOrderId);
        r.materialStatus = reservationStatus;
        r.lastEventAt = occurredAt;
    }

    @Override
    public void recordPriorityChanged(UUID workOrderId, String priority, Instant occurredAt) {
        Row r = row(workOrderId);
        r.priority = priority;
        r.lastEventAt = occurredAt;
    }

    @Override
    public void recordShortageDetected(UUID workOrderId, int shortageCount, String shortageSummary, Instant occurredAt) {
        Row r = row(workOrderId);
        r.lastEventAt = occurredAt;
    }

    @Override
    public void setOpenPoCount(UUID workOrderId, int count, Instant occurredAt) {
        Row r = row(workOrderId);
        r.openPoCount = count;
        r.lastEventAt = occurredAt;
    }
}
