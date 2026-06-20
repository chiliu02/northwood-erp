package com.northwood.purchasing.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Purchasing's ack to {@code inventory.OrderPeggedSupplyCancellationRequested} —
 * the purchasing leg of multi-leg sales-order compensation. Reports whether the
 * order-pegged purchase order could be withdrawn:
 *
 * <ul>
 *   <li>{@code compensated = true} — the PO was {@code draft}/{@code sent} and is
 *       now {@code cancelled} (or was already cancelled — idempotent). The leg is
 *       undone.</li>
 *   <li>{@code compensated = false} — an <b>un-compensatable leaf</b>: the PO was
 *       already (partially) received / invoiced / paid, so withdrawing it is itself
 *       a business transaction (goods-receipt-and-return) out of scope here. The
 *       sales saga records the failure and reaches {@code compensation_failed} for
 *       manual intervention.</li>
 * </ul>
 *
 * <p>Addressed to the sales fulfilment saga: {@code sourceSalesOrderHeaderId}
 * finds the saga, {@code sourceSalesOrderLineId} forms the leg id
 * {@code purchasing:<lineId>} it drains. Partition key is {@code aggregateId} (the
 * purchase-order-header id).
 */
public record PurchaseOrderCancellationApplied(
    UUID eventId,
    UUID aggregateId,                 // purchase_order_header_id (partition key)
    UUID sourceSalesOrderHeaderId,    // the sales saga to drain
    UUID sourceSalesOrderLineId,      // forms the saga leg id 'purchasing:<lineId>'
    boolean compensated,              // true = PO withdrawn; false = un-compensatable leaf
    String previousStatus,            // the PO status before the cancellation attempt
    String reason,
    Instant occurredAt
) implements DomainEvent {

    public static final String EVENT_TYPE = "purchasing.PurchaseOrderCancellationApplied";

    @Override public String eventType() { return EVENT_TYPE; }
}
