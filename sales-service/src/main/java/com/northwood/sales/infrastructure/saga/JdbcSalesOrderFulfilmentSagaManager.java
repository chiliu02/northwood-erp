package com.northwood.sales.infrastructure.saga;

import com.northwood.finance.domain.events.CustomerPaymentReceived;
import com.northwood.inventory.domain.events.InventorySalesOrderCancellationApplied;
import com.northwood.inventory.domain.events.ReplenishmentCancelled;
import com.northwood.inventory.domain.events.ReplenishmentFulfilled;
import com.northwood.inventory.domain.events.SalesOrderLineReservationChanged;
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

    /** Saga states in which a line-amendment reservation reply is reconciled. */
    private static final Set<String> RESERVATION_PHASE_STATES = Set.of(
        STOCK_RESERVATION_REQUESTED, SUPPLY_SECURED, STOCK_RESERVATION_INCOMPLETE
    );

    /**
     * States in which a {@code StockReserved} reply may legitimately advance the
     * saga — the open reservation window ({@code stock_reservation_requested}
     * normally; {@code stock_reservation_incomplete} for the amended-line case
     * below). Outside this set — terminal / {@code compensated}, or already
     * supply-secured / shipped — the reply is a late straggler (e.g. an in-flight
     * reservation landing after the order was cancelled) and must be ignored,
     * never transition the saga forward: re-advancing a compensated/terminal saga
     * to {@code supply_secured} would resurrect a cancelled order. This is the
     * source-state guard every other forward {@code apply*} method already carries.
     */
    private static final Set<String> STOCK_RESERVED_SOURCE_STATES = Set.of(
        STOCK_RESERVATION_REQUESTED, STOCK_RESERVATION_INCOMPLETE
    );

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
        // deposit request; PREPAID → stock-reservation request after the up-front
        // payment settles (prepayment or deposit — the old deposit_paid unified
        // into prepaid). AWAITING_RELEASE → emit the deferred stock reservation
        // once the planning-time-fence release date arrives — woken by wall-clock
        // (next_retry_at <= now()), NOT an inbox event, so it MUST be claimable
        // here or the parked saga never wakes. AWAITING_PREPAYMENT is NOT here —
        // it is a passive gate woken by the up-front payment via the inbox.
        // STOCK_RESERVATION_INCOMPLETE is not in this set — inventory raises the
        // replenishment in-tx, so the worker has nothing to do there; the saga
        // parks until ReplenishmentFulfilled / ReplenishmentCancelled drives it
        // via the inbox.
        return Set.of(STARTED, AWAITING_RELEASE, PREPAID);
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
    @Transactional(readOnly = true)
    public java.util.Optional<String> currentState(UUID salesOrderHeaderId) {
        return sagaPort.findBySalesOrderId(salesOrderHeaderId).map(SalesOrderFulfilmentSaga::state);
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Optional<String> currentPaymentTerms(UUID salesOrderHeaderId) {
        return sagaPort.findBySalesOrderId(salesOrderHeaderId)
            .map(saga -> readData(saga).paymentTerms());
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
        if (!STOCK_RESERVED_SOURCE_STATES.contains(saga.state())) {
            // Late straggler: the saga left the reservation window (e.g. the order
            // was cancelled/compensated, or it already rejected) before this
            // in-flight StockReserved landed. Ignore it — advancing now would
            // resurrect a terminal/compensated saga to supply_secured. Mirrors the
            // source-state guard in applyReplenishment* / applyShipmentPosted.
            log.debug("saga {} sales_order={} ignoring {} (state={} outside the reservation window)",
                saga.sagaId(), salesOrderHeaderId, StockReserved.EVENT_TYPE, saga.state());
            return saga.state();
        }
        if (StockReserved.STATUS_RESERVED.equals(reservationStatus)
            && !readData(saga).outstandingReplenishmentLineIds().isEmpty()) {
            // Ordering guard: a line-amendment reply (SalesOrderLineReservationChanged,
            // partitioned by sales_order_id) can land before this StockReserved
            // (partitioned by reservation_id) and register an outstanding amended
            // short line. Don't clobber that with ready_to_ship — stay parked at
            // stock_reservation_incomplete until the amended line's replenishment
            // drains.
            saga.transitionTo(STOCK_RESERVATION_INCOMPLETE, "wait_for_replenishment");
            saga.parkUntil(Instant.now().plus(Duration.ofDays(1)));
            sagaPort.update(saga);
            log.info("saga {} sales_order={} status=reserved but {} amended line(s) outstanding → stock_reservation_incomplete",
                saga.sagaId(), salesOrderHeaderId, readData(saga).outstandingReplenishmentLineIds().size());
        } else if (StockReserved.STATUS_RESERVED.equals(reservationStatus)) {
            // Full stock cover — every line is already reserved against
            // stock_balance, so there is nothing to replenish. Go straight to
            // supply_secured and wait for the completion gate (ship + pay).
            saga.transitionTo(SUPPLY_SECURED, "wait_for_completion");
            sagaPort.update(saga);
            log.info("saga {} sales_order={} status={} → supply_secured (full reservation)",
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
    public String applyLineReservationChanged(UUID salesOrderHeaderId, UUID salesOrderLineId, boolean lineIsShort) {
        SalesOrderFulfilmentSaga saga = requireSaga(salesOrderHeaderId, SalesOrderLineReservationChanged.EVENT_TYPE);

        // Only meaningful during the reservation phase. Outside it (pre-reservation,
        // shipped+, compensated, terminal) the amendment window is closed and
        // there's nothing to reconcile.
        if (!RESERVATION_PHASE_STATES.contains(saga.state())) {
            log.debug("saga {} sales_order={} ignoring line-reservation-changed (state={}, line={})",
                saga.sagaId(), salesOrderHeaderId, saga.state(), salesOrderLineId);
            return saga.state();
        }

        FulfilmentSagaData data = readData(saga);
        java.util.Set<UUID> outstanding = new java.util.LinkedHashSet<>(data.outstandingReplenishmentLineIds());
        if (lineIsShort) {
            outstanding.add(salesOrderLineId);
            writeData(saga, data.withOutstandingReplenishmentLineIds(outstanding));
            if (!STOCK_RESERVATION_INCOMPLETE.equals(saga.state())) {
                saga.transitionTo(STOCK_RESERVATION_INCOMPLETE, "amended_line_short");
            }
            saga.parkUntil(Instant.now().plus(Duration.ofDays(1)));
            sagaPort.update(saga);
            log.info("saga {} sales_order={} amended line={} short → stock_reservation_incomplete ({} outstanding)",
                saga.sagaId(), salesOrderHeaderId, salesOrderLineId, outstanding.size());
        } else {
            boolean removed = outstanding.remove(salesOrderLineId);
            if (removed) {
                writeData(saga, data.withOutstandingReplenishmentLineIds(outstanding));
            }
            if (outstanding.isEmpty() && STOCK_RESERVATION_INCOMPLETE.equals(saga.state())) {
                saga.transitionTo(SUPPLY_SECURED, "amended_lines_all_reserved");
            }
            sagaPort.update(saga);
            log.info("saga {} sales_order={} amended line={} reserved/released (state={}, {} outstanding)",
                saga.sagaId(), salesOrderHeaderId, salesOrderLineId, saga.state(), outstanding.size());
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
                // make-/buy-to-order: every outstanding line was an
                // order-pegged completion — inventory already reserved each
                // output for its SO line atomically with the stock credit. Ship
                // straight off the peg, no re-reservation (which would re-peg).
                writeData(saga, updated);
                saga.transitionTo(SUPPLY_SECURED, "wait_for_completion");
                sagaPort.update(saga);
                log.info("saga {} sales_order={} → supply_secured (all {} line(s) order-pegged + reserved on completion)",
                    saga.sagaId(), salesOrderHeaderId, data.outstandingReplenishmentLineIds().size());
                return SUPPLY_SECURED;
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
        // Only reject for a line still in the outstanding set. A line removed
        // by amendment is dropped from the set first (the released reply lands
        // before this cancel), so its in-flight replenishment cancellation is a
        // benign no-op here — the order was deliberately amended, not unsourceable.
        if (!readData(saga).outstandingReplenishmentLineIds().contains(salesOrderLineId)) {
            log.debug("saga {} sales_order={} ignoring replenishment-cancelled for non-outstanding line={} (amended away)",
                saga.sagaId(), salesOrderHeaderId, salesOrderLineId);
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
    public String applyShipmentPosted(UUID salesOrderHeaderId, boolean orderFullyShipped) {
        SalesOrderFulfilmentSaga saga = requireSaga(salesOrderHeaderId, ShipmentPosted.EVENT_TYPE);

        // The saga's only post-supply act is the completion gate. The header
        // ship status (shipped / partially_shipped) is owned by the line fold
        // (SalesOrder.recordShipped, applied before this in the handler), not the
        // saga — so a partial shipment is a no-op here. Only the shipment that
        // completes the order latches the orderShipped flag; completion fires
        // when the pay leg has also landed (maybeComplete).
        if (!SUPPLY_SECURED.equals(saga.state())) {
            log.debug("saga {} sales_order={} not in supply_secured (state={}); ignoring shipment",
                saga.sagaId(), salesOrderHeaderId, saga.state());
            return saga.state();
        }
        if (!orderFullyShipped) {
            log.debug("saga {} sales_order={} partial shipment while supply_secured; ship status owned by the line fold, gate waits",
                saga.sagaId(), salesOrderHeaderId);
            return saga.state();
        }
        writeData(saga, readData(saga).withOrderShipped());
        String state = maybeComplete(saga);
        log.info("saga {} sales_order={} order fully shipped → {} (completion gate; settled={})",
            saga.sagaId(), salesOrderHeaderId, state, readData(saga).isOrderSettled());
        return state;
    }

    @Override
    @Transactional
    public String applyCustomerPaymentReceived(UUID salesOrderHeaderId, boolean invoiceFullySettled, boolean orderFullySettled) {
        SalesOrderFulfilmentSaga saga = requireSaga(salesOrderHeaderId, CustomerPaymentReceived.EVENT_TYPE);

        // Up-front-payment gate (prepayment + deposit): the saga parks at
        // awaiting_prepayment until its up-front invoice is fully paid
        // (invoiceFullySettled). On settlement → prepaid (an ACTIVE checkpoint;
        // the worker picks the saga up to request reservation, applying the
        // planning fence). The handler emits SalesOrderPrepaymentSettled on the
        // prepaid return to lift the inventory shipment gate.
        //
        // Latch the completion gate's orderSettled leg here ONLY for prepayment —
        // a full prepayment IS the whole order, so it's genuinely settled. A
        // deposit must NOT latch here: at deposit-payment time the balance invoice
        // doesn't exist yet, so orderFullySettled is spuriously true (the single
        // deposit invoice is fully paid). The deposit's settled leg latches later
        // at supply_secured, when the post-shipment balance payment lands and
        // orderFullySettled is genuinely true across both invoices.
        if (AWAITING_PREPAYMENT.equals(saga.state())) {
            if (invoiceFullySettled) {
                boolean isPrepayment = PaymentTerms.PREPAYMENT.code().equals(readData(saga).paymentTerms());
                if (orderFullySettled && isPrepayment) {
                    writeData(saga, readData(saga).withOrderSettled());
                }
                saga.transitionTo(PREPAID, "wait_for_worker_pickup");
                saga.parkUntil(Instant.now());
                sagaPort.update(saga);
                log.info("saga {} sales_order={} → prepaid (up-front invoice fully paid; worker continues forward; settledLatched={})",
                    saga.sagaId(), salesOrderHeaderId, orderFullySettled && isPrepayment);
            } else {
                log.info("saga {} sales_order={} up-front invoice partially paid; staying at awaiting_prepayment",
                    saga.sagaId(), salesOrderHeaderId);
            }
            return saga.state();
        }

        // Completion gate (pay leg): every invoice for the order is now paid
        // (orderFullySettled — for a deposit order that means deposit AND
        // balance). Latch the settled flag and complete if the order has also
        // shipped. A non-order-settling payment (one of several invoices) is a
        // no-op — the flag stays unset and the gate holds. Only meaningful at
        // supply_secured; outside it (pre-reservation, terminal, compensated)
        // the payment is not the saga's concern.
        if (SUPPLY_SECURED.equals(saga.state())) {
            if (orderFullySettled) {
                writeData(saga, readData(saga).withOrderSettled());
                String state = maybeComplete(saga);
                log.info("saga {} sales_order={} order fully settled → {} (completion gate; shipped={})",
                    saga.sagaId(), salesOrderHeaderId, state, readData(saga).isOrderShipped());
                return state;
            }
            log.debug("saga {} sales_order={} payment received but order not fully settled; gate holds",
                saga.sagaId(), salesOrderHeaderId);
            return saga.state();
        }

        log.debug("saga {} sales_order={} payment received outside the gate (state={}); ignoring",
            saga.sagaId(), salesOrderHeaderId, saga.state());
        return saga.state();
    }

    /**
     * Completion gate. The saga moves {@code supply_secured → completed} once
     * both legs have landed — {@link FulfilmentSagaData#orderShipped} (a
     * completing {@code ShipmentPosted}) and {@link FulfilmentSagaData#orderSettled}
     * (an order-settling {@code CustomerPaymentReceived}). The two events carry
     * different partition keys and may arrive in either order, so each caller
     * latches its flag then calls this; whichever lands second completes. Always
     * persists (the latched flag), so the caller need not update separately.
     */
    private String maybeComplete(SalesOrderFulfilmentSaga saga) {
        if (SUPPLY_SECURED.equals(saga.state()) && readData(saga).isReadyToComplete()) {
            saga.transitionTo(COMPLETED, "o2c_completed");
        }
        sagaPort.update(saga);
        return saga.state();
    }

    @Override
    @Transactional
    public String applyInventoryCancellationApplied(UUID salesOrderHeaderId, Set<String> outstandingCompensationLegIds) {
        SalesOrderFulfilmentSaga saga = requireSaga(salesOrderHeaderId,
            InventorySalesOrderCancellationApplied.EVENT_TYPE);

        // A terminal saga (e.g. 'rejected' via the unsourceable path, which also
        // releases through this ack) is left untouched.
        if (saga.terminalStates().contains(saga.state())) {
            log.debug("saga {} sales_order={} ignoring inventory cancellation ack (terminal state={})",
                saga.sagaId(), salesOrderHeaderId, saga.state());
            return saga.state();
        }

        Set<String> legs = outstandingCompensationLegIds == null ? Set.of() : outstandingCompensationLegIds;
        if (legs.isEmpty()) {
            // Two-phase cancel, common path: nothing order-pegged to withdraw, so
            // inventory's reservation release is the whole undo. Enter compensation
            // directly from the saga's active state — there is no 'compensating'
            // hop, because the cancel request no longer pre-compensates (a shipment
            // could win the race).
            saga.transitionTo(COMPENSATED, "cancelled");
            sagaPort.update(saga);
            log.info("saga {} sales_order={} → compensated (inventory cancellation ack, no pegged legs)",
                saga.sagaId(), salesOrderHeaderId);
            return COMPENSATED;
        }

        // Order-pegged supply was committed (a sent PO and/or a released work
        // order). Stamp the legs and park in 'compensating' until every leg's
        // *CancellationApplied ack drains the set (applyCompensationAck).
        writeData(saga, readData(saga).withOutstandingCompensationLegs(legs));
        saga.transitionTo(COMPENSATING, "await_compensation_legs");
        saga.parkUntil(Instant.now().plus(Duration.ofDays(1)));
        sagaPort.update(saga);
        log.info("saga {} sales_order={} → compensating ({} pegged supply leg(s) to withdraw: {})",
            saga.sagaId(), salesOrderHeaderId, legs.size(), legs);
        return COMPENSATING;
    }

    @Override
    @Transactional
    public String applyCompensationAck(UUID salesOrderHeaderId, String legId, boolean failed) {
        SalesOrderFulfilmentSaga saga = requireSaga(salesOrderHeaderId, "compensation-leg-ack");

        // Only meaningful while draining. A late straggler (saga already terminal,
        // e.g. compensated/compensation_failed after the last leg) is a no-op.
        if (!COMPENSATING.equals(saga.state())) {
            log.debug("saga {} sales_order={} ignoring compensation-leg ack (state={}, leg={})",
                saga.sagaId(), salesOrderHeaderId, saga.state(), legId);
            return saga.state();
        }

        FulfilmentSagaData data = readData(saga);
        if (!data.outstandingCompensationLegs().contains(legId)) {
            // Idempotent: leg already drained (duplicate ack delivery).
            log.debug("saga {} sales_order={} compensation ack for already-drained leg={}; idempotent no-op",
                saga.sagaId(), salesOrderHeaderId, legId);
            return saga.state();
        }

        FulfilmentSagaData updated = data.withCompensationLegAcked(legId, failed);
        writeData(saga, updated);
        if (updated.allCompensationLegsAcked()) {
            String terminal = updated.hasCompensationFailures() ? COMPENSATION_FAILED : COMPENSATED;
            saga.transitionTo(terminal, updated.hasCompensationFailures()
                ? "compensation_failed_uncompensatable_leaf" : "all_legs_compensated");
            sagaPort.update(saga);
            log.info("saga {} sales_order={} compensation leg={} acked (failed={}) → {} (failed legs: {})",
                saga.sagaId(), salesOrderHeaderId, legId, failed, terminal, updated.failedCompensationLegs());
            return terminal;
        }

        // More legs outstanding — stay parked in 'compensating'.
        sagaPort.update(saga);
        log.info("saga {} sales_order={} compensation leg={} acked (failed={}); {} of {} remaining",
            saga.sagaId(), salesOrderHeaderId, legId, failed,
            updated.outstandingCompensationLegs().size(),
            data.outstandingCompensationLegs().size());
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
