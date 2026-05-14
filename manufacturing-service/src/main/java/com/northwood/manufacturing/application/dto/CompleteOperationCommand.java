package com.northwood.manufacturing.application.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record CompleteOperationCommand(
    UUID workOrderId,
    int operationSequence,
    BigDecimal actualMinutes
) {
}
