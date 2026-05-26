package com.northwood.sales.infrastructure.saga;

import com.northwood.finance.domain.events.CustomerInvoiceCreated;
import com.northwood.finance.domain.events.CustomerPaymentReceived;
import com.northwood.inventory.domain.events.InventorySalesOrderCancellationApplied;
import com.northwood.inventory.domain.events.ShipmentPosted;
import com.northwood.inventory.domain.events.StockReserved;
import com.northwood.manufacturing.domain.events.ManufacturingDispatched;
import com.northwood.manufacturing.domain.events.ManufacturingSalesOrderCancellationApplied;
import com.northwood.manufacturing.domain.events.WorkOrderCreated;
import com.northwood.manufacturing.domain.events.WorkOrderManufacturingCompleted;
import com.northwood.sales.application.saga.SalesOrderFulfilmentSagaManager;
import com.northwood.sales.application.saga.SalesOrderFulfilmentSagaPort;
import com.northwood.sales.domain.saga.FulfilmentSagaData;
import com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga;
import com.northwood.shared.application.saga.SagaManager;
import com.northwood.shared.domain.Assert;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
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
 * <p>Apply methods take saga-relevant primitives (UUIDs, ints, strings,
 * maps), return the saga's new state. Callers gate post-saga side effects
 * on the returned state — e.g. {@code "compensated"} triggers
 * {@code SalesOrderCompensated} emission; {@code "stock_reservation_failed"}
 * triggers a {@code rejected} status projection.
 */
