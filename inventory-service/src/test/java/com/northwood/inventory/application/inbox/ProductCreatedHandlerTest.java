package com.northwood.inventory.application.inbox;

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
    private static final String SKU = "FG-NEW-001";
    private static final String NAME = "Newly Registered Product";
    private static final String TYPE = "finished_good";

    @Mock InboxPort inbox;
    @Mock ProductCardProjection productCard;

    private final ObjectMapper json = new ObjectMapper();
    private ProductCreatedHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ProductCreatedHandler(inbox, productCard, json);
    }

    private EventEnvelope event() {
        UUID eventId = UUID.randomUUID();
        ProductCreated payload = new ProductCreated(eventId, PRODUCT, SKU, NAME, TYPE, Instant.now());
        return new EventEnvelope(
            eventId, ProductAggregateTypes.PRODUCT, PRODUCT,
            ProductCreated.EVENT_TYPE, 1,
            json.writeValueAsString(payload),
            null, null, null, null, Instant.now()
        );
    }

    @Test void happy_path_seeds_product_card() {
        handler.handle(event());

        verify(productCard).applyCreated(eq(PRODUCT), eq(SKU), eq(NAME), eq(TYPE));
        verify(inbox).recordProcessed(any());
    }

    @Test void already_processed_short_circuits() {
        EventEnvelope envelope = event();
        when(inbox.alreadyProcessed(eq(envelope.eventId()), eq(ProductCreatedHandler.CONSUMER_NAME)))
            .thenReturn(true);

        handler.handle(envelope);

        verify(productCard, never()).applyCreated(any(), any(), any(), any());
        verify(inbox, never()).recordProcessed(any());
    }
}
