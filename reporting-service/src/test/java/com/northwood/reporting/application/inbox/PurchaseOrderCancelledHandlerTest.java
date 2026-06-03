package com.northwood.reporting.application.inbox;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.northwood.purchasing.domain.PurchasingAggregateTypes;
import com.northwood.purchasing.domain.events.PurchaseOrderCancelled;
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
class PurchaseOrderCancelledHandlerTest {

    private static final UUID PO = UUID.randomUUID();
    private static final Instant CANCELLED_AT = Instant.parse("2026-06-03T03:15:00Z");
    private static final String ACTOR = "priya";

    @Mock InboxPort inbox;
    @Mock PurchaseOrderTrackingProjection projection;

    private final ObjectMapper json = new ObjectMapper();
    private PurchaseOrderCancelledHandler handler;

    @BeforeEach
    void setUp() {
        handler = new PurchaseOrderCancelledHandler(inbox, projection, json);
    }

    private EventEnvelope event() {
        UUID eventId = UUID.randomUUID();
        PurchaseOrderCancelled payload = new PurchaseOrderCancelled(
            eventId, PO, "PO-001", UUID.randomUUID(), "draft", ACTOR, "wrong supplier", CANCELLED_AT
        );
        return new EventEnvelope(
            eventId, PurchasingAggregateTypes.PURCHASE_ORDER, PO,
            PurchaseOrderCancelled.EVENT_TYPE, 1,
            json.writeValueAsString(payload),
            null, null, null, ACTOR, Instant.now()
        );
    }

    @Test void happy_path_flips_po_status_to_cancelled() {
        handler.handle(event());

        verify(projection).recordPoCancelled(eq(PO), eq(CANCELLED_AT), eq(ACTOR));
        verify(inbox).recordProcessed(any());
    }

    @Test void already_processed_short_circuits() {
        EventEnvelope envelope = event();
        when(inbox.alreadyProcessed(eq(envelope.eventId()), eq(PurchaseOrderCancelledHandler.CONSUMER_NAME)))
            .thenReturn(true);

        handler.handle(envelope);

        verify(projection, never()).recordPoCancelled(any(), any(), any());
        verify(inbox, never()).recordProcessed(any());
    }
}
