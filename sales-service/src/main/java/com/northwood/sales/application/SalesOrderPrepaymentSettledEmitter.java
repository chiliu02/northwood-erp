package com.northwood.sales.application;

import com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga;
import com.northwood.sales.domain.events.SalesOrderPrepaymentSettled;
import com.northwood.shared.application.outbox.OutboxAppender;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * §2.31 Slice C. Emits {@code sales.SalesOrderPrepaymentSettled} when the
 * fulfilment saga transitions to {@code prepaid} (full settlement of a
 * prepayment-terms invoice). Inventory consumes this to flip
 * {@code sales_order_line_facts.prepayment_settled = true} so the
 * shipment-gate check can refuse unpaid prepayment orders with HTTP 409
 * without inventory reading sales' saga state directly.
 *
 * <p>Mirrors {@link SalesOrderReadyToShipEmitter}: lives under
 * {@code application/} (not {@code application/inbox/}) so future handlers
 * can share it without the inbox-package handler-only convention concern.
 * Stamped with {@link SalesOrderFulfilmentSaga#AGGREGATE_TYPE} — the saga
 * owns the emission lifecycle (the order aggregate didn't mutate; the saga's
 * state transition is the originator).
 */
@Service
public class SalesOrderPrepaymentSettledEmitter {

    private static final Logger log = LoggerFactory.getLogger(SalesOrderPrepaymentSettledEmitter.class);

    private final OutboxAppender outbox;

    public SalesOrderPrepaymentSettledEmitter(OutboxAppender outbox) {
        this.outbox = outbox;
    }

    public void emitPrepaymentSettled(UUID salesOrderHeaderId) {
        SalesOrderPrepaymentSettled event = new SalesOrderPrepaymentSettled(
            UUID.randomUUID(),
            salesOrderHeaderId,
            Instant.now()
        );
        outbox.append(event, SalesOrderFulfilmentSaga.AGGREGATE_TYPE);
        log.info("emitted {} for sales_order={}", SalesOrderPrepaymentSettled.EVENT_TYPE, salesOrderHeaderId);
    }
}
