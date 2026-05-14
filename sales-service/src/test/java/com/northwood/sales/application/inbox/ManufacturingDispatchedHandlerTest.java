package com.northwood.sales.application.inbox;

import com.northwood.sales.domain.SalesOrder;

import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.MANUFACTURING_REQUESTED;
import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.STOCK_RESERVATION_FAILED;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.northwood.manufacturing.domain.events.ManufacturingDispatched;
import com.northwood.sales.application.saga.SalesOrderFulfilmentSagaManager;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.EventEnvelope;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class ManufacturingDispatchedHandlerTest {

    private static final UUID SO = UUID.randomUUID();

    @Mock InboxPort inbox;
    @Mock SalesOrderFulfilmentSagaManager sagaManager;
    @Mock SalesOrderHeaderStatusProjection statusProjection;

    private final ObjectMapper json = new ObjectMapper();
    private ManufacturingDispatchedHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ManufacturingDispatchedHandler(inbox, sagaManager, statusProjection, json);
    }

    private EventEnvelope event(String... outcomes) {
        UUID eventId = UUID.randomUUID();
        List<ManufacturingDispatched.LineOutcome> lines = new java.util.ArrayList<>();
        int line = 10;
        for (String outcome : outcomes) {
            lines.add(new ManufacturingDispatched.LineOutcome(
                UUID.randomUUID(), line, UUID.randomUUID(), "SKU-" + line, outcome
            ));
            line += 10;
        }
        ManufacturingDispatched payload = new ManufacturingDispatched(
            eventId, UUID.randomUUID(), SO, lines, Instant.now()
        );
        return new EventEnvelope(
            eventId, "WorkOrder", UUID.randomUUID(),
            ManufacturingDispatched.EVENT_TYPE, 1,
            json.writeValueAsString(payload),
            null, null, null, null, Instant.now()
        );
    }

    @Test void happy_path_passes_accepted_count_to_manager() {
        when(sagaManager.applyManufacturingDispatched(eq(SO), eq(2), eq(3))).thenReturn(MANUFACTURING_REQUESTED);

        handler.handle(event("accepted", "rejected", "accepted"));

        verify(sagaManager).applyManufacturingDispatched(SO, 2, 3);
        verify(statusProjection, never()).markStatus(any(), any());
        verify(inbox).recordProcessed(any());
    }

    @Test void all_rejected_triggers_rejected_projection() {
        when(sagaManager.applyManufacturingDispatched(eq(SO), eq(0), eq(2)))
            .thenReturn(STOCK_RESERVATION_FAILED);

        handler.handle(event("rejected", "rejected"));

        verify(statusProjection).markStatus(SO, SalesOrder.REJECTED);
    }

    @Test void already_processed_short_circuits() {
        EventEnvelope envelope = event("accepted");
        when(inbox.alreadyProcessed(eq(envelope.eventId()), eq(ManufacturingDispatchedHandler.CONSUMER_NAME)))
            .thenReturn(true);

        handler.handle(envelope);

        verify(sagaManager, never()).applyManufacturingDispatched(any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
        verifyNoInteractions(statusProjection);
    }
}
