package com.northwood.manufacturing.infrastructure.saga;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.northwood.inventory.domain.events.RawMaterialsReserved;
import com.northwood.manufacturing.application.saga.MakeToOrderSagaManager;
import com.northwood.manufacturing.domain.saga.MakeToOrderSaga;
import static com.northwood.manufacturing.domain.saga.MakeToOrderSaga.*;
import com.northwood.manufacturing.application.saga.MakeToOrderSagaPort;
import com.northwood.shared.application.saga.SagaManager;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;

/**
 * JDBC-backed make-to-order saga manager. Saga state truth — every transition
 * the saga can take is a method here. Holds <i>only</i> the minimum needed
 * for saga state work: {@link MakeToOrderSagaPort} (inherited as
 * {@code sagaPort}) and {@link ObjectMapper} (saga.data JSON). All side
 * effects (event emission, calls into other services / aggregates) live
 * with the caller — the worker shell for worker-driven advances and the
 * inbox handler shells for inbox-driven advances.
 */
@Service
public class JdbcMakeToOrderSagaManager
    extends SagaManager<MakeToOrderSaga, MakeToOrderSagaPort>
    implements MakeToOrderSagaManager {

    private final ObjectMapper json;

    /**
     * Lease + backoff durations are overridable via
     * {@code northwood.saga.lease-ttl-seconds} (default 30s) and
     * {@code northwood.saga.retry-backoff-seconds} (default 15s) — §2.13.
     */
    public JdbcMakeToOrderSagaManager(
        MakeToOrderSagaPort sagaPort,
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
        return Set.of(STARTED, WORK_ORDER_CREATED);
    }

    // ============================================================
    // Lifecycle
    // ============================================================

    @Override
    @Transactional
    public void insertStarted(UUID salesOrderHeaderId, UUID salesOrderLineId, String dataJson) {
        sagaPort.insert(MakeToOrderSaga.started(salesOrderHeaderId, salesOrderLineId, dataJson));
    }

    @Override
    @Transactional
    public void insertAttachedToWorkOrder(
        UUID salesOrderHeaderId, UUID salesOrderLineId, UUID workOrderId, String dataJson
    ) {
        sagaPort.insert(MakeToOrderSaga.attachedToWorkOrder(
            salesOrderHeaderId, salesOrderLineId, workOrderId, dataJson
        ));
    }

    // ============================================================
    // Inbox-driven transitions
    // ============================================================

    @Override
    @Transactional
    public String applyRawMaterialsReserved(
        UUID workOrderId, String status, Map<UUID, BigDecimal> shortageByProductId
    ) {
        MakeToOrderSaga saga = sagaPort.findByWorkOrderId(workOrderId)
            .orElseThrow(() -> new IllegalStateException(
                "No make-to-order saga for work_order_id=" + workOrderId
                    + "; cannot apply " + RawMaterialsReserved.EVENT_TYPE
            ));

        if (!RAW_MATERIAL_RESERVATION_REQUESTED.equals(saga.state())) {
            log.debug("saga {} work_order={} not in raw_material_reservation_requested (state={}); ignoring",
                saga.sagaId(), workOrderId, saga.state());
            return saga.state();
        }

        String nextState = RawMaterialsReserved.STATUS_RESERVED.equals(status)
            ? RAW_MATERIALS_RESERVED
            : RAW_MATERIAL_SHORTAGE;
        if (RAW_MATERIAL_SHORTAGE.equals(nextState)
            && shortageByProductId != null && !shortageByProductId.isEmpty()) {
            stashShortageOnSaga(saga, shortageByProductId);
        }
        saga.transitionTo(nextState, nextState);
        sagaPort.save(saga);
        log.info("saga {} work_order={} status={} → {}",
            saga.sagaId(), workOrderId, status, nextState);
        return saga.state();
    }

    @Override
    @Transactional
    public String unparkOrNarrowShortage(UUID sagaId, Map<UUID, BigDecimal> receivedByProductId) {
        MakeToOrderSaga saga = sagaPort.findBySagaId(sagaId).orElse(null);
        if (saga == null || !RAW_MATERIAL_SHORTAGE.equals(saga.state())) {
            return null;
        }
        UnparkDecision decision = decideUnpark(saga, receivedByProductId);
        switch (decision) {
            case UNPARK -> {
                saga.transitionTo(WORK_ORDER_CREATED, "retry_raw_material_reservation");
                sagaPort.save(saga);
                log.info("un-parked saga {} work_order={} (shortage fully covered)",
                    saga.sagaId(), saga.workOrderId());
            }
            case NARROW -> {
                sagaPort.save(saga);
                log.info("narrowed shortage on saga {} work_order={} (still partially short)",
                    saga.sagaId(), saga.workOrderId());
            }
            case NONE -> {
                return null;
            }
        }
        return saga.state();
    }

    @Override
    @Transactional
    public String applyManufacturingCompleted(UUID workOrderId) {
        MakeToOrderSaga saga = sagaPort.findByWorkOrderId(workOrderId).orElse(null);
        if (saga == null) {
            log.warn("no make-to-order saga found for work_order={}; skipping saga advancement", workOrderId);
            return null;
        }
        if (saga.terminalStates().contains(saga.state())) {
            log.debug("saga {} already terminal in state {}; nothing to do", saga.sagaId(), saga.state());
            return saga.state();
        }
        saga.transitionTo(COMPLETED, "production_completed");
        sagaPort.save(saga);
        log.info("saga {} (work_order={}) → completed", saga.sagaId(), workOrderId);
        return saga.state();
    }

    @Override
    @Transactional
    public String cancelForWorkOrder(UUID workOrderId) {
        MakeToOrderSaga saga = sagaPort.findByWorkOrderId(workOrderId).orElse(null);
        if (saga == null) {
            return null;
        }
        if (saga.terminalStates().contains(saga.state())) {
            return saga.state();
        }
        saga.transitionTo(COMPENSATED, "cancelled_via_sales");
        sagaPort.save(saga);
        log.info("saga {} (work_order={}) → compensated (cancelled via sales)",
            saga.sagaId(), workOrderId);
        return saga.state();
    }

    // ============================================================
    // Internal helpers
    // ============================================================

    private enum UnparkDecision { UNPARK, NARROW, NONE }

    private UnparkDecision decideUnpark(MakeToOrderSaga saga, Map<UUID, BigDecimal> receivedByProductId) {
        Map<String, Object> data;
        String existing = saga.dataJson();
        if (existing == null || existing.isBlank() || "{}".equals(existing.trim())) {
            return UnparkDecision.UNPARK;  // no data stashed → legacy behaviour
        }
        try {
            data = json.readValue(existing, new TypeReference<Map<String, Object>>() {});
        } catch (JacksonException e) {
            throw new IllegalStateException(
                "Failed to read saga " + saga.sagaId() + " data for un-park decision", e);
        }
        Object stash = data.get("shortageByProductId");
        if (!(stash instanceof Map<?, ?> rawMap)) {
            return UnparkDecision.UNPARK;  // legacy: no shortage info → coarse behaviour
        }

        Map<String, String> updated = new LinkedHashMap<>();
        boolean touched = false;
        boolean allCovered = true;
        for (Map.Entry<?, ?> e : rawMap.entrySet()) {
            String pidStr = e.getKey().toString();
            BigDecimal remaining = new BigDecimal(e.getValue().toString());
            UUID productId = UUID.fromString(pidStr);
            BigDecimal received = receivedByProductId.get(productId);
            if (received != null && received.signum() > 0) {
                remaining = remaining.subtract(received).max(BigDecimal.ZERO);
                touched = true;
            }
            if (remaining.signum() > 0) {
                updated.put(pidStr, remaining.toPlainString());
                allCovered = false;
            }
        }
        if (!touched) {
            return UnparkDecision.NONE;
        }
        data.put("shortageByProductId", updated);
        saga.setDataJson(json.writeValueAsString(data));
        return allCovered ? UnparkDecision.UNPARK : UnparkDecision.NARROW;
    }

    private void stashShortageOnSaga(MakeToOrderSaga saga, Map<UUID, BigDecimal> shortageByProductId) {
        Map<String, Object> data;
        String existing = saga.dataJson();
        if (existing == null || existing.isBlank() || "{}".equals(existing.trim())) {
            data = new LinkedHashMap<>();
        } else {
            try {
                data = json.readValue(existing, new TypeReference<Map<String, Object>>() {});
            } catch (JacksonException e) {
                throw new IllegalStateException(
                    "Failed to read saga " + saga.sagaId() + " data for shortage stash", e);
            }
        }
        Map<String, String> shortageJson = new LinkedHashMap<>();
        for (Map.Entry<UUID, BigDecimal> e : shortageByProductId.entrySet()) {
            shortageJson.put(e.getKey().toString(), e.getValue().toPlainString());
        }
        data.put("shortageByProductId", shortageJson);
        saga.setDataJson(json.writeValueAsString(data));
    }
}
