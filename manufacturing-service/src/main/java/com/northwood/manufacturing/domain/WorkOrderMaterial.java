package com.northwood.manufacturing.domain;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * One material requirement on a work order, snapshotted from {@code bom_line}
 * at release time. {@code required_quantity} = bom_line.quantity_per_finished_unit *
 * work_order.planned_quantity * (1 + scrap_factor_percent/100).
 */
public final class WorkOrderMaterial {

    private final UUID id;
    private final UUID componentProductId;
    private final String componentSku;
    private final String componentName;
    private final BigDecimal requiredQuantity;
    private final BigDecimal unitCost;
    private final String status;

    public WorkOrderMaterial(
        UUID id,
        UUID componentProductId,
        String componentSku,
        String componentName,
        BigDecimal requiredQuantity,
        BigDecimal unitCost,
        String status
    ) {
        if (requiredQuantity.signum() < 0) {
            throw new IllegalArgumentException("requiredQuantity must be >= 0");
        }
        this.id = Objects.requireNonNull(id);
        this.componentProductId = Objects.requireNonNull(componentProductId);
        this.componentSku = Objects.requireNonNull(componentSku);
        this.componentName = Objects.requireNonNull(componentName);
        this.requiredQuantity = requiredQuantity;
        this.unitCost = unitCost == null ? BigDecimal.ZERO : unitCost;
        this.status = status;
    }

    public UUID id()                     { return id; }
    public UUID componentProductId()     { return componentProductId; }
    public String componentSku()         { return componentSku; }
    public String componentName()        { return componentName; }
    public BigDecimal requiredQuantity() { return requiredQuantity; }
    public BigDecimal unitCost()         { return unitCost; }
    public String status()               { return status; }
}
