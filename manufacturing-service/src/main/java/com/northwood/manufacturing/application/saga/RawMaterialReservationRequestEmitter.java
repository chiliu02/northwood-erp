package com.northwood.manufacturing.application.saga;

import com.northwood.inventory.domain.WarehouseCodes;
import com.northwood.manufacturing.domain.WorkOrder;
import com.northwood.manufacturing.domain.WorkOrderId;
import com.northwood.manufacturing.domain.WorkOrderMaterial;
import com.northwood.manufacturing.domain.WorkOrderRepository;
import com.northwood.manufacturing.domain.events.RawMaterialReservationRequested;
import com.northwood.manufacturing.domain.events.RawMaterialReservationRequested.RequestedComponent;
import com.northwood.shared.application.outbox.OutboxAppender;
import com.northwood.shared.domain.Assert;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Builds and appends {@code RawMaterialReservationRequested} for a work order.
 *
 * <p>Shared by the two paths that ask inventory to reserve a WO's raw
 * materials, so the event-construction logic lives in exactly one place:
 * <ul>
 *   <li>the {@code WorkOrderSagaWorker}, on the initial
 *       {@code work_order_created → raw_material_reservation_requested} advance;</li>
 *   <li>the {@code GoodsReceivedHandler}, when a covering goods-receipt clears a
 *       parked {@code raw_material_shortage} and the reservation is re-requested
 *       (previously this bounced the saga back through {@code work_order_created}
 *       so the worker would re-emit — misleading, since no new WO is created).</li>
 * </ul>
 *
 * <p>Stamped under {@link WorkOrder#AGGREGATE_TYPE}: the request naturally
 * belongs to the WorkOrder's stream (the saga emits nothing under its own
 * identity — see {@code WorkOrderSaga.AGGREGATE_TYPE}).
 */
@Component
public class RawMaterialReservationRequestEmitter {

    private final WorkOrderRepository workOrders;
    private final OutboxAppender outbox;

    public RawMaterialReservationRequestEmitter(
        WorkOrderRepository workOrders,
        OutboxAppender outbox
    ) {
        this.workOrders = workOrders;
        this.outbox = outbox;
    }

    /**
     * Load the WO, build a {@code RawMaterialReservationRequested} over its
     * materials, and append it to the outbox. Returns the component count for
     * the caller's log line.
     */
    public int emitFor(UUID workOrderId) {
        Assert.notNull(workOrderId, "workOrderId");
        WorkOrder workOrder = workOrders.findById(WorkOrderId.of(workOrderId))
            .orElseThrow(() -> new IllegalStateException(
                "no work order " + workOrderId + " to request raw-material reservation for"
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

        outbox.append(new RawMaterialReservationRequested(
            UUID.randomUUID(),
            workOrderId,
            workOrderId,
            workOrder.salesOrderHeaderId(),
            workOrder.salesOrderLineId(),
            WarehouseCodes.MAIN,
            components,
            Instant.now()
        ), WorkOrder.AGGREGATE_TYPE);

        return components.size();
    }
}
