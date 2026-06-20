package com.northwood.sales.application;

import com.northwood.sales.domain.SalesOrder;
import com.northwood.sales.domain.SalesOrderId;
import com.northwood.sales.domain.SalesOrderRepository;
import com.northwood.sales.domain.events.SalesOrderCompensated;
import com.northwood.sales.domain.events.SalesOrderCompensationFailed;
import com.northwood.shared.application.outbox.OutboxAppender;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * {@code sales.SalesOrderCompensated} emission for the compensation-ack inbox
 * handler ({@code InventoryCancellationAppliedHandler}). Reads
 * {@code SalesOrder.cancelledAt} for the event's {@code cancelledAt} field and
 * hands the event to {@link OutboxAppender}. Encapsulates the construction +
 * its silent-fallback contract; the mechanical serialise-and-append lives in
 * {@code OutboxAppender}. The second consumer
 * ({@code ManufacturingCancellationAppliedHandler}) was retired when manufacturing
 * dropped out of the compensation gate — inventory is now the sole ack.
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
    private final OutboxAppender outbox;

    public SalesOrderCompensationEmitter(SalesOrderRepository salesOrders, OutboxAppender outbox) {
        this.salesOrders = salesOrders;
        this.outbox = outbox;
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
        outbox.append(event, SalesOrder.AGGREGATE_TYPE);
        log.info("emitted {} for sales_order={}", SalesOrderCompensated.EVENT_TYPE, salesOrderHeaderId);
    }

    /**
     * Emit {@code sales.SalesOrderCompensationFailed} — the saga reached
     * {@code compensation_failed} because at least one order-pegged supply leg
     * could not be withdrawn (an un-compensatable leaf). The order is cancelled
     * either way; this is the escalation signal (open an RMA / post a write-off).
     * Shares the {@code cancelledAt} silent-fallback contract with
     * {@link #emitCompensated}.
     */
    public void emitCompensationFailed(UUID salesOrderHeaderId) {
        Instant cancelledAt = salesOrders.findById(SalesOrderId.of(salesOrderHeaderId))
            .map(SalesOrder::cancelledAt)
            .orElse(null);
        if (cancelledAt == null) {
            log.warn(
                "emitCompensationFailed sales_order={} could not load SalesOrder.cancelledAt; "
                    + "stamping {}.cancelledAt = now() (audit drift by saga round-trip duration)",
                salesOrderHeaderId, SalesOrderCompensationFailed.EVENT_TYPE
            );
        }
        SalesOrderCompensationFailed event = new SalesOrderCompensationFailed(
            UUID.randomUUID(),
            salesOrderHeaderId,
            cancelledAt == null ? Instant.now() : cancelledAt,
            Instant.now()
        );
        outbox.append(event, SalesOrder.AGGREGATE_TYPE);
        log.info("emitted {} for sales_order={}", SalesOrderCompensationFailed.EVENT_TYPE, salesOrderHeaderId);
    }
}
