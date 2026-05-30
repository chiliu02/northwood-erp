package com.northwood.manufacturing.application.inbox;

import com.northwood.manufacturing.domain.events.RawMaterialShortageDetected;

import static com.northwood.manufacturing.domain.saga.WorkOrderSaga.RAW_MATERIALS_RESERVED;
import static com.northwood.manufacturing.domain.saga.WorkOrderSaga.RAW_MATERIAL_SHORTAGE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.northwood.inventory.domain.InventoryAggregateTypes;
import com.northwood.inventory.domain.events.RawMaterialsReserved;
import com.northwood.manufacturing.application.saga.WorkOrderSagaManager;
import com.northwood.manufacturing.domain.WorkOrder;
import com.northwood.manufacturing.domain.WorkOrderId;
import com.northwood.manufacturing.domain.WorkOrderMaterial;
import com.northwood.manufacturing.domain.WorkOrderOperation;
import com.northwood.manufacturing.domain.WorkOrderRepository;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.EventEnvelope;
import com.northwood.shared.application.outbox.OutboxAppender;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class RawMaterialsReservedHandlerTest {

    private static final UUID SO_HEADER = UUID.randomUUID();
    private static final UUID SO_LINE = UUID.randomUUID();

    @Mock InboxPort inbox;
    @Mock WorkOrderSagaManager sagaManager;
    @Mock WorkOrderRepository workOrders;
    @Mock OutboxAppender outbox;

    private final ObjectMapper json = new ObjectMapper();
    private RawMaterialsReservedHandler handler;

    @BeforeEach
    void setUp() {
        handler = new RawMaterialsReservedHandler(inbox, sagaManager, workOrders, outbox, json);
    }

    private static WorkOrder workOrderWithMaterial(UUID materialId, UUID componentProductId) {
        WorkOrderOperation op = new WorkOrderOperation(
            UUID.randomUUID(), 10, "OP-10", "Cut", UUID.randomUUID(),
            BigDecimal.ZERO, new BigDecimal("30"), WorkOrder.OperationStatus.PLANNED
        );
        WorkOrderMaterial material = new WorkOrderMaterial(
            materialId, componentProductId, "RM-X", "Material X",
            new BigDecimal("4"), BigDecimal.ZERO, WorkOrder.MaterialLineStatus.REQUIRED
        );
        return WorkOrder.release(
            "WO-001", SO_HEADER, SO_LINE, null,
            UUID.randomUUID(), "FG-X", "Finished X",
            UUID.randomUUID(), BigDecimal.ONE,
            List.of(material), List.of(op)
        );
    }

    private EventEnvelope event(UUID workOrderId, String status,
                                List<RawMaterialsReserved.ReservedComponent> components) {
        UUID eventId = UUID.randomUUID();
        RawMaterialsReserved payload = new RawMaterialsReserved(
            eventId, UUID.randomUUID(), workOrderId, UUID.randomUUID(),
            status, components, Instant.now()
        );
        return new EventEnvelope(
            eventId, InventoryAggregateTypes.STOCK_RESERVATION, workOrderId,
            RawMaterialsReserved.EVENT_TYPE, 1,
            json.writeValueAsString(payload),
            null, null, null, null, Instant.now()
        );
    }

    private static RawMaterialsReserved.ReservedComponent reserved(UUID matId, UUID prodId) {
        return new RawMaterialsReserved.ReservedComponent(
            matId, prodId, new BigDecimal("4"), new BigDecimal("4"),
            BigDecimal.ZERO, "reserved"
        );
    }

    private static RawMaterialsReserved.ReservedComponent shortage(UUID matId, UUID prodId, String shortageQty) {
        return new RawMaterialsReserved.ReservedComponent(
            matId, prodId, new BigDecimal("4"),
            new BigDecimal("4").subtract(new BigDecimal(shortageQty)),
            new BigDecimal(shortageQty), "shortage"
        );
    }

    @Test void full_reservation_projects_material_status_reserved_and_saves() {
        UUID wo = UUID.randomUUID();
        UUID matId = UUID.randomUUID();
        UUID prodId = UUID.randomUUID();
        WorkOrder loaded = workOrderWithMaterial(matId, prodId);
        loaded.pullPendingEvents();
        when(sagaManager.applyRawMaterialsReserved(eq(wo), eq("reserved"), any()))
            .thenReturn(RAW_MATERIALS_RESERVED);
        when(workOrders.findById(WorkOrderId.of(wo))).thenReturn(Optional.of(loaded));

        handler.handle(event(wo, "reserved", List.of(reserved(matId, prodId))));

        verify(sagaManager).applyRawMaterialsReserved(eq(wo), eq("reserved"), any());
        verify(workOrders).save(loaded);
        assertThat(loaded.materialStatus()).isEqualTo(WorkOrder.MaterialStatus.RESERVED);
        verifyNoInteractions(outbox);
    }

    @Test void partial_reservation_returning_shortage_projects_and_emits_RawMaterialShortageDetected() {
        UUID wo = UUID.randomUUID();
        UUID matId = UUID.randomUUID();
        UUID prodId = UUID.randomUUID();
        WorkOrder loaded = workOrderWithMaterial(matId, prodId);
        loaded.pullPendingEvents();
        when(sagaManager.applyRawMaterialsReserved(eq(wo), eq("partially_reserved"), any()))
            .thenReturn(RAW_MATERIAL_SHORTAGE);
        when(workOrders.findById(WorkOrderId.of(wo))).thenReturn(Optional.of(loaded));

        handler.handle(event(wo, "partially_reserved", List.of(shortage(matId, prodId, "2"))));

        verify(workOrders).save(loaded);
        assertThat(loaded.materialStatus()).isEqualTo(WorkOrder.MaterialStatus.PARTIALLY_RESERVED);
        ArgumentCaptor<RawMaterialShortageDetected> captor =
            ArgumentCaptor.forClass(RawMaterialShortageDetected.class);
        ArgumentCaptor<String> actorCaptor = ArgumentCaptor.forClass(String.class);
        verify(outbox).append(captor.capture(), eq(WorkOrder.AGGREGATE_TYPE), actorCaptor.capture());
        assertThat(captor.getValue().eventType()).isEqualTo(RawMaterialShortageDetected.EVENT_TYPE);
        assertThat(actorCaptor.getValue()).isNull();
    }

    @Test void failed_reservation_projects_material_status_shortage() {
        UUID wo = UUID.randomUUID();
        UUID matId = UUID.randomUUID();
        UUID prodId = UUID.randomUUID();
        WorkOrder loaded = workOrderWithMaterial(matId, prodId);
        loaded.pullPendingEvents();
        when(sagaManager.applyRawMaterialsReserved(eq(wo), eq("failed"), any()))
            .thenReturn(RAW_MATERIAL_SHORTAGE);
        when(workOrders.findById(WorkOrderId.of(wo))).thenReturn(Optional.of(loaded));

        handler.handle(event(wo, "failed", List.of(shortage(matId, prodId, "4"))));

        verify(workOrders).save(loaded);
        assertThat(loaded.materialStatus()).isEqualTo(WorkOrder.MaterialStatus.SHORTAGE);
    }

    @Test void already_processed_short_circuits() {
        UUID wo = UUID.randomUUID();
        EventEnvelope envelope = event(wo, "reserved",
            List.of(reserved(UUID.randomUUID(), UUID.randomUUID())));
        when(inbox.alreadyProcessed(eq(envelope.eventId()),
            eq(RawMaterialsReservedHandler.CONSUMER_NAME))).thenReturn(true);

        handler.handle(envelope);

        verifyNoInteractions(sagaManager);
        verify(inbox, never()).recordProcessed(any());
    }

    @Test void wrong_event_type_is_no_op() {
        EventEnvelope wrong = new EventEnvelope(
            UUID.randomUUID(), InventoryAggregateTypes.STOCK_RESERVATION, UUID.randomUUID(),
            "inventory.SomethingElse", 1,
            "{}", null, null, null, null, Instant.now()
        );

        handler.handle(wrong);

        verify(inbox, never()).alreadyProcessed(any(), any());
        verifyNoInteractions(sagaManager);
    }
}
