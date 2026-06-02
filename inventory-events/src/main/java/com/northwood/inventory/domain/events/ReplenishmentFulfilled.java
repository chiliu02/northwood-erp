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
 *       {@code outstandingReplenishmentLineIds} set. Same nullable semantic
 *       as the header id.</li>
 *   <li>{@code pegged} — true when this was an order-pegged ({@code to_order})
 *       request (§2.43): inventory already reserved the output for the SO line
 *       atomically with the stock credit, so sales marks the line reserved and
 *       ships WITHOUT a re-reservation retry. False for shortage top-ups, where
 *       the pool was merely restocked and sales must retry the reservation.</li>
 * </ul>
 *
 * <p>Backward compatibility: older reporting consumers ignore the added fields
 * (Jackson tolerates extra fields). Old events redelivered later deserialise
 * with the new reference fields as null / {@code pegged} as false — the
 * sales-fulfilment handler treats null back-refs as "not for me, skip".
 */
public record ReplenishmentFulfilled(
    UUID eventId,
    UUID aggregateId,
    UUID productId,
    UUID sourceSalesOrderHeaderId,
    UUID sourceSalesOrderLineId,
    boolean pegged,
    Instant occurredAt
) implements DomainEvent {

    public static final String EVENT_TYPE = "inventory.ReplenishmentFulfilled";

    @Override public String eventType() { return EVENT_TYPE; }
}
