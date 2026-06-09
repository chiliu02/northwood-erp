package com.northwood.sales.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A line was removed from a sales order, post-placement and pre-shipment. The
 * removal is <i>soft</i> on the sales side — the row survives with
 * {@code line_status = 'cancelled'} so the line id stays resolvable — but to a
 * consumer it means "release whatever you held for this line". {@code
 * previousQuantity} is the ordered quantity at removal time so inventory knows
 * how much to release without reading sales' state.
 *
 * <p>Partition key is {@code aggregateId} (the sales-order header id) so the
 * removal is ordered relative to the original placement and any prior
 * amendment.
 *
 * <p>{@code newOrderTotal} is the order's recomputed header total <i>after</i>
 * the removal (the soft-cancelled line is excluded from totals), so the
 * reporting 360 projection can refresh {@code total_amount} /
 * {@code outstanding_amount} — §1G.3.
 */
public record SalesOrderLineRemoved(
    UUID eventId,
    UUID aggregateId,         // sales_order_header_id
    UUID salesOrderLineId,
    UUID productId,
    BigDecimal previousQuantity,
    BigDecimal newOrderTotal,
    Instant occurredAt
) implements DomainEvent {

    public static final String EVENT_TYPE = "sales.SalesOrderLineRemoved";

    @Override public String eventType() { return EVENT_TYPE; }
}
