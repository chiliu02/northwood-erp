package com.northwood.manufacturing.application.inbox;

import com.northwood.manufacturing.application.inbox.ProductReplenishmentProjection.Replenishment;
import com.northwood.manufacturing.application.saga.MakeToOrderSagaManager;
import com.northwood.manufacturing.application.BomLookup;
import com.northwood.manufacturing.domain.events.ManufacturingDispatched;
import com.northwood.manufacturing.domain.events.ManufacturingDispatched.LineOutcome;
import com.northwood.sales.domain.events.ManufacturingRequested;
import com.northwood.shared.domain.DomainEvent;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import com.northwood.shared.application.outbox.OutboxPort;
import com.northwood.shared.application.outbox.OutboxRow;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Idempotent inbox handler for {@code sales.ManufacturingRequested}. For each
 * line whose product has an active BOM, inserts a make-to-order saga row in
 * {@code 'started'} so the worker can pick it up. Lines without an active BOM
 * (raw materials, services) are reported as rejected — those orders can't be
 * made by manufacturing.
 *
 * <p>Always emits a single {@code manufacturing.ManufacturingDispatched} event
 * carrying per-line outcomes ({@code accepted} or {@code rejected_no_bom}).
 * Sales' fulfilment saga uses the event's all-rejected case to escape the
 * {@code manufacturing_requested} state; without this signal the saga would
 * sit there forever waiting for a {@code WorkOrderCreated} that will never
 * fire (since 0 sagas were created here, and the worker that turns sagas
 * into work orders has nothing to pick up).
 */
@Component
public class ManufacturingRequestedHandler extends AbstractInboxHandler<ManufacturingRequested> {

    public static final String CONSUMER_NAME = "manufacturing.make-to-order";

    private final MakeToOrderSagaManager sagaManager;
    private final BomLookup boms;
    private final ProductReplenishmentProjection replenishment;
    private final OutboxPort outbox;

    public ManufacturingRequestedHandler(
        InboxPort inbox,
        MakeToOrderSagaManager sagaManager,
        BomLookup boms,
        ProductReplenishmentProjection replenishment,
        OutboxPort outbox,
        ObjectMapper json
    ) {
        super(inbox, json, ManufacturingRequested.class, ManufacturingRequested.EVENT_TYPE, CONSUMER_NAME);
        this.sagaManager = sagaManager;
        this.boms = boms;
        this.replenishment = replenishment;
        this.outbox = outbox;
    }

    @Override
    protected void apply(ManufacturingRequested payload, EventEnvelope envelope) {
        List<LineOutcome> outcomes = new ArrayList<>();
        int sagasCreated = 0;
        for (ManufacturingRequested.RequestedLine line : payload.lines()) {
            // Check the make-vs-buy projection first — it's the authoritative
            // gate for "should this product flow through manufacturing at all?"
            // A row missing or is_manufactured=false routes the line to
            // rejected_not_manufactured (purchasing would compensate, once
            // wired). Only after this check do we look at BOM presence.
            Optional<Replenishment> classification = replenishment.findByProductId(line.productId());
            if (classification.isEmpty() || !classification.get().isManufactured()) {
                outcomes.add(new LineOutcome(
                    line.salesOrderLineId(), line.lineNumber(),
                    line.productId(), line.productSku(),
                    "rejected_not_manufactured"
                ));
                log.debug("[{}] product_id={} ({}) is not flagged is_manufactured; rejecting line {}",
                    CONSUMER_NAME, line.productId(), line.productSku(), line.lineNumber());
                continue;
            }
            if (boms.findActiveByFinishedProductId(line.productId()).isEmpty()) {
                outcomes.add(new LineOutcome(
                    line.salesOrderLineId(), line.lineNumber(),
                    line.productId(), line.productSku(),
                    "rejected_no_bom"
                ));
                log.debug("[{}] no active BOM for product_id={} ({}); rejecting line {}",
                    CONSUMER_NAME, line.productId(), line.productSku(), line.lineNumber());
                continue;
            }
            String dataJson;
            try {
                dataJson = json.writeValueAsString(line);
            } catch (JacksonException e) {
                throw new IllegalStateException("Failed to serialise saga data for line " + line.salesOrderLineId(), e);
            }
            sagaManager.insertStarted(
                payload.salesOrderHeaderId(),
                line.salesOrderLineId(),
                dataJson
            );
            sagasCreated++;
            outcomes.add(new LineOutcome(
                line.salesOrderLineId(), line.lineNumber(),
                line.productId(), line.productSku(),
                "accepted"
            ));
        }

        appendOutbox(new ManufacturingDispatched(
            UUID.randomUUID(),
            payload.salesOrderHeaderId(),
            payload.salesOrderHeaderId(),
            outcomes,
            Instant.now()
        ), ManufacturingDispatched.AGGREGATE_TYPE, envelope.actorUserId());

        log.info("[{}] processed {} ({}) for sales_order_header={} → {}/{} accepted",
            CONSUMER_NAME, envelope.eventType(), envelope.eventId(),
            payload.salesOrderHeaderId(), sagasCreated, payload.lines().size());
    }

    private void appendOutbox(DomainEvent event, String aggregateType, String actorUserId) {
        try {
            outbox.appendPending(OutboxRow.pending(
                event.eventId(),
                aggregateType,
                event.aggregateId(),
                event.eventType(),
                event.eventVersion(),
                json.writeValueAsString(event),
                null, null, null,
                actorUserId
            ));
        } catch (JacksonException e) {
            throw new IllegalStateException("Cannot serialise " + event.eventType(), e);
        }
    }
}
