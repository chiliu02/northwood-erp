package com.northwood.manufacturing.application.inbox;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.northwood.product.domain.events.ProductDiscontinued;
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
class ProductDiscontinuedHandlerTest {

    private static final UUID PRODUCT = UUID.randomUUID();

    @Mock InboxPort inbox;
    @Mock ProductReplenishmentProjection replenishment;
    @Mock ProductActiveBomProjection activeBom;

    private final ObjectMapper json = new ObjectMapper();
    private ProductDiscontinuedHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ProductDiscontinuedHandler(inbox, replenishment, activeBom, json);
    }

    private EventEnvelope event() {
        UUID eventId = UUID.randomUUID();
        ProductDiscontinued payload = new ProductDiscontinued(eventId, PRODUCT, Instant.now());
        return new EventEnvelope(
            eventId, "Product", PRODUCT,
            ProductDiscontinued.EVENT_TYPE, 1,
            json.writeValueAsString(payload),
            null, null, null, null, Instant.now()
        );
    }

    @Test void happy_path_flips_replenishment_flags_and_clears_active_bom() {
        handler.handle(event());

        verify(replenishment).applyDiscontinued(eq(PRODUCT));
        verify(activeBom).apply(eq(PRODUCT), eq((UUID) null));
        verify(inbox).recordProcessed(any());
    }

    @Test void already_processed_short_circuits() {
        EventEnvelope envelope = event();
        when(inbox.alreadyProcessed(eq(envelope.eventId()), eq(ProductDiscontinuedHandler.CONSUMER_NAME)))
            .thenReturn(true);

        handler.handle(envelope);

        verify(replenishment, never()).applyDiscontinued(any());
        verify(activeBom, never()).apply(any(), any());
        verify(inbox, never()).recordProcessed(any());
    }
}
