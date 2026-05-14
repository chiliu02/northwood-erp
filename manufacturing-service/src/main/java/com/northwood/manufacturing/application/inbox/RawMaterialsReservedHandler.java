package com.northwood.manufacturing.application.inbox;

import static com.northwood.manufacturing.domain.saga.MakeToOrderSaga.RAW_MATERIAL_SHORTAGE;

import com.northwood.manufacturing.application.saga.MakeToOrderSagaManager;
import com.northwood.manufacturing.domain.WorkOrder;
import com.northwood.manufacturing.domain.WorkOrderId;
import com.northwood.manufacturing.domain.WorkOrderMaterial;
import com.northwood.manufacturing.domain.WorkOrderRepository;
import com.northwood.inventory.domain.events.RawMaterialsReserved;
import com.northwood.manufacturing.domain.events.RawMaterialShortageDetected;
import com.northwood.manufacturing.domain.events.RawMaterialShortageDetected.ShortageComponent;
import com.northwood.shared.domain.DomainEvent;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import com.northwood.shared.application.outbox.OutboxPort;
import com.northwood.shared.application.outbox.OutboxRow;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Inbox handler for {@code inventory.RawMaterialsReserved}. Three effects in
 * the same transaction:
 *
 * <ol>
 *   <li>Ask the saga manager to apply the reservation outcome (which advances
 *       the make-to-order saga to {@code raw_materials_reserved} or
 *       {@code raw_material_shortage}).</li>
 *   <li>Project the outcome onto the WO aggregate's {@code material_status}
 *       via {@link WorkOrder#applyReservationOutcome(String)}, so a UI reading
 *       the WO directly (production-board detail,
 *       {@code /api/work-orders-cmd/{id}}) sees the same signal without
 *       joining across services.</li>
 *   <li>When the saga lands on {@code raw_material_shortage}, emit
 *       {@code manufacturing.RawMaterialShortageDetected} so purchasing spawns
 *       a requisition for the missing components.</li>
 * </ol>
 */
@Component
public class RawMaterialsReservedHandler extends AbstractInboxHandler<RawMaterialsReserved> {

    public static final String CONSUMER_NAME = "manufacturing.make-to-order.raw-materials-reserved";
    private static final String DEFAULT_WAREHOUSE = "MAIN";

    private final MakeToOrderSagaManager sagaManager;
    private final WorkOrderRepository workOrders;
    private final OutboxPort outbox;

    public RawMaterialsReservedHandler(
        InboxPort inbox,
        MakeToOrderSagaManager sagaManager,
        WorkOrderRepository workOrders,
        OutboxPort outbox,
        ObjectMapper json
    ) {
        super(inbox, json, RawMaterialsReserved.class, RawMaterialsReserved.EVENT_TYPE, CONSUMER_NAME);
        this.sagaManager = sagaManager;
        this.workOrders = workOrders;
        this.outbox = outbox;
    }

    @Override
    protected void apply(RawMaterialsReserved payload, EventEnvelope envelope) {
        Map<UUID, BigDecimal> shortageByProductId = extractShortage(payload);
        String newState = sagaManager.applyRawMaterialsReserved(
            payload.workOrderId(), payload.status(), shortageByProductId
        );

        // Project the reservation outcome onto the WO's material_status so a
        // UI reading the WO directly sees it. Load once, apply, save —
        // applyReservationOutcome is idempotent on retry (same value = no-op).
        // The WO must exist by this point — the saga manager would have
        // thrown above if it didn't.
        WorkOrder workOrder = workOrders.findById(WorkOrderId.of(payload.workOrderId()))
            .orElseThrow(() -> new IllegalStateException(
                "No work_order " + payload.workOrderId() + " for material_status projection"
            ));
        workOrder.applyReservationOutcome(toMaterialStatus(payload.status()));
        workOrders.save(workOrder);

        if (RAW_MATERIAL_SHORTAGE.equals(newState)) {
            emitShortageDetected(payload, workOrder, envelope.actorUserId());
        }

        log.info("[{}] work_order={} status={} → {} (reservation={})",
            CONSUMER_NAME, payload.workOrderId(), payload.status(), newState, payload.stockReservationId());
    }

    private static String toMaterialStatus(String reservationStatus) {
        return switch (reservationStatus) {
            case RawMaterialsReserved.STATUS_RESERVED -> WorkOrder.MATERIAL_RESERVED;
            case RawMaterialsReserved.STATUS_PARTIALLY_RESERVED -> WorkOrder.MATERIAL_PARTIALLY_RESERVED;
            case RawMaterialsReserved.STATUS_FAILED -> WorkOrder.MATERIAL_SHORTAGE;
            default -> throw new IllegalStateException(
                "Unknown reservation status on RawMaterialsReserved: " + reservationStatus
            );
        };
    }

    private static Map<UUID, BigDecimal> extractShortage(RawMaterialsReserved payload) {
        Map<UUID, BigDecimal> shortage = new LinkedHashMap<>();
        for (RawMaterialsReserved.ReservedComponent c : payload.components()) {
            if (c.shortageQuantity() != null && c.shortageQuantity().signum() > 0) {
                shortage.put(c.componentProductId(), c.shortageQuantity());
            }
        }
        return shortage;
    }

    private void emitShortageDetected(RawMaterialsReserved payload, WorkOrder workOrder, String actorUserId) {
        Map<UUID, WorkOrderMaterial> bySku = new HashMap<>();
        for (WorkOrderMaterial m : workOrder.materials()) {
            bySku.put(m.id(), m);
        }

        List<ShortageComponent> shortage = new ArrayList<>();
        for (RawMaterialsReserved.ReservedComponent c : payload.components()) {
            BigDecimal qty = c.shortageQuantity();
            if (qty == null || qty.signum() <= 0) {
                continue;
            }
            WorkOrderMaterial m = bySku.get(c.workOrderMaterialId());
            if (m == null) {
                throw new IllegalStateException(
                    "ReservedComponent work_order_material_id=" + c.workOrderMaterialId()
                        + " not found on work_order " + payload.workOrderId()
                );
            }
            shortage.add(new ShortageComponent(
                m.id(), m.componentProductId(), m.componentSku(), m.componentName(), qty
            ));
        }
        if (shortage.isEmpty()) {
            return;
        }

        appendOutbox(new RawMaterialShortageDetected(
            UUID.randomUUID(),
            workOrder.id().value(),
            workOrder.id().value(),
            workOrder.salesOrderHeaderId(),
            workOrder.salesOrderLineId(),
            DEFAULT_WAREHOUSE,
            shortage,
            Instant.now()
        ), "WorkOrder", actorUserId);
    }

    private void appendOutbox(DomainEvent event, String aggregateType, String actorUserId) {
        try {
            outbox.appendPending(OutboxRow.pending(
                event.eventId(),
                aggregateType,
                event.aggregateId(),
                event.eventType(),
                event.eventVersion(),
                json.writeValueAsString(event),
                null, null, null,
                actorUserId
            ));
        } catch (JacksonException e) {
            throw new IllegalStateException("Cannot serialise " + event.eventType(), e);
        }
    }
}
