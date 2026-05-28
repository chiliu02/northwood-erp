package com.northwood.inventory.application.inbox;

import static org.mockito.ArgumentMatchers.any;
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
    @Mock ProductReplenishmentProjection projection;

    private final ObjectMapper json = new ObjectMapper();
    private MakeVsBuyChangedHandler handler;

    @BeforeEach
    void setUp() {
        handler = new MakeVsBuyChangedHandler(inbox, projection, json);
    }

    private EventEnvelope event(boolean newPurchased, boolean newManufactured) {
        UUID eventId = UUID.randomUUID();
        MakeVsBuyChanged payload = new MakeVsBuyChanged(
            eventId, PRODUCT,
            !newPurchased, newPurchased,
            !newManufactured, newManufactured,
            Instant.now()
        );
        return new EventEnvelope(
            eventId, ProductAggregateTypes.PRODUCT, PRODUCT,
            MakeVsBuyChanged.EVENT_TYPE, 1,
            json.writeValueAsString(payload),
            null, null, null, null, Instant.now()
        );
    }

    @Test void happy_path_propagates_flags_to_projection() {
        handler.handle(event(true, false));

        verify(projection).applyMakeVsBuy(eq(PRODUCT), eq(true), eq(false));
        verify(inbox).recordProcessed(any());
    }

    @Test void both_flags_true_is_valid_vertically_integrated_case() {
        handler.handle(event(true, true));

        verify(projection).applyMakeVsBuy(eq(PRODUCT), eq(true), eq(true));
    }

    @Test void already_processed_short_circuits() {
        EventEnvelope envelope = event(false, true);
        when(inbox.alreadyProcessed(eq(envelope.eventId()), eq(MakeVsBuyChangedHandler.CONSUMER_NAME)))
            .thenReturn(true);

        handler.handle(envelope);

        verify(projection, never()).applyMakeVsBuy(any(), any(Boolean.class), any(Boolean.class));
        verify(inbox, never()).recordProcessed(any());
    }
}
