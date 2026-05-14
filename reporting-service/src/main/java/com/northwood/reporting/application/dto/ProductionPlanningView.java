package com.northwood.reporting.application.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ProductionPlanningView(
    UUID workOrderId,
    String workOrderNumber,
    UUID salesOrderHeaderId,
    String orderNumber,
    UUID finishedProductId,
    String finishedProductSku,
    String finishedProductName,
    BigDecimal plannedQuantity,
    BigDecimal completedQuantity,
    String workOrderStatus,
    String materialStatus,
    int shortageMaterialsCount,
    String shortageSummary,
    int openPurchaseOrdersCount,
    LocalDate expectedMaterialAvailableDate,
    LocalDate plannedStartDate,
    LocalDate plannedEndDate,
    String priority,
    Instant updatedAt
) {}