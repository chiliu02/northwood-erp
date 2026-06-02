package com.northwood.manufacturing.application.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Command to release a stock-replenishment work order. Built
 * by {@code manufacturing.ReplenishmentRequestedHandler} from an
 * {@code inventory.ReplenishmentRequested} event with
 * {@code targetService = "manufacturing"}. Carries {@code replenishmentRequestId}
 * (the request being dispatched) — there is no sales-order binding, since
 * replenishment work orders are make-to-stock.
 *
 * <p>{@code sourceSalesOrderHeaderId} is the originating sales order when this
 * replenishment was triggered by a {@code sales_order_shortage} (null otherwise)
 * — threaded onto {@code WorkOrderCreated} for reporting's SO↔WO link, not a
 * manufacturing-side binding.
 */
public record ReleaseForReplenishmentCommand(
    String workOrderNumber,
    UUID replenishmentRequestId,
    UUID finishedProductId,
    String finishedProductSku,
    String finishedProductName,
    BigDecimal plannedQuantity,
    UUID sourceSalesOrderHeaderId
) {}
