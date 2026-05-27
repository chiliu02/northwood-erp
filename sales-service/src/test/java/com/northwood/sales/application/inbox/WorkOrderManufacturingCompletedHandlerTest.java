package com.northwood.sales.application.inbox;

import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.MANUFACTURING_IN_PROGRESS;
import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.READY_TO_SHIP;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.northwood.manufacturing.domain.ManufacturingAggregateTypes;
import com.northwood.manufacturing.domain.events.WorkOrderManufacturingCompleted;
import com.northwood.sales.application.SalesOrderReadyToShipEmitter;
import com.northwood.sales.application.saga.SalesOrderFulfilmentSagaManager;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.EventEnvelope;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class WorkOrderManufacturingCompletedHandlerTest {

    private static final UUID SO = UUID.randomUUID();

    @Mock InboxPort inbox;
    @Mock SalesOrderFulfilmentSagaManager sagaManager;
    @Mock SalesOrderReadyToShipEmitter readyToShipEmitter;

    private final ObjectMapper json = new ObjectMapper();
    private WorkOrderManufacturingCompletedHandler handler;

    @BeforeEach
    void setUp() {
        handler = new WorkOrderManufacturingCompletedHandler(inbox, sagaManager, readyToShipEmitter, json);
    }

    private EventEnvelope event() {
        UUID eventId = UUID.randomUUID();
        WorkOrderManufacturingCompleted payload = new WorkOrderManufacturingCompleted(
            eventId, UUID.randomUUID(), "WO-001",
            SO, UUID.randomUUID(), null,
            UUID.randomUUID(), "FG-001", new BigDecimal("1"),
            Instant.now()
        );
        return new EventEnvelope(
            eventId, ManufacturingAggregateTypes.WORK_ORDER, UUID.randomUUID(),
            WorkOrderManufacturingCompleted.EVENT_TYPE, 1,
            json.writeValueAsString(payload),
            null, null, null, null, Instant.now()
        );
    }

    @Test void intermediate_completion_delegates_without_emitting_ready_to_ship() {
        when(sagaManager.applyWorkOrderManufacturingCompleted(any(), any(), any()))
            .thenReturn(MANUFACTURING_IN_PROGRESS);

        handler.handle(event());

        verify(sagaManager).applyWorkOrderManufacturingCompleted(any(), any(), any());
        verify(readyToShipEmitter, never()).emitReadyToShip(any());
        verify(inbox).recordProcessed(any());
    }

    @Test void final_completion_emits_ready_to_ship() {
        when(sagaManager.applyWorkOrderManufacturingCompleted(eq(SO), any(), any()))
            .thenReturn(READY_TO_SHIP);

        handler.handle(event());

        verify(readyToShipEmitter).emitReadyToShip(SO);
        verify(inbox).recordProcessed(any());
    }

    @Test void already_processed_short_circuits() {
        EventEnvelope envelope = event();
        when(inbox.alreadyProcessed(eq(envelope.eventId()), eq(WorkOrderManufacturingCompletedHandler.CONSUMER_NAME)))
            .thenReturn(true);

        handler.handle(envelope);

        verify(sagaManager, never()).applyWorkOrderManufacturingCompleted(any(), any(), any());
        verify(inbox, never()).recordProcessed(any());
    }
}
