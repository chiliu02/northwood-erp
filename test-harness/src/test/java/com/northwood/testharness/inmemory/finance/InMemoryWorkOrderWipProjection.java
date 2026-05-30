package com.northwood.testharness.inmemory.finance;

import com.northwood.finance.application.inbox.WorkOrderWipProjection;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * In-memory {@link WorkOrderWipProjection} double mirroring the idempotency
 * semantics of the JDBC upserts: charge-once / complete-once gates, running WIP
 * value per work order. Test accessors expose the value + completion flag.
 */
public final class InMemoryWorkOrderWipProjection implements WorkOrderWipProjection {

    private static final class Row {
        private UUID finishedProductId;
        private BigDecimal wipValue = BigDecimal.ZERO;
        private boolean materialsCharged;
        private boolean completed;
    }

    private final Map<UUID, Row> byWorkOrderId = new HashMap<>();

    @Override
    public boolean chargeRawMaterials(UUID workOrderId, BigDecimal amount) {
        Row r = byWorkOrderId.computeIfAbsent(workOrderId, k -> new Row());
        if (r.materialsCharged) {
            return false;
        }
        r.materialsCharged = true;
        r.wipValue = r.wipValue.add(amount);
        return true;
    }

    @Override
    public void rollInSubAssemblies(UUID workOrderId, BigDecimal amount) {
        Row r = byWorkOrderId.computeIfAbsent(workOrderId, k -> new Row());
        r.wipValue = r.wipValue.add(amount);
    }

    @Override
    public boolean markCompleted(UUID workOrderId, UUID finishedProductId) {
        Row r = byWorkOrderId.computeIfAbsent(workOrderId, k -> new Row());
        if (r.completed) {
            return false;
        }
        r.completed = true;
        if (r.finishedProductId == null) {
            r.finishedProductId = finishedProductId;
        }
        return true;
    }

    public BigDecimal wipValue(UUID workOrderId) {
        Row r = byWorkOrderId.get(workOrderId);
        return r == null ? null : r.wipValue;
    }

    public boolean isCompleted(UUID workOrderId) {
        Row r = byWorkOrderId.get(workOrderId);
        return r != null && r.completed;
    }
}
