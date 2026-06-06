package com.northwood.sales.application.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Override the unit price of an existing sales-order line (quantity unchanged).
 * A pricing decision — gated to {@code sales_manager} at the API. The price is
 * currency-checked against the catalog the same way placement validates an
 * override. {@code expectedVersion} is the optimistic-concurrency token (null
 * skips the staleness check).
 */
public record ChangeOrderLineUnitPriceCommand(
    UUID salesOrderHeaderId,
    UUID salesOrderLineId,
    Long expectedVersion,
    BigDecimal unitPrice
) {}
