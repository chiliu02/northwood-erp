package com.northwood.manufacturing.domain.events;

import com.northwood.sales.domain.SalesAggregateTypes;
import com.northwood.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Per-sales-order acceptance signal emitted by manufacturing's
 * {@code ManufacturingRequestedHandler} after deciding which lines it can
 * actually fulfil. One outcome per line; sales' handler reacts to the
 * "everything was rejected" case by flipping the fulfilment saga to
 * {@code rejected} (otherwise the saga would sit at
 * {@code manufacturing_requested} forever waiting for a {@code WorkOrderCreated}
 * that will never arrive).
 *
 * <p>{@code aggregateId} is the sales-order header id (manufacturing operates
 * per-order at this stage; the per-line WO ids are not yet known when this
 * event is emitted — the saga worker creates them on a later tick).
 *
 * <p>Outcomes today:
 * <ul>
 *   <li>{@code accepted} — line has an active BOM, a make-to-order saga has
 *       been inserted, a work order will follow.</li>
 *   <li>{@code rejected_no_bom} — line's product has no active BOM (e.g. a
 *       raw material was ordered directly). Manufacturing cannot fulfil it.</li>
 * </ul>
 *
 * <p>Forward-compatible: future outcomes (e.g. {@code rejected_discontinued},
 * {@code rejected_quantity_invalid}) extend the same event without changing
 * the consumer's accept/reject decision logic.
 */
public record ManufacturingDispatched(
    UUID eventId,
    UUID aggregateId,
    UUID salesOrderHeaderId,
    List<LineOutcome> lines,
    Instant occurredAt
) implements DomainEvent {

    public static final String EVENT_TYPE = "manufacturing.ManufacturingDispatched";

    /**
     * Wire-format aggregate-type. Sourced from {@link SalesAggregateTypes#SALES_ORDER}
     * — manufacturing-service cannot import sales-service's {@code SalesOrder}
     * aggregate class, but it can import the constants in {@code sales-events}
     * (the contract surface). Per §2.20 (2026-05-16).
     */
    public static final String AGGREGATE_TYPE = SalesAggregateTypes.SALES_ORDER;

    @Override public String eventType() { return EVENT_TYPE; }

    public record LineOutcome(
        UUID salesOrderLineId,
        int lineNumber,
        UUID productId,
        String productSku,
        String outcome
    ) {}
}
