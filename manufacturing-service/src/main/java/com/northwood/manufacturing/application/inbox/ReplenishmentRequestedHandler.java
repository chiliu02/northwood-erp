package com.northwood.manufacturing.application.inbox;

import com.northwood.inventory.domain.events.ReplenishmentRequested;
import com.northwood.manufacturing.application.BomLookup;
import com.northwood.manufacturing.application.BomLookup.BomHeaderIdentity;
import com.northwood.manufacturing.application.WorkOrderReleaseService;
import com.northwood.manufacturing.application.dto.ReleaseForReplenishmentCommand;
import com.northwood.manufacturing.domain.WorkOrder;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * §2.35 Slice C: idempotent inbox handler for
 * {@code inventory.ReplenishmentRequested}. Filters on
 * {@code targetService = "manufacturing"}; for purchasing-routed requests
 * this is a no-op (purchasing has its own sibling handler in Slice D).
 *
 * <p>Releases a stock work order via
 * {@link WorkOrderReleaseService#releaseForReplenishment}. The aggregate
 * emits BOTH {@code manufacturing.WorkOrderCreated} (with the new
 * {@code replenishmentRequestId} field populated) and
 * {@code manufacturing.ReplenishmentDispatched} atomically, so inventory's
 * close-the-loop handler (Slice E) sees the dispatch and can flip the
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

    public ReplenishmentRequestedHandler(
        InboxPort inbox,
        WorkOrderReleaseService releaseService,
        BomLookup boms,
        ObjectMapper json
    ) {
        super(inbox, json, ReplenishmentRequested.class, ReplenishmentRequested.EVENT_TYPE, CONSUMER_NAME);
        this.releaseService = releaseService;
        this.boms = boms;
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
            log.warn(
                "[{}] product_id={} has no active BOM — cannot release a stock-replenishment work order "
                    + "for replenishment_request={} (qty={}). Inventory's ReplenishmentRequest stays open; "
                    + "operator should either author a BOM or flip make-vs-buy to purchasing-only.",
                CONSUMER_NAME, productId, payload.aggregateId(), payload.quantity()
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
            payload.quantity()
        );

        WorkOrder wo = releaseService.releaseForReplenishment(command);
        log.info("[{}] released stock-replenishment work_order={} ({}) for replenishment_request={} (sku={}, qty={})",
            CONSUMER_NAME, wo.id().value(), wo.workOrderNumber(),
            payload.aggregateId(), identity.get().productSku(), payload.quantity());
    }
}
