package com.northwood.manufacturing.application.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record ReleaseCommand(
    String workOrderNumber,
    UUID salesOrderHeaderId,
    UUID salesOrderLineId,
    UUID parentWorkOrderId,
    UUID finishedProductId,
    String finishedProductSku,
    String finishedProductName,
    BigDecimal plannedQuantity
) {}
