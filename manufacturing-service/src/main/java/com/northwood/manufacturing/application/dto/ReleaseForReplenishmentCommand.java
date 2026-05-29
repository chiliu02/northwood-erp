package com.northwood.manufacturing.application.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * §2.35 Slice C: command to release a stock-replenishment work order. Built
 * by {@code manufacturing.ReplenishmentRequestedHandler} from an
 * {@code inventory.ReplenishmentRequested} event with
 * {@code targetService = "manufacturing"}. Carries {@code replenishmentRequestId}
 * (the request being dispatched) — there is no sales-order pair, since
 * replenishment work orders are make-to-stock (§2.37 Slice 3).
 */
public record ReleaseForReplenishmentCommand(
    String workOrderNumber,
    UUID replenishmentRequestId,
    UUID finishedProductId,
    String finishedProductSku,
    String finishedProductName,
    BigDecimal plannedQuantity
) {}
