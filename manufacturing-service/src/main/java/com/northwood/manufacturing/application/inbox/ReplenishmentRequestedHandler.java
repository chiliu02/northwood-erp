package com.northwood.manufacturing.application.inbox;

import com.northwood.inventory.domain.InventoryAggregateTypes;
import com.northwood.inventory.domain.events.ReplenishmentRequested;
import com.northwood.manufacturing.application.BomLookup;
import com.northwood.manufacturing.application.BomLookup.BomHeaderIdentity;
import com.northwood.manufacturing.application.WorkOrderReleaseService;
import com.northwood.manufacturing.application.dto.ReleaseForReplenishmentCommand;
import com.northwood.manufacturing.domain.WorkOrder;
import com.northwood.manufacturing.domain.events.ReplenishmentUndispatchable;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import com.northwood.shared.application.outbox.OutboxAppender;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Idempotent inbox handler for {@code inventory.ReplenishmentRequested}.
 * Filters on {@code targetService = "manufacturing"}; for purchasing-routed
 * requests this is a no-op (purchasing has its own sibling handler).
 *
 * <p>Releases a stock work order via
 * {@link WorkOrderReleaseService#releaseForReplenishment}. The aggregate
 * emits BOTH {@code manufacturing.WorkOrderCreated} (with the new
 * {@code replenishmentRequestId} field populated) and
 * {@code manufacturing.ReplenishmentDispatched} atomically, so inventory's
 * close-the-loop handler sees the dispatch and can flip the
 * originating request to {@code dispatched}.
 *
 * <p>If the SKU has no active BOM at handler time (a buyable-only SKU was
 * misclassified as manufactured, or the BOM hasn't been authored yet) the
 * handler logs a WARN and inbox-records the message as processed without
 * raising a WO — leaves the originating ReplenishmentRequest in
 * {@code requested}. Operators can intervene by either flipping the SKU's
 * make-vs-buy to purchasing-only or authoring a BOM.
 */
@Component
public class ReplenishmentRequestedHandler extends AbstractInboxHandler<ReplenishmentRequested> {

    public static final String CONSUMER_NAME = "manufacturing.replenishment-dispatcher";

    private final WorkOrderReleaseService releaseService;
    private final BomLookup boms;
    private final OutboxAppender outbox;

    public ReplenishmentRequestedHandler(
        InboxPort inbox,
        WorkOrderReleaseService releaseService,
        BomLookup boms,
        OutboxAppender outbox,
        ObjectMapper json
    ) {
        super(inbox, json, ReplenishmentRequested.class, ReplenishmentRequested.EVENT_TYPE, CONSUMER_NAME);
        this.releaseService = releaseService;
        this.boms = boms;
        this.outbox = outbox;
    }

    @Override
    protected void apply(ReplenishmentRequested payload, EventEnvelope envelope) {
        if (!ReplenishmentRequested.TARGET_SERVICE_MANUFACTURING.equals(payload.targetService())) {
            log.debug("[{}] skipping {} ({}) — targetService={} routes to a different service",
                CONSUMER_NAME, envelope.eventType(), envelope.eventId(), payload.targetService());
            return;
        }

        UUID productId = payload.productId();
        Optional<BomHeaderIdentity> identity = boms.findActiveBomIdentity(productId);
        if (identity.isEmpty()) {
            // Can't make it (no active BOM). Tell inventory so it cancels the
            // request (ReplenishmentCancelled) — which for a sales_order_shortage
            // request rejects the originating sales order.
            String reason = "no active BOM for product " + productId + " — cannot release a work order";
            outbox.append(new ReplenishmentUndispatchable(
                UUID.randomUUID(),
                payload.aggregateId(),
                payload.aggregateId(),
                productId,
                reason,
                Instant.now()
            ), InventoryAggregateTypes.REPLENISHMENT_REQUEST, envelope.actorUserId());
            log.warn(
                "[{}] product_id={} has no active BOM — emitting {} for replenishment_request={} (qty={}); "
                    + "inventory will cancel the request.",
                CONSUMER_NAME, productId, ReplenishmentUndispatchable.EVENT_TYPE, payload.aggregateId(), payload.quantity()
            );
            return;
        }

        String workOrderNumber = WorkOrder.NUMBER_PREFIX
            + UUID.randomUUID().toString().substring(0, WorkOrder.NUMBER_SUFFIX_LENGTH).toUpperCase();
        ReleaseForReplenishmentCommand command = new ReleaseForReplenishmentCommand(
            workOrderNumber,
            payload.aggregateId(),
            productId,
            identity.get().productSku(),
            identity.get().productName(),
            payload.quantity(),
            payload.sourceSalesOrderHeaderId()
        );

        WorkOrder wo = releaseService.releaseForReplenishment(command);
        log.info("[{}] released stock-replenishment work_order={} ({}) for replenishment_request={} (sku={}, qty={})",
            CONSUMER_NAME, wo.id().value(), wo.workOrderNumber(),
            payload.aggregateId(), identity.get().productSku(), payload.quantity());
    }
}
