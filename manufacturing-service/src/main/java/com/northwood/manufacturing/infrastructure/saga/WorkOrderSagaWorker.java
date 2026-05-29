package com.northwood.manufacturing.infrastructure.saga;

import com.northwood.manufacturing.application.saga.RawMaterialReservationRequestEmitter;
import com.northwood.manufacturing.application.saga.WorkOrderSagaManager;
import com.northwood.manufacturing.domain.saga.WorkOrderSaga;
import static com.northwood.manufacturing.domain.saga.WorkOrderSaga.RAW_MATERIAL_RESERVATION_REQUESTED;
import static com.northwood.manufacturing.domain.saga.WorkOrderSaga.WORK_ORDER_CREATED;
import com.northwood.shared.domain.Assert;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Spring scheduling glue + worker-driven advance for the work-order saga.
 * Delegates the reservation-request side effect to
 * {@link RawMaterialReservationRequestEmitter} (shared with
 * {@code GoodsReceivedHandler} since §2.41); the saga state machine lives
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
    private final RawMaterialReservationRequestEmitter reservationEmitter;

    public WorkOrderSagaWorker(
        WorkOrderSagaManager manager,
        RawMaterialReservationRequestEmitter reservationEmitter
    ) {
        this.manager = manager;
        this.reservationEmitter = reservationEmitter;
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

        int componentCount = reservationEmitter.emitFor(workOrderId);

        saga.transitionTo(RAW_MATERIAL_RESERVATION_REQUESTED, "wait_for_raw_materials_reserved");
        saga.parkUntil(Instant.now().plus(Duration.ofDays(1)));

        log.info("[{}] saga {} work_order={} → raw_material_reservation_requested ({} component(s))",
            workerId, saga.sagaId(), workOrderId, componentCount);
    }

}
