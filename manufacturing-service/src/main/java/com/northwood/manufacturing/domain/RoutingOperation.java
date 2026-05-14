package com.northwood.manufacturing.domain;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

public final class RoutingOperation {

    private final UUID id;
    private final int operationSequence;
    private final String operationCode;
    private final String description;
    private final UUID workCenterId;
    private final BigDecimal plannedSetupMinutes;
    private final BigDecimal plannedRunMinutes;

    public RoutingOperation(
        UUID id,
        int operationSequence,
        String operationCode,
        String description,
        UUID workCenterId,
        BigDecimal plannedSetupMinutes,
        BigDecimal plannedRunMinutes
    ) {
        this.id = Objects.requireNonNull(id);
        this.operationSequence = operationSequence;
        this.operationCode = Objects.requireNonNull(operationCode);
        this.description = description;
        this.workCenterId = Objects.requireNonNull(workCenterId);
        this.plannedSetupMinutes = plannedSetupMinutes == null ? BigDecimal.ZERO : plannedSetupMinutes;
        this.plannedRunMinutes = Objects.requireNonNull(plannedRunMinutes);
    }

    public UUID id()                        { return id; }
    public int operationSequence()          { return operationSequence; }
    public String operationCode()           { return operationCode; }
    public String description()             { return description; }
    public UUID workCenterId()              { return workCenterId; }
    public BigDecimal plannedSetupMinutes() { return plannedSetupMinutes; }
    public BigDecimal plannedRunMinutes()   { return plannedRunMinutes; }
}
