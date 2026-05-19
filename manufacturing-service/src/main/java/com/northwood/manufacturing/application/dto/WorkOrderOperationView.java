package com.northwood.manufacturing.application.dto;

import com.northwood.manufacturing.domain.WorkOrderOperation;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Read-side projection of {@link WorkOrderOperation} for the wire layer. */
public record WorkOrderOperationView(
    UUID id,
    int operationSequence,
    String operationCode,
    String description,
    UUID workCenterId,
    BigDecimal plannedSetupMinutes,
    BigDecimal plannedRunMinutes,
    String status,
    BigDecimal actualMinutes,
    Instant startedAt,
    Instant completedAt
) {
    public static WorkOrderOperationView from(WorkOrderOperation o) {
        return new WorkOrderOperationView(
            o.id(), o.operationSequence(), o.operationCode(), o.description(),
            o.workCenterId(), o.plannedSetupMinutes(), o.plannedRunMinutes(),
            o.status().dbValue(), o.actualMinutes(), o.startedAt(), o.completedAt()
        );
    }
}
