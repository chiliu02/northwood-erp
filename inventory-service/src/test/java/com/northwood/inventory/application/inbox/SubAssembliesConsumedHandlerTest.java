package com.northwood.inventory.application.inbox;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.northwood.inventory.application.WipBalanceWriter;
import com.northwood.manufacturing.domain.ManufacturingAggregateTypes;
import com.northwood.manufacturing.domain.events.SubAssembliesConsumed;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.EventEnvelope;
import java.math.BigDecimal;
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
class SubAssembliesConsumedHandlerTest {

    private static final UUID PARENT = UUID.randomUUID();

    @Mock InboxPort inbox;
    @Mock WipBalanceWriter wipBalances;

    private final ObjectMapper json = new ObjectMapper();
    private SubAssembliesConsumedHandler handler;

    @BeforeEach
    void setUp() {
        handler = new SubAssembliesConsumedHandler(inbox, wipBalances, json);
    }

    private EventEnvelope event(List<SubAssembliesConsumed.ConsumedItem> items) {
        UUID eventId = UUID.randomUUID();
        SubAssembliesConsumed payload = new SubAssembliesConsumed(
            eventId, PARENT, items, Instant.now()
        );
        return new EventEnvelope(
            eventId, ManufacturingAggregateTypes.WORK_ORDER, PARENT,
            SubAssembliesConsumed.EVENT_TYPE, 1,
            json.writeValueAsString(payload),
            null, null, null, null, Instant.now()
        );
    }

    @Test void each_consumed_item_decrements_wip() {
        UUID prodA = UUID.randomUUID();
        UUID prodB = UUID.randomUUID();

        handler.handle(event(List.of(
            new SubAssembliesConsumed.ConsumedItem(UUID.randomUUID(), prodA, new BigDecimal("3")),
            new SubAssembliesConsumed.ConsumedItem(UUID.randomUUID(), prodB, new BigDecimal("1"))
        )));

        verify(wipBalances).decrement(prodA, new BigDecimal("3"));
        verify(wipBalances).decrement(prodB, new BigDecimal("1"));
        verify(inbox).recordProcessed(any());
    }

    @Test void empty_items_list_records_processed_without_wip_writes() {
        handler.handle(event(List.of()));

        verifyNoInteractions(wipBalances);
        verify(inbox).recordProcessed(any());
    }

    @Test void already_processed_short_circuits() {
        EventEnvelope envelope = event(List.of(
            new SubAssembliesConsumed.ConsumedItem(
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("1"))
        ));
        when(inbox.alreadyProcessed(eq(envelope.eventId()),
            eq(SubAssembliesConsumedHandler.CONSUMER_NAME))).thenReturn(true);

        handler.handle(envelope);

        verifyNoInteractions(wipBalances);
        verify(inbox, never()).recordProcessed(any());
    }
}
