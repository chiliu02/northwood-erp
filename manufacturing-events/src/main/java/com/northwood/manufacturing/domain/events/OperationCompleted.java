package com.northwood.manufacturing.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One operation on a work order has finished. {@code aggregateId} is the work
 * order's id (operations are children, not aggregate roots).
 */
public record OperationCompleted(
    UUID eventId,
    UUID aggregateId,
    UUID workOrderOperationId,
    int operationSequence,
    String operationCode,
    BigDecimal plannedRunMinutes,
    BigDecimal actualMinutes,
    UUID salesOrderHeaderId,
    UUID salesOrderLineId,
    Instant occurredAt
) implements DomainEvent {

    public static final String EVENT_TYPE = "manufacturing.OperationCompleted";

    @Override public String eventType() { return EVENT_TYPE; }
}
