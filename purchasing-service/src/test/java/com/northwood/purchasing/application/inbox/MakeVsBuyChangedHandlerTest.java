package com.northwood.purchasing.application.inbox;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.northwood.product.domain.ProductAggregateTypes;
import com.northwood.product.domain.events.MakeVsBuyChanged;
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
class MakeVsBuyChangedHandlerTest {

    private static final UUID PRODUCT = UUID.randomUUID();

    @Mock InboxPort inbox;
    @Mock MakeVsBuyChangedProjection projection;

    private final ObjectMapper json = new ObjectMapper();
    private MakeVsBuyChangedHandler handler;

    @BeforeEach
    void setUp() {
        handler = new MakeVsBuyChangedHandler(inbox, projection, json);
    }

    private EventEnvelope event(boolean newPurchased) {
        UUID eventId = UUID.randomUUID();
        MakeVsBuyChanged payload = new MakeVsBuyChanged(
            eventId, PRODUCT, false, newPurchased, true, true, Instant.now());
        return new EventEnvelope(
            eventId, ProductAggregateTypes.PRODUCT, PRODUCT,
            MakeVsBuyChanged.EVENT_TYPE, 1,
            json.writeValueAsString(payload),
            null, null, null, null, Instant.now());
    }

    @Test void projects_the_new_purchased_flag() {
        handler.handle(event(true));

        verify(projection).applyMakeVsBuy(eq(PRODUCT), eq(true));
        verify(inbox).recordProcessed(any());
    }

    @Test void already_processed_short_circuits() {
        EventEnvelope envelope = event(true);
        when(inbox.alreadyProcessed(eq(envelope.eventId()), eq(MakeVsBuyChangedHandler.HANDLER_NAME)))
            .thenReturn(true);

        handler.handle(envelope);

        verify(projection, never()).applyMakeVsBuy(any(), anyBoolean());
        verify(inbox, never()).recordProcessed(any());
    }
}
