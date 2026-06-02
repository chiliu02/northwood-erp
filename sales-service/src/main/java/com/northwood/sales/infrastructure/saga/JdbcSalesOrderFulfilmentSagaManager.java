package com.northwood.sales.infrastructure.saga;

import com.northwood.finance.domain.events.CustomerInvoiceCreated;
import com.northwood.finance.domain.events.CustomerPaymentReceived;
import com.northwood.inventory.domain.events.InventorySalesOrderCancellationApplied;
import com.northwood.inventory.domain.events.ReplenishmentCancelled;
import com.northwood.inventory.domain.events.ReplenishmentFulfilled;
import com.northwood.inventory.domain.events.ShipmentPosted;
import com.northwood.inventory.domain.events.StockReserved;
import com.northwood.sales.application.saga.SalesOrderFulfilmentSagaManager;
import com.northwood.sales.application.saga.SalesOrderFulfilmentSagaPort;
import com.northwood.sales.domain.PaymentTerms;
import com.northwood.sales.domain.saga.FulfilmentSagaData;
import com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga;
import com.northwood.shared.application.saga.SagaManager;
import com.northwood.shared.domain.Assert;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.*;

/**
 * JDBC-backed sales fulfilment saga manager. Saga state truth — every
 * transition the saga can take is a method here. Holds <i>only</i> the
 * minimum needed for saga state work: {@link SalesOrderFulfilmentSagaPort}
 * (saga row CRUD) and {@link ObjectMapper} (saga.data JSON). All side
 * effects (event emission, projection writes, calls into other services /
 * aggregates) live with the caller — the worker shell for worker-driven
 * advances and the inbox handler shells for inbox-driven advances.
 *
 * <p>Apply methods take saga-relevant primitives (UUIDs, sets, strings),
 * return the saga's new state. Callers gate post-saga side effects on the
 * returned state — e.g. {@code "compensated"} triggers
 * {@code SalesOrderCompensated} emission; {@code "rejected"}
 * triggers a {@code rejected} status projection.
 */
