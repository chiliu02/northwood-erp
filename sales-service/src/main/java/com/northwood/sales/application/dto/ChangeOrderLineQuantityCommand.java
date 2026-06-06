package com.northwood.sales.application.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Change the ordered quantity of an existing sales-order line (price unchanged).
 * {@code expectedVersion} is the optimistic-concurrency token (null skips the
 * staleness check).
 */
public record ChangeOrderLineQuantityCommand(
    UUID salesOrderHeaderId,
    UUID salesOrderLineId,
    Long expectedVersion,
    BigDecimal orderedQuantity
) {}
