package com.northwood.sales.application.inbox;

import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.REJECTED;

import com.northwood.manufacturing.domain.events.ManufacturingDispatched;
import com.northwood.sales.application.saga.SalesOrderFulfilmentSagaManager;
import com.northwood.sales.domain.SalesOrder;
import com.northwood.sales.domain.SalesOrderId;
import com.northwood.sales.domain.SalesOrderRepository;
import com.northwood.sales.domain.events.SalesOrderCancellationRequested;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import com.northwood.shared.application.outbox.OutboxAppender;
import java.time.Instant;
import java.util.UUID;
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
 * returned {@code rejected} only on {@code !anyAccepted}).
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
    private final SalesOrderRepository salesOrders;
    private final OutboxAppender outbox;

    public ManufacturingDispatchedHandler(
        InboxPort inbox,
        SalesOrderFulfilmentSagaManager sagaManager,
        SalesOrderHeaderStatusProjection statusProjection,
        SalesOrderRepository salesOrders,
        OutboxAppender outbox,
        ObjectMapper json
    ) {
        super(inbox, json, ManufacturingDispatched.class, ManufacturingDispatched.EVENT_TYPE, CONSUMER_NAME);
        this.sagaManager = sagaManager;
        this.statusProjection = statusProjection;
        this.salesOrders = salesOrders;
        this.outbox = outbox;
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
        if (REJECTED.equals(newState)) {
            statusProjection.markStatus(payload.salesOrderHeaderId(), SalesOrder.Status.REJECTED);
            String reason = buildRejectionReason(payload, acceptedCount, totalLines);
            emitCancellationRequest(payload.salesOrderHeaderId(), reason);
            log.info("[{}] sales_order={} rejected ({} accepted, {} rejected); compensation requested",
                CONSUMER_NAME, payload.salesOrderHeaderId(),
                acceptedCount, totalLines - acceptedCount);
        }
    }

    /**
     * §4.2 closure: emit {@code sales.SalesOrderCancellationRequested} for a
     * system-driven rejection (any rejected dispatch line) without going
     * through {@link SalesOrder#cancel(String)}. Downstream inventory +
     * manufacturing handlers consume it to release the partial stock
     * reservation and cancel any already-started make-to-order sagas.
     *
     * <p><b>Silent-fallback contract.</b> Loads the order to populate
     * {@code orderNumber} + {@code customerId} on the payload. If the order
     * can't be loaded — shouldn't happen, since we're inside the same
     * transaction that just observed {@code ManufacturingDispatched} for an
     * existing order — we log WARN and skip the emission rather than throw;
     * the sales saga has already transitioned to
     * {@code rejected} (terminal), so even without downstream
     * compensation the saga itself is in a sensible state. (Inlined from the
     * former {@code SalesOrderCompensationEmitter.emitCancellationRequest},
     * which had a single caller.)
     */
    private void emitCancellationRequest(UUID salesOrderHeaderId, String reason) {
        SalesOrder order = salesOrders.findById(SalesOrderId.of(salesOrderHeaderId)).orElse(null);
        if (order == null) {
            log.warn("emitCancellationRequest sales_order={} could not load SalesOrder; skipping emission. "
                + "Downstream compensation (stock release + work-order cancellation) will NOT fire.",
                salesOrderHeaderId);
            return;
        }
        outbox.append(new SalesOrderCancellationRequested(
            UUID.randomUUID(),
            salesOrderHeaderId,
            order.orderNumber(),
            order.customerId(),
            reason,
            Instant.now()
        ), SalesOrder.AGGREGATE_TYPE);
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