@Service
public class JdbcSalesOrderFulfilmentSagaManager
    extends SagaManager<SalesOrderFulfilmentSaga, SalesOrderFulfilmentSagaPort>
    implements SalesOrderFulfilmentSagaManager {

    private static final Set<String> COMPLETION_ADVANCEABLE_STATES = Set.of(
        MANUFACTURING_REQUESTED, MANUFACTURING_IN_PROGRESS, MANUFACTURING_COMPLETED
    );

    private final ObjectMapper json;

    /**
     * Lease + backoff durations are overridable via
     * {@code northwood.saga.lease-ttl-seconds} (default 30s) and
     * {@code northwood.saga.retry-backoff-seconds} (default 15s) — §2.13.
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
        return Set.of(STARTED, STOCK_RESERVED);
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
        Map<Integer, BigDecimal> shortageByLineNumber
    ) {
        SalesOrderFulfilmentSaga saga = requireSaga(salesOrderHeaderId, StockReserved.EVENT_TYPE);
        if (StockReserved.STATUS_RESERVED.equals(reservationStatus)) {
            // Full stock cover — every line is already reserved against
            // stock_balance, so there is nothing to manufacture. Skip the
            // manufacturing leg entirely and wait for ShipmentPosted.
            saga.transitionTo(READY_TO_SHIP, "wait_for_shipment");
            sagaPort.update(saga);
            log.info("saga {} sales_order={} status={} → ready_to_ship (full reservation, manufacturing skipped)",
                saga.sagaId(), salesOrderHeaderId, reservationStatus);
        } else {
            // partially_reserved / failed must carry a per-line shortage
            // map; the inventory side has nothing meaningful to say
            // otherwise. Fail loudly rather than stash an empty map and
            // let the worker discover the anomaly via its WARN guard.
            Assert.stateNotEmpty(shortageByLineNumber, StockReserved.EVENT_TYPE + " status=" + reservationStatus + " for sales_order="
                    + salesOrderHeaderId + " arrived without a per-line shortage map. "
                    + "Inventory must include shortageByLineNumber for partially_reserved / failed outcomes.");
            stashShortage(saga, shortageByLineNumber);
            saga.transitionTo(STOCK_RESERVED, "wait_for_next_step");
            saga.parkUntil(Instant.now());
            sagaPort.update(saga);
            log.info("saga {} sales_order={} status={} → stock_reserved",
                saga.sagaId(), salesOrderHeaderId, reservationStatus);
        }
        return saga.state();
    }

    @Override
    @Transactional
    public String applyWorkOrderCreated(UUID salesOrderHeaderId, UUID workOrderId, UUID parentWorkOrderId) {
        if (parentWorkOrderId != null) {
            log.debug("sub-assembly work_order={} (parent={}); not tracked at sales level",
                workOrderId, parentWorkOrderId);
            return null;
        }
        SalesOrderFulfilmentSaga saga = requireSaga(salesOrderHeaderId, WorkOrderCreated.EVENT_TYPE);

        FulfilmentSagaData updated = readData(saga).withWorkOrderCreated(workOrderId);
        writeData(saga, updated);

        if (MANUFACTURING_REQUESTED.equals(saga.state())) {
            saga.transitionTo(MANUFACTURING_IN_PROGRESS, "wait_for_work_order_completion");
            log.info("saga {} sales_order={} → manufacturing_in_progress (work_order={}, outstanding={})",
                saga.sagaId(), salesOrderHeaderId, workOrderId, updated.outstandingWorkOrderIds().size());
        } else {
            log.info("saga {} sales_order={} registered work_order={} (state={}, outstanding={})",
                saga.sagaId(), salesOrderHeaderId, workOrderId,
                saga.state(), updated.outstandingWorkOrderIds().size());
        }
        sagaPort.update(saga);
        return saga.state();
    }

    @Override
    @Transactional
    public String applyWorkOrderManufacturingCompleted(
        UUID salesOrderHeaderId, UUID workOrderId, UUID parentWorkOrderId
    ) {
        if (parentWorkOrderId != null) {
            log.debug("sub-assembly work_order={} (parent={}) completion; not tracked at sales level",
                workOrderId, parentWorkOrderId);
            return null;
        }
        SalesOrderFulfilmentSaga saga = requireSaga(salesOrderHeaderId, WorkOrderManufacturingCompleted.EVENT_TYPE);

        FulfilmentSagaData updated = readData(saga).withWorkOrderCompleted(workOrderId);
        writeData(saga, updated);

        if (COMPLETION_ADVANCEABLE_STATES.contains(saga.state()) && updated.allWorkOrdersComplete()) {
            saga.transitionTo(READY_TO_SHIP, "wait_for_shipment");
            log.info("saga {} sales_order={} → ready_to_ship (last work_order={}, completed={})",
                saga.sagaId(), salesOrderHeaderId, workOrderId, updated.completedWorkOrderIds().size());
        } else if (COMPLETION_ADVANCEABLE_STATES.contains(saga.state())) {
            log.info("saga {} sales_order={} work_order={} completed; outstanding={}, holding at {}",
                saga.sagaId(), salesOrderHeaderId, workOrderId,
                updated.outstandingWorkOrderIds().size(), saga.state());
        } else {
            log.debug("saga {} sales_order={} already past manufacturing (state={}); recording only",
                saga.sagaId(), salesOrderHeaderId, saga.state());
        }
        sagaPort.update(saga);
        return saga.state();
    }

    @Override
    @Transactional
    public String applyManufacturingDispatched(UUID salesOrderHeaderId, int acceptedCount, int totalLines) {
        SalesOrderFulfilmentSaga saga = requireSaga(salesOrderHeaderId, ManufacturingDispatched.EVENT_TYPE);

        // Policy (§4.2 closure, 2026-05-15): ANY rejected line rejects the
        // whole order. Northwood's saga goal — "fail clearly with no
        // half-fulfilled state" — used to be silently broken by partial
        // dispatch: 2 of 3 lines accepted advanced the saga with
        // expected_wo_count=2, dropping the rejected line. The caller
        // (ManufacturingDispatchedHandler) is responsible for releasing any
        // partial stock reservation + cancelling the make-to-order sagas
        // already started for the accepted lines via
        // SalesOrderCompensationEmitter.emitCancellationRequest.
        boolean anyRejected = acceptedCount < totalLines;

        if (anyRejected && MANUFACTURING_REQUESTED.equals(saga.state())) {
            String step = acceptedCount == 0
                ? "no_manufacturable_lines"
                : "partial_dispatch_rejection";
            saga.transitionTo(STOCK_RESERVATION_FAILED, step);
            sagaPort.update(saga);
            log.info("saga {} sales_order={} → stock_reservation_failed ({} accepted, {} rejected; step={})",
                saga.sagaId(), salesOrderHeaderId, acceptedCount, totalLines - acceptedCount, step);
        } else if (anyRejected) {
            log.warn("saga {} sales_order={} dispatched with rejections but state={} — informational only",
                saga.sagaId(), salesOrderHeaderId, saga.state());
        } else {
            FulfilmentSagaData updated = readData(saga).withExpectedWorkOrderCount(acceptedCount);
            writeData(saga, updated);
            sagaPort.update(saga);
            log.info("saga {} sales_order={} dispatched ({} accepted, 0 rejected); expected_wo_count={} stamped",
                saga.sagaId(), salesOrderHeaderId, acceptedCount, acceptedCount);
        }
        return saga.state();
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
        saga.transitionTo(GOODS_SHIPPED, "wait_for_invoice");
        sagaPort.update(saga);
        log.info("saga {} sales_order={} → goods_shipped",
            saga.sagaId(), salesOrderHeaderId);
        return saga.state();
    }

    @Override
    @Transactional
    public String applyCustomerInvoiceCreated(UUID salesOrderHeaderId) {
        SalesOrderFulfilmentSaga saga = requireSaga(salesOrderHeaderId, CustomerInvoiceCreated.EVENT_TYPE);
        if (GOODS_SHIPPED.equals(saga.state())) {
            saga.transitionTo(INVOICE_CREATED, "wait_for_payment");
            sagaPort.update(saga);
            log.info("saga {} sales_order={} → invoice_created",
                saga.sagaId(), salesOrderHeaderId);
        } else {
            log.debug("saga {} sales_order={} not in goods_shipped (state={}); ignoring",
                saga.sagaId(), salesOrderHeaderId, saga.state());
        }
        return saga.state();
    }

    @Override
    @Transactional
    public String applyCustomerPaymentReceived(UUID salesOrderHeaderId, boolean fullySettled) {
        SalesOrderFulfilmentSaga saga = requireSaga(salesOrderHeaderId, CustomerPaymentReceived.EVENT_TYPE);

        if (!INVOICE_CREATED.equals(saga.state()) && !INVOICE_PAID.equals(saga.state())) {
            log.debug("saga {} sales_order={} not in payment-receivable state (state={}); ignoring",
                saga.sagaId(), salesOrderHeaderId, saga.state());
        } else if (fullySettled) {
            saga.transitionTo(COMPLETED, "o2c_completed");
            sagaPort.update(saga);
            log.info("saga {} sales_order={} → completed (fully settled)",
                saga.sagaId(), salesOrderHeaderId);
        } else {
            saga.transitionTo(INVOICE_PAID, "wait_for_remaining_payments");
            sagaPort.update(saga);
            log.info("saga {} sales_order={} → invoice_paid (partial)",
                saga.sagaId(), salesOrderHeaderId);
        }
        return saga.state();
    }

    @Override
    @Transactional
    public String applyInventoryCancellationApplied(UUID salesOrderHeaderId) {
        return recordCompensationAck(salesOrderHeaderId, true,
            InventorySalesOrderCancellationApplied.EVENT_TYPE);
    }

    @Override
    @Transactional
    public String applyManufacturingCancellationApplied(UUID salesOrderHeaderId) {
        return recordCompensationAck(salesOrderHeaderId, false,
            ManufacturingSalesOrderCancellationApplied.EVENT_TYPE);
    }

    private String recordCompensationAck(UUID salesOrderHeaderId, boolean inventorySide, String eventName) {
        SalesOrderFulfilmentSaga saga = requireSaga(salesOrderHeaderId, eventName);
        FulfilmentSagaData data = inventorySide
            ? readData(saga).withInventoryCancellationAcked()
            : readData(saga).withManufacturingCancellationAcked();
        writeData(saga, data);
        sagaPort.update(saga);

        if (data.bothCancellationAcksReceived() && COMPENSATING.equals(saga.state())) {
            saga.transitionTo(COMPENSATED, "cancelled");
            sagaPort.update(saga);
            log.info("saga {} sales_order={} → compensated ({} ack triggered completion)",
                saga.sagaId(), salesOrderHeaderId, eventName);
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

    private void stashShortage(SalesOrderFulfilmentSaga saga, Map<Integer, BigDecimal> shortageByLineNumber) {
        FulfilmentSagaData existing = readData(saga);
        writeData(saga, new FulfilmentSagaData(
            shortageByLineNumber,
            existing.expectedWorkOrderCount(),
            existing.outstandingWorkOrderIds(),
            existing.completedWorkOrderIds(),
            existing.inventoryCancellationAcked(),
            existing.manufacturingCancellationAcked()
        ));
    }
}
