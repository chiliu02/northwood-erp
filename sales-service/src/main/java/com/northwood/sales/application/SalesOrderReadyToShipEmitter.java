package com.northwood.sales.application;

import com.northwood.sales.domain.SalesOrder;
import com.northwood.sales.domain.events.SalesOrderReadyToShip;
import com.northwood.shared.application.outbox.OutboxAppender;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Shared {@code sales.SalesOrderReadyToShip} emission for the two inbox
 * handlers that can drive the fulfilment saga into {@code ready_to_ship}:
 * {@code StockReservedHandler} (full-reservation shortcut) and
 * {@code WorkOrderManufacturingCompletedHandler} (final work-order completion).
 * Each handler inspects its {@code applyXxx} return and calls this only on a
 * genuine transition to {@code ready_to_ship}; reporting consumes the event to
 * advance {@code sales_order_360_view.order_status}, which the shipment picker
 * filters on.
 *
 * <p>Mirrors {@link SalesOrderCompensationEmitter}: lives under
 * {@code application/} (not {@code application/inbox/}) so both handlers can
 * share it without the inbox-package handler-only convention concern. The
 * emission carries {@code aggregateId = sales_order_header_id} and is stamped
 * with {@link SalesOrder#AGGREGATE_TYPE} for consistency with the sibling
 * {@code SalesOrderCompensated} saga-milestone event reporting also projects.
 *
 * <p>Idempotency: a redelivered source event is short-circuited upstream by
 * the inbox dedup, and reporting's {@code recordReadyToShip} upsert is itself
 * idempotent, so a rare double-emit is harmless.
 */
@Service
public class SalesOrderReadyToShipEmitter {

    private static final Logger log = LoggerFactory.getLogger(SalesOrderReadyToShipEmitter.class);

    private final OutboxAppender outbox;

    public SalesOrderReadyToShipEmitter(OutboxAppender outbox) {
        this.outbox = outbox;
    }

    public void emitReadyToShip(UUID salesOrderHeaderId) {
        SalesOrderReadyToShip event = new SalesOrderReadyToShip(
            UUID.randomUUID(),
            salesOrderHeaderId,
            Instant.now()
        );
        outbox.append(event, SalesOrder.AGGREGATE_TYPE);
        log.info("emitted {} for sales_order={}", SalesOrderReadyToShip.EVENT_TYPE, salesOrderHeaderId);
    }
}
