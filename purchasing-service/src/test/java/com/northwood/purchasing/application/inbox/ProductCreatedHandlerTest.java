package com.northwood.purchasing.application.inbox;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.northwood.product.domain.ProductAggregateTypes;
import com.northwood.product.domain.events.ProductCreated;
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
class ProductCreatedHandlerTest {

    private static final UUID PRODUCT = UUID.randomUUID();

    @Mock InboxPort inbox;
    @Mock ProductCreatedProjection projection;

    private final ObjectMapper json = new ObjectMapper();
    private ProductCreatedHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ProductCreatedHandler(inbox, projection, json);
    }

    private EventEnvelope event() {
        UUID eventId = UUID.randomUUID();
        ProductCreated payload = new ProductCreated(
            eventId, PRODUCT, "RM-BOARD-001", "Wooden Board", "raw_material", Instant.now());
        return new EventEnvelope(
            eventId, ProductAggregateTypes.PRODUCT, PRODUCT,
            ProductCreated.EVENT_TYPE, 1,
            json.writeValueAsString(payload),
            null, null, null, null, Instant.now()
        );
    }

    @Test void upserts_sku_and_name_onto_the_card() {
        handler.handle(event());

        verify(projection).applyCreated(eq(PRODUCT), eq("RM-BOARD-001"), eq("Wooden Board"));
        verify(inbox).recordProcessed(any());
    }

    @Test void already_processed_short_circuits() {
        EventEnvelope envelope = event();
        when(inbox.alreadyProcessed(eq(envelope.eventId()), eq(ProductCreatedHandler.CONSUMER_NAME)))
            .thenReturn(true);

        handler.handle(envelope);

        verify(projection, never()).applyCreated(any(), any(), any());
        verify(inbox, never()).recordProcessed(any());
    }
}
