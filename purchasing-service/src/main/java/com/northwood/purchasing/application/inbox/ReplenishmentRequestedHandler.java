package com.northwood.purchasing.application.inbox;

import com.northwood.inventory.domain.InventoryAggregateTypes;
import com.northwood.inventory.domain.events.ReplenishmentRequested;
import com.northwood.purchasing.application.PurchaseRequisitionService;
import com.northwood.purchasing.application.dto.RequisitionLineRequest;
import com.northwood.purchasing.application.dto.StockReplenishmentCommand;
import com.northwood.purchasing.domain.PurchaseRequisition;
import com.northwood.purchasing.domain.events.ReplenishmentUndispatchable;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import com.northwood.shared.application.outbox.OutboxAppender;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Idempotent inbox handler for
 * {@code inventory.ReplenishmentRequested}. Filters on
 * {@code targetService = "purchasing"}; for manufacturing-routed requests
 * this is a no-op (manufacturing has its own sibling handler).
 *
 * <p>Builds a single-line {@link StockReplenishmentCommand} and calls
 * {@link PurchaseRequisitionService#createForStockReplenishment}. The
 * aggregate emits BOTH {@code purchasing.PurchaseRequisitionCreated} (with
 * the new {@code sourceReplenishmentRequestId} field) AND
 * {@code purchasing.ReplenishmentDispatched} atomically, so inventory's
 * close-the-loop handler picks up the dispatch and can flip the
 * originating request to {@code dispatched}.
 *
 * <p>This handler subsumes the retired
 * {@code purchasing.RawMaterialShortageDetectedHandler}: both reorder-point
 * breaches AND ex-WO-shortage triggers now flow through this single
 * channel. The WO-shortage path arrives through inventory's bridge.
 *
 * <p>The {@link ReplenishmentRequested} event carries only product_id (not
 * SKU/name) — purchasing's existing {@code RequisitionLineRequest} expects
 * SKU + name to stamp on the line. Today we synthesise placeholder strings
 * derived from the product_id; finance's 3-way match and the supplier
 * lookup work off product_id, and the SKU/name will get backfilled by the
 * existing product-card projection if we move that read upstream of the
 * line build in a future polish. The deferred backfill is documented as a
 * follow-up.
 */
@Component
public class ReplenishmentRequestedHandler extends AbstractInboxHandler<ReplenishmentRequested> {

    public static final String CONSUMER_NAME = "purchasing.replenishment-dispatcher";

    private final PurchaseRequisitionService requisitions;
    private final OutboxAppender outbox;

    public ReplenishmentRequestedHandler(
        InboxPort inbox,
        PurchaseRequisitionService requisitions,
        OutboxAppender outbox,
        ObjectMapper json
    ) {
        super(inbox, json, ReplenishmentRequested.class, ReplenishmentRequested.EVENT_TYPE, CONSUMER_NAME);
        this.requisitions = requisitions;
        this.outbox = outbox;
    }

    @Override
    protected void apply(ReplenishmentRequested payload, EventEnvelope envelope) {
        if (!ReplenishmentRequested.TARGET_SERVICE_PURCHASING.equals(payload.targetService())) {
            log.debug("[{}] skipping {} ({}) — targetService={} routes to a different service",
                CONSUMER_NAME, envelope.eventType(), envelope.eventId(), payload.targetService());
            return;
        }

        // Placeholder SKU/name pending the projection-backed lookup follow-up.
        // The product_id is the load-bearing identifier — finance's 3-way
        // match + the supplier lookup all key on it, not the SKU.
        String placeholderSku = "RPL-" + payload.productId().toString().substring(0, 8).toUpperCase();
        String placeholderName = "Replenishment line for product " + payload.productId();

        String requisitionNumber = PurchaseRequisition.NUMBER_PREFIX
            + UUID.randomUUID().toString().substring(0, PurchaseRequisition.NUMBER_SUFFIX_LENGTH).toUpperCase();

        Optional<UUID> prId = requisitions.createForStockReplenishment(new StockReplenishmentCommand(
            requisitionNumber,
            payload.aggregateId(),
            // Carry the originating sales order (non-null for
            // sales_order_shortage / order_pegged) into the P2P saga's trace key.
            payload.sourceSalesOrderHeaderId(),
            List.of(new RequisitionLineRequest(
                payload.productId(),
                placeholderSku,
                placeholderName,
                payload.quantity(),
                null
            ))
        ));

        if (prId.isEmpty()) {
            // No vendor to source from. Tell inventory so it
            // cancels the request (ReplenishmentCancelled) — which for a
            // sales_order_shortage request rejects the originating sales order.
            String reason = "no approved vendor / supplier for product " + payload.productId()
                + " — cannot raise a requisition";
            outbox.append(new ReplenishmentUndispatchable(
                UUID.randomUUID(),
                payload.aggregateId(),
                payload.aggregateId(),
                payload.productId(),
                reason,
                Instant.now()
            ), InventoryAggregateTypes.REPLENISHMENT_REQUEST, envelope.actorUserId());
            log.warn("[{}] no vendor for product={} — emitting {} for replenishment_request={} (qty={}); inventory will cancel the request",
                CONSUMER_NAME, payload.productId(), ReplenishmentUndispatchable.EVENT_TYPE,
                payload.aggregateId(), payload.quantity());
            return;
        }

        log.info("[{}] created stock-replenishment PR={} ({}) for replenishment_request={} (product={}, qty={}, reason={})",
            CONSUMER_NAME, prId.get(), requisitionNumber, payload.aggregateId(),
            payload.productId(), payload.quantity(), payload.reason());
    }
}
