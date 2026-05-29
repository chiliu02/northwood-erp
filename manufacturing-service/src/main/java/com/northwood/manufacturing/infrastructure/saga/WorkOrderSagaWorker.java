package com.northwood.manufacturing.infrastructure.saga;

import com.northwood.manufacturing.application.saga.WorkOrderSagaManager;
import com.northwood.manufacturing.domain.WorkOrder;
import com.northwood.manufacturing.domain.WorkOrderId;
import com.northwood.manufacturing.domain.WorkOrderMaterial;
import com.northwood.manufacturing.domain.WorkOrderRepository;
import com.northwood.inventory.domain.WarehouseCodes;
import com.northwood.manufacturing.domain.events.RawMaterialReservationRequested;
import com.northwood.manufacturing.domain.events.RawMaterialReservationRequested.RequestedComponent;
import com.northwood.manufacturing.domain.saga.WorkOrderSaga;
import static com.northwood.manufacturing.domain.saga.WorkOrderSaga.RAW_MATERIAL_RESERVATION_REQUESTED;
import static com.northwood.manufacturing.domain.saga.WorkOrderSaga.WORK_ORDER_CREATED;
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
 * Spring scheduling glue + worker-driven advance for the work-order saga.
 * Holds the worker-driven side effects (WO load + outbox emission of
 * {@code RawMaterialReservationRequested}); the saga state machine lives
 * on {@link WorkOrderSagaManager}.
 *
 * <p>Worker-driven advances:
 * <ul>
 *   <li>{@code work_order_created → raw_material_reservation_requested}: load
 *       the WO, emit {@code RawMaterialReservationRequested}, transition the
 *       saga, park.</li>
 * </ul>
 *
 * <p>§2.37 Slice 3 removed the {@code started → work_order_created} leg (and the
 * sales-bound {@code WorkOrderReleaseService.release(...)} call it made): the
 * saga is no longer entered at {@code started} from a sales-driven
 * {@code ManufacturingRequested}. Every WO-lifecycle saga is now seeded at
 * {@code work_order_created} by {@code WorkOrderReleaseService.releaseForReplenishment}
 * (make-to-stock — both the root replenishment WO and its sub-assembly children).
 */
@Component
public class WorkOrderSagaWorker {

    private static final Logger log = LoggerFactory.getLogger(WorkOrderSagaWorker.class);
    private static final int BATCH_SIZE = 10;

    private final String workerId =
        "manufacturing.mto-worker@" + ManagementFactory.getRuntimeMXBean().getName();

    private final WorkOrderSagaManager manager;
    private final WorkOrderRepository workOrders;
    private final OutboxAppender outbox;

    public WorkOrderSagaWorker(
        WorkOrderSagaManager manager,
        WorkOrderRepository workOrders,
        OutboxAppender outbox
    ) {
        this.manager = manager;
        this.workOrders = workOrders;
        this.outbox = outbox;
    }

    @Scheduled(fixedDelayString = "${northwood.saga.poll-interval:1000}")
    public void poll() {
        manager.drain(BATCH_SIZE, workerId, this::advance);
    }

    /** Test-side hook: drive one batch with a deterministic worker id. */
    public void drainOnce(String workerId) {
        manager.drain(BATCH_SIZE, workerId, this::advance);
    }

    private void advance(WorkOrderSaga saga) {
        switch (saga.state()) {
            case WORK_ORDER_CREATED -> requestRawMaterialReservation(saga);
            default -> log.debug("[{}] no transition implemented for state {}", workerId, saga.state());
        }
    }

    private void requestRawMaterialReservation(WorkOrderSaga saga) {
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
