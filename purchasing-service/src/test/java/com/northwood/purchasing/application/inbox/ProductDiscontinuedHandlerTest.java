package com.northwood.purchasing.application.inbox;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.northwood.product.domain.ProductAggregateTypes;
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
    private static final Instant DISCONTINUED_AT = Instant.parse("2026-05-14T03:15:00Z");

    @Mock InboxPort inbox;
    @Mock ProductDiscontinuedProjection projection;

    private final ObjectMapper json = new ObjectMapper();
    private ProductDiscontinuedHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ProductDiscontinuedHandler(inbox, projection, json);
    }

    private EventEnvelope event() {
        UUID eventId = UUID.randomUUID();
        ProductDiscontinued payload = new ProductDiscontinued(eventId, PRODUCT, DISCONTINUED_AT);
        return new EventEnvelope(
            eventId, ProductAggregateTypes.PRODUCT, PRODUCT,
            ProductDiscontinued.EVENT_TYPE, 1,
            json.writeValueAsString(payload),
            null, null, null, null, Instant.now()
        );
    }

    @Test void happy_path_stamps_purchasing_product_card() {
        handler.handle(event());

        verify(projection).applyDiscontinued(eq(PRODUCT), eq(DISCONTINUED_AT));
        verify(inbox).recordProcessed(any());
    }

    @Test void already_processed_short_circuits() {
        EventEnvelope envelope = event();
        when(inbox.alreadyProcessed(eq(envelope.eventId()), eq(ProductDiscontinuedHandler.HANDLER_NAME)))
            .thenReturn(true);

        handler.handle(envelope);

        verify(projection, never()).applyDiscontinued(any(), any());
        verify(inbox, never()).recordProcessed(any());
    }
}
