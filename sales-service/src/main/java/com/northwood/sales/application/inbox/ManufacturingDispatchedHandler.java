package com.northwood.sales.application.inbox;

import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.STOCK_RESERVATION_FAILED;

import com.northwood.manufacturing.domain.events.ManufacturingDispatched;
import com.northwood.sales.application.SalesOrderCompensationEmitter;
import com.northwood.sales.application.saga.SalesOrderFulfilmentSagaManager;
import com.northwood.sales.domain.SalesOrder;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Inbox handler for {@code manufacturing.ManufacturingDispatched}. Counts
 * accepted lines, asks the manager to apply the dispatch outcome, and on
 * any rejection (§4.2 closure, 2026-05-15) flips the order header to
 * {@code 'rejected'} + emits {@code sales.SalesOrderCancellationRequested}
 * so inventory releases any partial reservation and manufacturing cancels
 * the make-to-order sagas it already started for the accepted lines.
 *
 * <p>Pre-§4.2 closure: this handler only flipped the order to
 * {@code 'rejected'} when ALL lines were rejected (the saga manager
 * returned {@code stock_reservation_failed} only on {@code !anyAccepted}).
 * Partial rejection silently dropped the rejected lines and proceeded
 * with the accepted ones — order ended up with {@code total_amount}
 * reflecting the originally-ordered total while only the accepted lines
 * shipped. The new policy: ANY rejection rejects the whole order.
 */
@Component
public class ManufacturingDispatchedHandler extends AbstractInboxHandler<ManufacturingDispatched> {

    public static final String CONSUMER_NAME = "sales.fulfilment-saga.manufacturing-dispatched";

    private final SalesOrderFulfilmentSagaManager sagaManager;
    private final SalesOrderHeaderStatusProjection statusProjection;
    private final SalesOrderCompensationEmitter compensationEmitter;

    public ManufacturingDispatchedHandler(
        InboxPort inbox,
        SalesOrderFulfilmentSagaManager sagaManager,
        SalesOrderHeaderStatusProjection statusProjection,
        SalesOrderCompensationEmitter compensationEmitter,
        ObjectMapper json
    ) {
        super(inbox, json, ManufacturingDispatched.class, ManufacturingDispatched.EVENT_TYPE, CONSUMER_NAME);
        this.sagaManager = sagaManager;
        this.statusProjection = statusProjection;
        this.compensationEmitter = compensationEmitter;
    }

    @Override
    protected void apply(ManufacturingDispatched payload, EventEnvelope envelope) {
        int acceptedCount = (int) payload.lines().stream()
            .filter(l -> "accepted".equals(l.outcome()))
            .count();
        int totalLines = payload.lines().size();

        String newState = sagaManager.applyManufacturingDispatched(
            payload.salesOrderHeaderId(), acceptedCount, totalLines
        );
        if (STOCK_RESERVATION_FAILED.equals(newState)) {
            statusProjection.markStatus(payload.salesOrderHeaderId(), SalesOrder.REJECTED);
            String reason = buildRejectionReason(payload, acceptedCount, totalLines);
            compensationEmitter.emitCancellationRequest(payload.salesOrderHeaderId(), reason);
            log.info("[{}] sales_order={} rejected ({} accepted, {} rejected); compensation requested",
                CONSUMER_NAME, payload.salesOrderHeaderId(),
                acceptedCount, totalLines - acceptedCount);
        }
    }

    private static String buildRejectionReason(ManufacturingDispatched payload, int accepted, int total) {
        String rejectedSkus = payload.lines().stream()
            .filter(l -> !"accepted".equals(l.outcome()))
            .map(l -> l.productSku() + " (" + l.outcome() + ")")
            .collect(Collectors.joining(", "));
        return accepted == 0
            ? "All " + total + " line(s) rejected by manufacturing dispatch: " + rejectedSkus
            : (total - accepted) + " of " + total + " line(s) rejected: " + rejectedSkus
                + "; order cannot be partially fulfilled.";
    }
}
