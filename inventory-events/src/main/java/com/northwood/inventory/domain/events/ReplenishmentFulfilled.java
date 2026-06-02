package com.northwood.inventory.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A replenishment has been fulfilled. Emitted by inventory's close-the-loop
 * handlers when the downstream WO completes (manufactured path) or the linked
 * PO's goods receipt lands (purchased path).
 *
 * <p>{@code aggregateId} is the replenishment_request_id. Reporting's
 * {@code reporting.replenishment_history_view} consumes this to flip the row's
 * status to {@code 'fulfilled'} and stamp {@code fulfilled_at}.
 *
 * <p>The payload carries three fields needed by sales' fulfilment-saga fan-in
 * handler:
 * <ul>
 *   <li>{@code productId} — denormalises the SKU so a consumer can decide
 *       whether the fulfilment is relevant to a saga it's tracking, without
 *       a join back to {@code inventory.replenishment_request}. Always
 *       populated.</li>
 *   <li>{@code sourceSalesOrderHeaderId} — saga key. Non-null only for
 *       {@code reason = sales_order_shortage} requests; sales' fan-in handler
 *       uses it to find the saga (sales is keyed by header_id, not line_id).</li>
 *   <li>{@code sourceSalesOrderLineId} — line within the saga, so the
 *       handler can remove just that line's entry from the saga's
 *       {@code outstandingPurchasingLineIds} set. Same nullable semantic
 *       as the header id.</li>
 * </ul>
 *
 * <p>Backward compatibility: older reporting consumers ignore the added fields
 * (Jackson tolerates extra fields). Old events redelivered later deserialise
 * with the new fields as null — the sales-fulfilment handler treats null as
 * "not for me, skip".
 */
public record ReplenishmentFulfilled(
    UUID eventId,
    UUID aggregateId,
    UUID productId,
    UUID sourceSalesOrderHeaderId,
    UUID sourceSalesOrderLineId,
    Instant occurredAt
) implements DomainEvent {

    public static final String EVENT_TYPE = "inventory.ReplenishmentFulfilled";

    @Override public String eventType() { return EVENT_TYPE; }
}
