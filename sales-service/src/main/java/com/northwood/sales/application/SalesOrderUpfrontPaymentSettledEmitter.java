package com.northwood.sales.application;

import com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga;
import com.northwood.sales.domain.events.SalesOrderUpfrontPaymentSettled;
import com.northwood.shared.application.outbox.OutboxAppender;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * §2.31 Slice C / §2.32 Slice C. Emits {@code sales.SalesOrderUpfrontPaymentSettled}
 * when the fulfilment saga settles an order's up-front payment before shipment —
 * a prepayment-terms invoice fully paid ({@code prepaid}) or a deposit-terms
 * deposit fully paid ({@code deposit_paid}). Inventory consumes this to flip
 * {@code sales_order_line_facts.upfront_settled = true} so the shipment-gate
 * check can refuse a prepayment/deposit order whose up-front payment hasn't
 * landed (HTTP 409) without inventory reading sales' saga state directly.
 *
 * <p>Mirrors {@link SalesOrderReadyToShipEmitter}: lives under
 * {@code application/} (not {@code application/inbox/}) so future handlers
 * can share it without the inbox-package handler-only convention concern.
 * Stamped with {@link SalesOrderFulfilmentSaga#AGGREGATE_TYPE} — the saga
 * owns the emission lifecycle (the order aggregate didn't mutate; the saga's
 * state transition is the originator).
 */
@Service
public class SalesOrderUpfrontPaymentSettledEmitter {

    private static final Logger log = LoggerFactory.getLogger(SalesOrderUpfrontPaymentSettledEmitter.class);

    private final OutboxAppender outbox;

    public SalesOrderUpfrontPaymentSettledEmitter(OutboxAppender outbox) {
        this.outbox = outbox;
    }

    public void emitUpfrontPaymentSettled(UUID salesOrderHeaderId) {
        SalesOrderUpfrontPaymentSettled event = new SalesOrderUpfrontPaymentSettled(
            UUID.randomUUID(),
            salesOrderHeaderId,
            Instant.now()
        );
        outbox.append(event, SalesOrderFulfilmentSaga.AGGREGATE_TYPE);
        log.info("emitted {} for sales_order={}", SalesOrderUpfrontPaymentSettled.EVENT_TYPE, salesOrderHeaderId);
    }
}
