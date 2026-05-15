package com.northwood.manufacturing.application.inbox;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.northwood.manufacturing.application.BomLookup;
import com.northwood.product.domain.ProductAggregateTypes;
import com.northwood.product.domain.events.ProductDiscontinued;
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
class ProductDiscontinuedHandlerTest {

    private static final UUID PRODUCT = UUID.randomUUID();
    private static final UUID PARENT_FG_A = UUID.randomUUID();
    private static final UUID PARENT_FG_B = UUID.randomUUID();

    @Mock InboxPort inbox;
    @Mock ProductReplenishmentProjection replenishment;
    @Mock ProductActiveBomProjection activeBom;
    @Mock BomLookup boms;

    private final ObjectMapper json = new ObjectMapper();
    private ProductDiscontinuedHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ProductDiscontinuedHandler(inbox, replenishment, activeBom, boms, json);
    }

    private EventEnvelope event() {
        UUID eventId = UUID.randomUUID();
        ProductDiscontinued payload = new ProductDiscontinued(eventId, PRODUCT, Instant.now());
        return new EventEnvelope(
            eventId, ProductAggregateTypes.PRODUCT, PRODUCT,
            ProductDiscontinued.EVENT_TYPE, 1,
            json.writeValueAsString(payload),
            null, null, null, null, Instant.now()
        );
    }

    @Test void happy_path_flips_replenishment_flags_and_clears_active_bom() {
        when(boms.findParentProductIdsByComponent(PRODUCT)).thenReturn(List.of());

        handler.handle(event());

        verify(replenishment).applyDiscontinued(eq(PRODUCT));
        verify(activeBom).apply(eq(PRODUCT), eq((UUID) null));
        verify(inbox).recordProcessed(any());
    }

    @Test void cascades_clear_to_parent_active_boms() {
        when(boms.findParentProductIdsByComponent(PRODUCT)).thenReturn(List.of(PARENT_FG_A, PARENT_FG_B));

        handler.handle(event());

        verify(activeBom).apply(eq(PRODUCT), eq((UUID) null));
        verify(activeBom).apply(eq(PARENT_FG_A), eq((UUID) null));
        verify(activeBom).apply(eq(PARENT_FG_B), eq((UUID) null));
        verify(activeBom, times(3)).apply(any(), any());
    }

    @Test void already_processed_short_circuits() {
        EventEnvelope envelope = event();
        when(inbox.alreadyProcessed(eq(envelope.eventId()), eq(ProductDiscontinuedHandler.CONSUMER_NAME)))
            .thenReturn(true);

        handler.handle(envelope);

        verify(replenishment, never()).applyDiscontinued(any());
        verify(activeBom, never()).apply(any(), any());
        verify(boms, never()).findParentProductIdsByComponent(any());
        verify(inbox, never()).recordProcessed(any());
    }
}
