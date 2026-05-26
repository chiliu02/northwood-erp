package com.northwood.manufacturing.infrastructure.saga;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.northwood.manufacturing.application.WorkOrderReleaseService;
import com.northwood.manufacturing.application.dto.ReleaseCommand;
import com.northwood.sales.domain.events.ManufacturingRequested.RequestedLine;
import com.northwood.manufacturing.application.saga.MakeToOrderSagaManager;
import com.northwood.manufacturing.domain.WorkOrder;
import com.northwood.manufacturing.domain.WorkOrderId;
import com.northwood.manufacturing.domain.WorkOrderMaterial;
import com.northwood.manufacturing.domain.WorkOrderRepository;
import com.northwood.inventory.domain.WarehouseCodes;
import com.northwood.manufacturing.domain.events.RawMaterialReservationRequested;
import com.northwood.manufacturing.domain.events.RawMaterialReservationRequested.RequestedComponent;
import com.northwood.manufacturing.domain.saga.MakeToOrderSaga;
import static com.northwood.manufacturing.domain.saga.MakeToOrderSaga.RAW_MATERIAL_RESERVATION_REQUESTED;
import static com.northwood.manufacturing.domain.saga.MakeToOrderSaga.STARTED;
import static com.northwood.manufacturing.domain.saga.MakeToOrderSaga.WORK_ORDER_CREATED;
import com.northwood.shared.domain.Assert;
import com.northwood.shared.application.outbox.OutboxAppender;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Spring scheduling glue + worker-driven advance for the make-to-order saga.
 * Holds the worker-driven side effects (WO load + outbox emission of
 * {@code RawMaterialReservationRequested}); the saga state machine lives
 * on {@link MakeToOrderSagaManager}.
 *
 * <p>Worker-driven advances:
 * <ul>
 *   <li>{@code started → work_order_created}: deserialise the requested line
 *       from saga.data, call {@code WorkOrderReleaseService.release(...)}
 *       (which spawns child sagas for sub-assemblies via the manager too),
 *       attach the WO id to the saga, transition to {@code work_order_created}.
 *       Doesn't park — next tick claims and advances.</li>
 *   <li>{@code work_order_created → raw_material_reservation_requested}: load
 *       the WO, emit {@code RawMaterialReservationRequested}, transition the
 *       saga, park.</li>
 * </ul>
 */
@Component
public class MakeToOrderSagaWorker {

    private static final Logger log = LoggerFactory.getLogger(MakeToOrderSagaWorker.class);
    private static final int BATCH_SIZE = 10;

    private final String workerId =
        "manufacturing.mto-worker@" + ManagementFactory.getRuntimeMXBean().getName();

    private final MakeToOrderSagaManager manager;
    private final WorkOrderReleaseService release;
    private final WorkOrderRepository workOrders;
    private final OutboxAppender outbox;
    private final ObjectMapper json;

    public MakeToOrderSagaWorker(
        MakeToOrderSagaManager manager,
        WorkOrderReleaseService release,
        WorkOrderRepository workOrders,
        OutboxAppender outbox,
        ObjectMapper json
    ) {
        this.manager = manager;
        this.release = release;
        this.workOrders = workOrders;
        this.outbox = outbox;
        this.json = json;
    }

    @Scheduled(fixedDelayString = "${northwood.saga.poll-interval:1000}")
    public void poll() {
        manager.drain(BATCH_SIZE, workerId, this::advance);
    }

    /** Test-side hook: drive one batch with a deterministic worker id. */
    public void drainOnce(String workerId) {
        manager.drain(BATCH_SIZE, workerId, this::advance);
    }

    private void advance(MakeToOrderSaga saga) {
        switch (saga.state()) {
            case STARTED -> releaseWorkOrder(saga);
            case WORK_ORDER_CREATED -> requestRawMaterialReservation(saga);
            default -> log.debug("[{}] no transition implemented for state {}", workerId, saga.state());
        }
    }

    private void releaseWorkOrder(MakeToOrderSaga saga) {
        RequestedLine line;
        try {
            line = json.readValue(saga.dataJson(), RequestedLine.class);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to deserialise saga data for " + saga.sagaId(), e);
        }

        WorkOrder workOrder = release.release(new ReleaseCommand(
            WorkOrder.NUMBER_PREFIX + UUID.randomUUID().toString().substring(0, WorkOrder.NUMBER_SUFFIX_LENGTH).toUpperCase(),
            saga.salesOrderHeaderId(),
            saga.salesOrderLineId(),
            null,
            line.productId(),
            line.productSku(),
            line.productName(),
            line.orderedQuantity()
        ));

        saga.attachWorkOrder(workOrder.id().value());
        saga.transitionTo(WORK_ORDER_CREATED, "wait_for_raw_material_reservation");
        // Don't park — next_retry_at = now from transitionTo, so the next
        // tick claims the saga and runs requestRawMaterialReservation.

        log.info("[{}] saga {} sales_order_line={} → work_order_created (work_order={})",
            workerId, saga.sagaId(), saga.salesOrderLineId(), workOrder.id().value());
    }

    private void requestRawMaterialReservation(MakeToOrderSaga saga) {
        UUID workOrderId = saga.workOrderId();
        Assert.stateNotNull(workOrderId, "saga " + saga.sagaId() + " is in work_order_created but has no work_order_id");
        WorkOrder workOrder = workOrders.findById(WorkOrderId.of(workOrderId))
            .orElseThrow(() -> new IllegalStateException(
                "no work order " + workOrderId + " for saga " + saga.sagaId()
            ));

        List<RequestedComponent> components = new ArrayList<>();
        for (WorkOrderMaterial m : workOrder.materials()) {
            components.add(new RequestedComponent(
                m.id(),
                m.componentProductId(),
                m.componentSku(),
                m.componentName(),
                m.requiredQuantity()
            ));
        }

        RawMaterialReservationRequested event = new RawMaterialReservationRequested(
            UUID.randomUUID(),
            workOrderId,
            workOrderId,
            workOrder.salesOrderHeaderId(),
            workOrder.salesOrderLineId(),
            WarehouseCodes.MAIN,
            components,
            Instant.now()
        );
        outbox.append(event, WorkOrder.AGGREGATE_TYPE);

        saga.transitionTo(RAW_MATERIAL_RESERVATION_REQUESTED, "wait_for_raw_materials_reserved");
        saga.parkUntil(Instant.now().plus(Duration.ofDays(1)));

        log.info("[{}] saga {} work_order={} → raw_material_reservation_requested ({} component(s))",
            workerId, saga.sagaId(), workOrderId, components.size());
    }

}
