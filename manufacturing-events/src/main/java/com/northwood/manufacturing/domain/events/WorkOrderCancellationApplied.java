package com.northwood.manufacturing.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Manufacturing's ack to {@code inventory.OrderPeggedSupplyCancellationRequested} —
 * the manufacturing leg of multi-leg sales-order compensation. Reports whether the
 * order-pegged work order could be withdrawn:
 *
 * <ul>
 *   <li>{@code compensated = true} — the WO was {@code released} and is now
 *       {@code cancelled} (or was already cancelled — idempotent). The leg is
 *       undone and its reserved raw materials released.</li>
 *   <li>{@code compensated = false} — an <b>un-compensatable leaf</b>: the WO was
 *       already in progress / completed / closed (material issued to WIP, operations
 *       run), so undoing it is a scrap-WIP-with-GL-loss out of scope here. The sales
 *       saga records the failure and reaches {@code compensation_failed} for manual
 *       intervention.</li>
 * </ul>
 *
 * <p>Addressed to the sales fulfilment saga: {@code sourceSalesOrderHeaderId}
 * finds the saga, {@code sourceSalesOrderLineId} forms the leg id
 * {@code manufacturing:<lineId>} it drains. Partition key is {@code aggregateId}
 * (the work-order id).
 */
public record WorkOrderCancellationApplied(
    UUID eventId,
    UUID aggregateId,                 // work_order_id (partition key)
    UUID sourceSalesOrderHeaderId,    // the sales saga to drain
    UUID sourceSalesOrderLineId,      // forms the saga leg id 'manufacturing:<lineId>'
    boolean compensated,              // true = WO withdrawn; false = un-compensatable leaf
    String previousStatus,            // the WO status before the cancellation attempt
    String reason,
    Instant occurredAt
) implements DomainEvent {

    public static final String EVENT_TYPE = "manufacturing.WorkOrderCancellationApplied";

    @Override public String eventType() { return EVENT_TYPE; }
}
