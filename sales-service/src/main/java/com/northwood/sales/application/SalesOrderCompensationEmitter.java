package com.northwood.sales.application;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.northwood.sales.domain.SalesOrder;
import com.northwood.sales.domain.SalesOrderId;
import com.northwood.sales.domain.SalesOrderRepository;
import com.northwood.sales.domain.events.SalesOrderCompensated;
import com.northwood.shared.application.outbox.OutboxPort;
import com.northwood.shared.application.outbox.OutboxRow;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Shared {@code sales.SalesOrderCompensated} emission for the two
 * cancellation-ack inbox handlers ({@code InventoryCancellationAppliedHandler}
 * and {@code ManufacturingCancellationAppliedHandler}). Reads
 * {@code SalesOrder.cancelledAt} for the event's {@code cancelledAt} field
 * and writes the event to the outbox.
 *
 * <p><b>Silent-fallback contract on {@code cancelledAt}.</b> Sourced from
 * {@code SalesOrder.cancelledAt} (set when the user invoked {@code cancel}).
 * If the aggregate isn't found here — which shouldn't happen since
 * cancellation already persisted the field — the event falls back to
 * {@code Instant.now()} with a WARN naming the missing order. The audit
 * drift is the saga round-trip (typically seconds). Throwing is rejected
 * because the saga has already transitioned + persisted; an exception
 * would re-enter the worker, find the saga already in {@code 'compensated'},
 * and short-circuit on the second attempt anyway — but with a misleading
 * retry trail.
 *
 * <p>Lives under {@code application/} (not {@code application/inbox/}) so
 * both handlers can call it without the package convention concern that
 * inbox-package classes are handler-only.
 */
@Service
public class SalesOrderCompensationEmitter {

    private static final Logger log = LoggerFactory.getLogger(SalesOrderCompensationEmitter.class);

    private final SalesOrderRepository salesOrders;
    private final OutboxPort outbox;
    private final ObjectMapper json;

    public SalesOrderCompensationEmitter(
        SalesOrderRepository salesOrders, OutboxPort outbox, ObjectMapper json
    ) {
        this.salesOrders = salesOrders;
        this.outbox = outbox;
        this.json = json;
    }

    public void emitCompensated(UUID salesOrderHeaderId) {
        Instant cancelledAt = salesOrders.findById(SalesOrderId.of(salesOrderHeaderId))
            .map(SalesOrder::cancelledAt)
            .orElse(null);
        if (cancelledAt == null) {
            log.warn(
                "emitCompensated sales_order={} could not load SalesOrder.cancelledAt; "
                    + "stamping {}.cancelledAt = now() (audit drift by saga round-trip duration)",
                salesOrderHeaderId, SalesOrderCompensated.EVENT_TYPE
            );
        }
        SalesOrderCompensated event = new SalesOrderCompensated(
            UUID.randomUUID(),
            salesOrderHeaderId,
            cancelledAt == null ? Instant.now() : cancelledAt,
            Instant.now()
        );
        try {
            outbox.appendPending(OutboxRow.pending(
                event.eventId(),
                SalesOrder.AGGREGATE_TYPE,
                event.aggregateId(),
                event.eventType(),
                event.eventVersion(),
                json.writeValueAsString(event),
                null, null, null,
                null
            ));
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialise " + SalesOrderCompensated.EVENT_TYPE, e);
        }
        log.info("emitted {} for sales_order={}", SalesOrderCompensated.EVENT_TYPE, salesOrderHeaderId);
    }
}
