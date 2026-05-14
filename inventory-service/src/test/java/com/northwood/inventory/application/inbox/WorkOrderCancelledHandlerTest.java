package com.northwood.inventory.application.inbox;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.northwood.inventory.application.StockReservationService;
import com.northwood.manufacturing.domain.events.WorkOrderCancelled;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.EventEnvelope;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class WorkOrderCancelledHandlerTest {

    private static final UUID WO = UUID.randomUUID();

    @Mock InboxPort inbox;
    @Mock StockReservationService reservation;

    private final ObjectMapper json = new ObjectMapper();
    private WorkOrderCancelledHandler handler;

    @BeforeEach
    void setUp() {
        handler = new WorkOrderCancelledHandler(inbox, reservation, json);
    }

    private EventEnvelope event() {
        UUID eventId = UUID.randomUUID();
        WorkOrderCancelled payload = new WorkOrderCancelled(
            eventId, WO, null, UUID.randomUUID(),
            "cancelled via sales", Instant.now()
        );
        return new EventEnvelope(
            eventId, "WorkOrder", WO,
            WorkOrderCancelled.EVENT_TYPE, 1,
            json.writeValueAsString(payload),
            null, null, null, null, Instant.now()
        );
    }

    @Test void delegates_release_to_reservation_service_and_records_processed() {
        handler.handle(event());

        verify(reservation).releaseForWorkOrder(WO);
        verify(inbox).recordProcessed(any());
    }

    @Test void already_processed_short_circuits() {
        EventEnvelope envelope = event();
        when(inbox.alreadyProcessed(eq(envelope.eventId()),
            eq(WorkOrderCancelledHandler.CONSUMER_NAME))).thenReturn(true);

        handler.handle(envelope);

        verifyNoInteractions(reservation);
        verify(inbox, never()).recordProcessed(any());
    }

    @Test void wrong_event_type_is_no_op() {
        EventEnvelope wrong = new EventEnvelope(
            UUID.randomUUID(), "WorkOrder", UUID.randomUUID(),
            "manufacturing.SomethingElse", 1, "{}",
            null, null, null, null, Instant.now()
        );

        handler.handle(wrong);

        verify(inbox, never()).alreadyProcessed(any(), any());
        verifyNoInteractions(reservation);
    }
}
