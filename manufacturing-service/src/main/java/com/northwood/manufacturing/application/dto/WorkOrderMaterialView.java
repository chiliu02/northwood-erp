package com.northwood.manufacturing.application.dto;

import com.northwood.manufacturing.domain.WorkOrderMaterial;
import java.math.BigDecimal;
import java.util.UUID;

/** Read-side projection of {@link WorkOrderMaterial} for the wire layer. */
public record WorkOrderMaterialView(
    UUID id,
    UUID componentProductId,
    String componentSku,
    String componentName,
    BigDecimal requiredQuantity,
    BigDecimal unitCost,
    String status
) {
    public static WorkOrderMaterialView from(WorkOrderMaterial m) {
        return new WorkOrderMaterialView(
            m.id(), m.componentProductId(), m.componentSku(), m.componentName(),
            m.requiredQuantity(), m.unitCost(), m.status()
        );
    }
}
