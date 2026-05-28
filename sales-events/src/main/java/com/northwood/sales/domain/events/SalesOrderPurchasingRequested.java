package com.northwood.sales.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * §2.36: Saga-driven request to inventory's replenishment orchestrator for
 * the purchased-only short lines of a sales order. Sibling to
 * {@link ManufacturingRequested} — together they restore the symmetric
 * branch off {@code stock_reservation_incomplete} that §2.35's reorder-
 * point monitor only mitigates, never closes.
 *
 * <p>Emitted by {@code SalesOrderFulfilmentSagaWorker} when the partial-
 * reservation flow detects short lines whose SKU classification (read from
 * inventory's {@code product_replenishment} snapshot) is purchased-only.
 * Manufactured-only short lines continue to go through
 * {@code ManufacturingRequested}; orders with a mix of both raise both
 * events in the same worker tick.
 *
 * <p>Consumed by inventory's {@code SalesOrderPurchasingRequestedHandler}
 * (consumer name {@code inventory.sales-order-purchasing-requested}),
 * which raises one
 * {@code ReplenishmentRequest.requestForSalesOrderShortage(...)} per
 * line — back-referenced to {@code salesOrderLineId} so the eventual
 * {@code inventory.ReplenishmentFulfilled} can un-park the originating
 * fulfilment saga.
 *
 * <p>Inventory owns the routing decision (per the §2.35 architectural
 * rule) — the SO worker only states "these specific purchased-only lines
 * are short; please replenish"; inventory derives {@code target_service =
 * purchasing} from its own {@code product_replenishment} snapshot.
 *
 * <p>Lives on the sales-service outbox, so inventory receives it via its
 * normal Kafka inbox dispatch.
 */
public record SalesOrderPurchasingRequested(
    UUID eventId,
    UUID aggregateId,
    UUID salesOrderHeaderId,
    UUID warehouseId,
    List<RequestedLine> lines,
    Instant occurredAt
) implements DomainEvent {

    public static final String EVENT_TYPE = "sales.SalesOrderPurchasingRequested";

    @Override public String eventType() { return EVENT_TYPE; }

    /**
     * One per purchased-only short line. {@code shortageQuantity} is the
     * amount inventory failed to reserve — this is the qty the
     * replenishment request will be raised for (rounded up by reorder
     * policy if the SKU's reorder_quantity exceeds it).
     */
    public record RequestedLine(
        UUID salesOrderLineId,
        int lineNumber,
        UUID productId,
        String productSku,
        String productName,
        BigDecimal shortageQuantity
    ) {}
}
