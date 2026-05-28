package com.northwood.manufacturing.application.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * §2.35 Slice C: command to release a stock-replenishment work order. Built
 * by {@code manufacturing.ReplenishmentRequestedHandler} from an
 * {@code inventory.ReplenishmentRequested} event with
 * {@code targetService = "manufacturing"}. Mirrors {@link ReleaseCommand} but
 * with {@code replenishmentRequestId} replacing the sales-order pair.
 */
public record ReleaseForReplenishmentCommand(
    String workOrderNumber,
    UUID replenishmentRequestId,
    UUID finishedProductId,
    String finishedProductSku,
    String finishedProductName,
    BigDecimal plannedQuantity
) {}
