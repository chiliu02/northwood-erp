package com.northwood.reporting.application.inbox;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.northwood.purchasing.domain.PurchasingAggregateTypes;
import com.northwood.purchasing.domain.events.PurchaseOrderApproved;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.EventEnvelope;
import com.northwood.shared.domain.Currencies;
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
class PurchaseOrderApprovedHandlerTest {

    private static final UUID PO = UUID.randomUUID();
    private static final Instant APPROVED_AT = Instant.parse("2026-05-14T03:15:00Z");
    private static final String ACTOR = "alice";

    @Mock InboxPort inbox;
    @Mock PurchaseOrderTrackingProjection projection;

    private final ObjectMapper json = new ObjectMapper();
    private PurchaseOrderApprovedHandler handler;

    @BeforeEach
    void setUp() {
        handler = new PurchaseOrderApprovedHandler(inbox, projection, json);
    }

    private EventEnvelope event() {
        UUID eventId = UUID.randomUUID();
        PurchaseOrderApproved payload = new PurchaseOrderApproved(
            eventId, PO, "PO-001",
            UUID.randomUUID(), Currencies.AUD, new BigDecimal("100.00"),
            ACTOR, "ok", APPROVED_AT
        );
        return new EventEnvelope(
            eventId, PurchasingAggregateTypes.PURCHASE_ORDER, PO,
            PurchaseOrderApproved.EVENT_TYPE, 1,
            json.writeValueAsString(payload),
            null, null, null, ACTOR, Instant.now()
        );
    }

    @Test void happy_path_flips_po_status_to_sent_and_stamps_approved_at() {
        handler.handle(event());

        verify(projection).recordPoApproved(eq(PO), eq(APPROVED_AT), eq(ACTOR));
        verify(inbox).recordProcessed(any());
    }

    @Test void already_processed_short_circuits() {
        EventEnvelope envelope = event();
        when(inbox.alreadyProcessed(eq(envelope.eventId()), eq(PurchaseOrderApprovedHandler.CONSUMER_NAME)))
            .thenReturn(true);

        handler.handle(envelope);

        verify(projection, never()).recordPoApproved(any(), any(), any());
        verify(inbox, never()).recordProcessed(any());
    }
}
