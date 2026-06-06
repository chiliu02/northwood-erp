package com.northwood.sales.application.dto;

import java.util.UUID;

/**
 * Remove a line from an existing sales order (soft — the line is flipped to
 * {@code cancelled}). {@code expectedVersion} is the optimistic-concurrency
 * token (null skips the staleness check).
 */
public record RemoveOrderLineCommand(
    UUID salesOrderHeaderId,
    UUID salesOrderLineId,
    Long expectedVersion
) {}