@Service
public class JdbcSalesOrderFulfilmentSagaManager
    extends SagaManager<SalesOrderFulfilmentSaga, SalesOrderFulfilmentSagaPort>
    implements SalesOrderFulfilmentSagaManager {

    private final ObjectMapper json;

    /**
     * Lease + backoff durations are overridable via
     * {@code northwood.saga.lease-ttl-seconds} (default 30s) and
     * {@code northwood.saga.retry-backoff-seconds} (default 15s).
     * Same defaults across all three saga managers; the per-service override
     * exists so a single saga family can be tuned without touching siblings.
     */
    public JdbcSalesOrderFulfilmentSagaManager(
        SalesOrderFulfilmentSagaPort sagaPort,
        ObjectMapper json,
        PlatformTransactionManager transactionManager,
        @org.springframework.beans.factory.annotation.Value("${northwood.saga.lease-ttl-seconds:30}") long leaseTtlSeconds,
        @org.springframework.beans.factory.annotation.Value("${northwood.saga.retry-backoff-seconds:15}") long retryBackoffSeconds
    ) {
        super(sagaPort, transactionManager, Duration.ofSeconds(leaseTtlSeconds), Duration.ofSeconds(retryBackoffSeconds));
        this.json = json;
    }

    @Override
    protected Set<String> activeStates() {
        // Worker-pickup checkpoints, each requiring the worker to emit an event
        // and transition forward. STARTED → stock-reservation / prepayment /
        // deposit request; PREPAID + DEPOSIT_PAID → stock-reservation request
        // after the up-front payment settles. STOCK_RESERVATION_INCOMPLETE is
        // not in this set — inventory raises the replenishment in-tx, so the
        // worker has nothing to do there; the saga parks until
        // ReplenishmentFulfilled / ReplenishmentCancelled drives it via the inbox.
        return Set.of(STARTED, PREPAID, DEPOSIT_PAID);
    }

    // ============================================================
    // Lifecycle entry points
    // ============================================================

    @Override
    @Transactional
    public void insertStarted(UUID salesOrderHeaderId, String dataJson) {
        sagaPort.insert(SalesOrderFulfilmentSaga.started(salesOrderHeaderId, dataJson));
    }

    @Override
    @Transactional
    public void requestCompensation(UUID salesOrderHeaderId) {
        SalesOrderFulfilmentSaga saga = sagaPort.findBySalesOrderId(salesOrderHeaderId)
            .orElseThrow(() -> new SagaNotFoundException(salesOrderHeaderId));
        saga.transitionTo(COMPENSATING, "wait_for_compensation_acks");
        sagaPort.update(saga);
    }

    // ============================================================
    // Inbox-driven transitions
    // ============================================================

    @Override
    @Transactional
    public String applyStockReserved(
        UUID salesOrderHeaderId,
        String reservationStatus,
        Set<UUID> shortageLineIds
    ) {
        SalesOrderFulfilmentSaga saga = requireSaga(salesOrderHeaderId, StockReserved.EVENT_TYPE);
        if (StockReserved.STATUS_RESERVED.equals(reservationStatus)) {
            // Full stock cover — every line is already reserved against
            // stock_balance, so there is nothing to replenish. Go straight to
            // ready_to_ship and wait for ShipmentPosted.
            saga.transitionTo(READY_TO_SHIP, "wait_for_shipment");
            sagaPort.update(saga);
            log.info("saga {} sales_order={} status={} → ready_to_ship (full reservation)",
                saga.sagaId(), salesOrderHeaderId, reservationStatus);
        } else {
            // partially_reserved / failed must carry the short line ids — the
            // inventory side has nothing meaningful to say otherwise. Fail
            // loudly rather than park an empty outstanding set that would never
            // un-park. Inventory has already raised the replenishment for each
            // short line in the same transaction; sales just records which lines
            // it's waiting on and parks.
            Assert.stateNotEmpty(shortageLineIds, StockReserved.EVENT_TYPE + " status=" + reservationStatus
                + " for sales_order=" + salesOrderHeaderId + " arrived without any short line ids. "
                + "Inventory must report the per-line shortage for partially_reserved / failed outcomes.");
            FulfilmentSagaData updated = readData(saga).withOutstandingReplenishmentLineIds(shortageLineIds);
            writeData(saga, updated);
            saga.transitionTo(STOCK_RESERVATION_INCOMPLETE, "wait_for_replenishment");
            saga.parkUntil(Instant.now().plus(Duration.ofDays(1)));
            sagaPort.update(saga);
            log.info("saga {} sales_order={} status={} → stock_reservation_incomplete ({} line(s) awaiting replenishment)",
                saga.sagaId(), salesOrderHeaderId, reservationStatus, shortageLineIds.size());
        }
        return saga.state();
    }

    @Override
    @Transactional
    public String applyReplenishmentFulfilled(UUID salesOrderHeaderId, UUID salesOrderLineId, boolean pegged) {
        SalesOrderFulfilmentSaga saga = requireSaga(salesOrderHeaderId, ReplenishmentFulfilled.EVENT_TYPE);

        // Only meaningful while parked awaiting replenishment. Late deliveries
        // (rejected saga, or saga already retried and advanced) are no-ops.
        if (!STOCK_RESERVATION_INCOMPLETE.equals(saga.state())) {
            log.debug("saga {} sales_order={} ignoring replenishment-fulfilled (state={}, line={})",
                saga.sagaId(), salesOrderHeaderId, saga.state(), salesOrderLineId);
            return saga.state();
        }

        FulfilmentSagaData data = readData(saga);
        if (!data.outstandingReplenishmentLineIds().contains(salesOrderLineId)) {
            // Idempotent: line already removed (duplicate event delivery).
            log.debug("saga {} sales_order={} replenishment-fulfilled for already-fulfilled line={}; idempotent no-op",
                saga.sagaId(), salesOrderHeaderId, salesOrderLineId);
            return saga.state();
        }

        FulfilmentSagaData updated = data.withReplenishmentLineFulfilled(salesOrderLineId, pegged);
        if (updated.allReplenishmentLinesFulfilled()) {
            if (!updated.requiresReservationRetry()) {
                // §2.43 make-/buy-to-order: every outstanding line was an
                // order-pegged completion — inventory already reserved each
                // output for its SO line atomically with the stock credit. Ship
                // straight off the peg, no re-reservation (which would re-peg).
                writeData(saga, updated);
                saga.transitionTo(READY_TO_SHIP, "wait_for_shipment");
                sagaPort.update(saga);
                log.info("saga {} sales_order={} → ready_to_ship (all {} line(s) order-pegged + reserved on completion)",
                    saga.sagaId(), salesOrderHeaderId, data.outstandingReplenishmentLineIds().size());
                return READY_TO_SHIP;
            }
            // Every outstanding line has been replenished and at least one was a
            // shortage top-up — re-enter the reservation cycle. The caller
            // re-emits StockReservationRequested.
            writeData(saga, updated);
            saga.transitionTo(STOCK_RESERVATION_REQUESTED, "retry_reservation_after_replenishment");
            saga.parkUntil(Instant.now().plus(Duration.ofDays(1)));
            sagaPort.update(saga);
            log.info("saga {} sales_order={} → stock_reservation_requested (all {} replenishment line(s) fulfilled; retrying reservation)",
                saga.sagaId(), salesOrderHeaderId, data.outstandingReplenishmentLineIds().size());
            return STOCK_RESERVATION_REQUESTED;
        }

        // Partial fulfilment — stay parked, decrement the outstanding set.
        writeData(saga, updated);
        sagaPort.update(saga);
        log.info("saga {} sales_order={} replenishment line={} fulfilled; {} of {} remaining",
            saga.sagaId(), salesOrderHeaderId, salesOrderLineId,
            updated.outstandingReplenishmentLineIds().size(),
            data.outstandingReplenishmentLineIds().size());
        return saga.state();
    }

    @Override
    @Transactional
    public String applyReplenishmentCancelled(UUID salesOrderHeaderId, UUID salesOrderLineId, String reason) {
        SalesOrderFulfilmentSaga saga = requireSaga(salesOrderHeaderId, ReplenishmentCancelled.EVENT_TYPE);

        // A short line whose replenishment was cancelled (unsourceable / no BOM
        // / no vendor) can never be fulfilled — reject the whole order (any one
        // un-fulfillable line rejects it). Only valid while
        // parked awaiting replenishment; late deliveries are no-ops.
        if (!STOCK_RESERVATION_INCOMPLETE.equals(saga.state())) {
            log.debug("saga {} sales_order={} ignoring replenishment-cancelled (state={}, line={})",
                saga.sagaId(), salesOrderHeaderId, saga.state(), salesOrderLineId);
            return saga.state();
        }
        saga.transitionTo(REJECTED, "replenishment_cancelled");
        sagaPort.update(saga);
        log.info("saga {} sales_order={} → rejected (replenishment cancelled for line={}: {})",
            saga.sagaId(), salesOrderHeaderId, salesOrderLineId, reason);
        return REJECTED;
    }

    @Override
    @Transactional
    public String applyShipmentPosted(UUID salesOrderHeaderId) {
        SalesOrderFulfilmentSaga saga = requireSaga(salesOrderHeaderId, ShipmentPosted.EVENT_TYPE);

        if (!READY_TO_SHIP.equals(saga.state())) {
            log.debug("saga {} sales_order={} not in ready_to_ship (state={}); ignoring",
                saga.sagaId(), salesOrderHeaderId, saga.state());
            return saga.state();
        }
        // For prepayment orders, the invoice was created at placement and paid
        // before shipment — there's no invoice / payment event still to wait
        // for. Walk goods_shipped → completed in this same transaction so the
        // saga lands on the right terminal without an active state hop the
        // worker would otherwise pick up.
        //
        // COD orders settle AT shipment — finance auto-creates the invoice and
        // auto-records the full customer payment the moment the SalesOrderShipped
        // lands. So the saga is self-contained: walk goods_shipped →
        // invoice_created → completed in this transaction
        // rather than wait for finance's CustomerInvoiceCreated /
        // CustomerPaymentReceived (which would otherwise race — they carry
        // different aggregate-ids / partition keys, so out-of-order delivery
        // could strand the saga). Those events still arrive and update
        // reporting; the saga is terminal by then and ignores them.
        String pt = readData(saga).paymentTerms();
        if (PaymentTerms.PREPAYMENT.dbValue().equals(pt)) {
            saga.transitionTo(GOODS_SHIPPED, "prepayment_shipment_landed");
            saga.transitionTo(COMPLETED, "o2c_completed");
            sagaPort.update(saga);
            log.info("saga {} sales_order={} → goods_shipped → completed (prepayment; invoice + payment already settled)",
                saga.sagaId(), salesOrderHeaderId);
        } else if (PaymentTerms.CASH_ON_DELIVERY.dbValue().equals(pt)) {
            saga.transitionTo(GOODS_SHIPPED, "cod_shipment_landed");
            saga.transitionTo(INVOICE_CREATED, "cod_invoiced_at_shipment");
            saga.transitionTo(COMPLETED, "o2c_completed");
            sagaPort.update(saga);
            log.info("saga {} sales_order={} → goods_shipped → invoice_created → completed (COD; invoice + payment auto-recorded at shipment)",
                saga.sagaId(), salesOrderHeaderId);
        } else {
            saga.transitionTo(GOODS_SHIPPED, "wait_for_invoice");
            sagaPort.update(saga);
            log.info("saga {} sales_order={} → goods_shipped",
                saga.sagaId(), salesOrderHeaderId);
        }
        return saga.state();
    }

    @Override
    @Transactional
    public String applyCustomerInvoiceCreated(UUID salesOrderHeaderId) {
        SalesOrderFulfilmentSaga saga = requireSaga(salesOrderHeaderId, CustomerInvoiceCreated.EVENT_TYPE);
        // Two source states converge on invoice_created. On-shipment path:
        // goods_shipped → invoice_created (finance auto-created the invoice
        // from SalesOrderShipped). Prepayment path: awaiting_prepayment_invoice
        // → invoice_created (finance built the invoice from
        // PrepaymentInvoiceRequested before any stock movement). The deposit
        // invoice (created from DepositInvoiceRequested at placement) parks the
        // saga at deposit_invoiced awaiting the deposit payment — distinct from
        // the on-shipment / prepayment invoice cycle.
        if (AWAITING_DEPOSIT_INVOICE.equals(saga.state())) {
            saga.transitionTo(DEPOSIT_INVOICED, "wait_for_deposit_payment");
            sagaPort.update(saga);
            log.info("saga {} sales_order={} awaiting_deposit_invoice → deposit_invoiced",
                saga.sagaId(), salesOrderHeaderId);
        } else if (GOODS_SHIPPED.equals(saga.state()) || AWAITING_PREPAYMENT_INVOICE.equals(saga.state())) {
            String fromState = saga.state();
            saga.transitionTo(INVOICE_CREATED, "wait_for_payment");
            sagaPort.update(saga);
            log.info("saga {} sales_order={} {} → invoice_created",
                saga.sagaId(), salesOrderHeaderId, fromState);
        } else {
            log.debug("saga {} sales_order={} not in goods_shipped / awaiting_prepayment_invoice / awaiting_deposit_invoice (state={}); ignoring",
                saga.sagaId(), salesOrderHeaderId, saga.state());
        }
        return saga.state();
    }

    @Override
    @Transactional
    public String applyCustomerPaymentReceived(UUID salesOrderHeaderId, boolean fullySettled) {
        SalesOrderFulfilmentSaga saga = requireSaga(salesOrderHeaderId, CustomerPaymentReceived.EVENT_TYPE);

        // A payment against the DEPOSIT invoice (saga parked at deposit_invoiced)
        // settles the up-front portion. Full settlement → deposit_paid (an ACTIVE
        // checkpoint, like prepaid: the worker picks the saga up to request stock
        // reservation). The balance invoice/payment cycle after shipment is the
        // on-shipment tail handled below.
        if (DEPOSIT_INVOICED.equals(saga.state())) {
            if (fullySettled) {
                saga.transitionTo(DEPOSIT_PAID, "wait_for_worker_pickup");
                saga.parkUntil(Instant.now());
                sagaPort.update(saga);
                log.info("saga {} sales_order={} → deposit_paid (deposit invoice fully paid; worker continues forward)",
                    saga.sagaId(), salesOrderHeaderId);
            } else {
                log.info("saga {} sales_order={} deposit invoice partially paid; staying at deposit_invoiced",
                    saga.sagaId(), salesOrderHeaderId);
            }
            return saga.state();
        }

        if (!INVOICE_CREATED.equals(saga.state()) && !INVOICE_PARTIALLY_PAID.equals(saga.state())) {
            log.debug("saga {} sales_order={} not in payment-receivable state (state={}); ignoring",
                saga.sagaId(), salesOrderHeaderId, saga.state());
        } else if (fullySettled) {
            // Full settlement routes by payment_terms. on_shipment → completed
            // (the existing happy-path terminal — the invoice was created after
            // shipment, so payment closes O2C). prepayment → prepaid (an ACTIVE
            // checkpoint; the worker picks the saga up from prepaid to emit
            // StockReservationRequested and walk the rest of the fulfilment path).
            // Legacy sagas (paymentTerms null) fall back to on_shipment.
            String pt = readData(saga).paymentTerms();
            boolean isPrepayment = PaymentTerms.PREPAYMENT.dbValue().equals(pt);
            if (isPrepayment) {
                saga.transitionTo(PREPAID, "wait_for_worker_pickup");
                saga.parkUntil(Instant.now());
                sagaPort.update(saga);
                log.info("saga {} sales_order={} → prepaid (prepayment invoice fully paid; worker continues forward)",
                    saga.sagaId(), salesOrderHeaderId);
            } else {
                saga.transitionTo(COMPLETED, "o2c_completed");
                sagaPort.update(saga);
                log.info("saga {} sales_order={} → completed (fully settled)",
                    saga.sagaId(), salesOrderHeaderId);
            }
        } else {
            saga.transitionTo(INVOICE_PARTIALLY_PAID, "wait_for_remaining_payments");
            sagaPort.update(saga);
            log.info("saga {} sales_order={} → invoice_partially_paid (partial)",
                saga.sagaId(), salesOrderHeaderId);
        }
        return saga.state();
    }

    @Override
    @Transactional
    public String applyInventoryCancellationApplied(UUID salesOrderHeaderId) {
        SalesOrderFulfilmentSaga saga = requireSaga(salesOrderHeaderId,
            InventorySalesOrderCancellationApplied.EVENT_TYPE);
        FulfilmentSagaData data = readData(saga).withInventoryCancellationAcked();
        writeData(saga, data);
        sagaPort.update(saga);

        if (data.cancellationAcked() && COMPENSATING.equals(saga.state())) {
            saga.transitionTo(COMPENSATED, "cancelled");
            sagaPort.update(saga);
            log.info("saga {} sales_order={} → compensated (inventory ack triggered completion)",
                saga.sagaId(), salesOrderHeaderId);
        }
        return saga.state();
    }

    // ============================================================
    // Internal helpers
    // ============================================================

    private SalesOrderFulfilmentSaga requireSaga(UUID salesOrderId, String eventName) {
        return sagaPort.findBySalesOrderId(salesOrderId)
            .orElseThrow(() -> new IllegalStateException(
                "No fulfilment saga for sales_order_header_id=" + salesOrderId
                    + "; cannot apply " + eventName));
    }

    private FulfilmentSagaData readData(SalesOrderFulfilmentSaga saga) {
        String raw = saga.dataJson();
        if (raw == null || raw.isBlank() || "{}".equals(raw.trim())) {
            return FulfilmentSagaData.none();
        }
        try {
            return json.readValue(raw, FulfilmentSagaData.class);
        } catch (JacksonException e) {
            throw new IllegalStateException("Cannot parse saga.data: " + raw, e);
        }
    }

    private void writeData(SalesOrderFulfilmentSaga saga, FulfilmentSagaData data) {
        saga.setDataJson(json.writeValueAsString(data));
    }
}
