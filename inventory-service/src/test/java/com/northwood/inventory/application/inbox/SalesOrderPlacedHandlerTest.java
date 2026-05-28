package com.northwood.inventory.application.inbox;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.northwood.sales.domain.SalesAggregateTypes;
import com.northwood.sales.domain.events.SalesOrderPlaced;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.EventEnvelope;
import com.northwood.shared.domain.Currencies;
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
class SalesOrderPlacedHandlerTest {

    private static final UUID SO = UUID.randomUUID();

    @Mock InboxPort inbox;
    @Mock SalesOrderLineFactsProjection projection;

    private final ObjectMapper json = new ObjectMapper();
    private SalesOrderPlacedHandler handler;

    @BeforeEach
    void setUp() {
        handler = new SalesOrderPlacedHandler(inbox, projection, json);
    }

    private EventEnvelope event(List<SalesOrderPlaced.PlacedLine> lines) {
        UUID eventId = UUID.randomUUID();
        SalesOrderPlaced payload = new SalesOrderPlaced(
            eventId, SO, "SO-001", UUID.randomUUID(), "CUST-001", "Acme",
            Currencies.AUD, new BigDecimal("100.00"),
            SalesOrderPlaced.PAYMENT_TERMS_ON_SHIPMENT,
            lines, Instant.now()
        );
        return new EventEnvelope(
            eventId, SalesAggregateTypes.SALES_ORDER, SO,
            SalesOrderPlaced.EVENT_TYPE, 1,
            json.writeValueAsString(payload),
            null, null, null, null, Instant.now()
        );
    }

    @Test void each_line_seeds_a_projection_row() {
        UUID line1 = UUID.randomUUID();
        UUID line2 = UUID.randomUUID();
        UUID prodA = UUID.randomUUID();
        UUID prodB = UUID.randomUUID();

        handler.handle(event(List.of(
            new SalesOrderPlaced.PlacedLine(line1, 1, prodA, "SKU-A", "Prod A",
                new BigDecimal("2"), new BigDecimal("10")),
            new SalesOrderPlaced.PlacedLine(line2, 2, prodB, "SKU-B", "Prod B",
                new BigDecimal("3"), new BigDecimal("20"))
        )));

        verify(projection).applySalesOrderPlaced(SO, line1, prodA);
        verify(projection).applySalesOrderPlaced(SO, line2, prodB);
        verify(inbox).recordProcessed(any());
    }

    @Test void empty_lines_records_processed_without_projection_writes() {
        handler.handle(event(List.of()));

        verifyNoInteractions(projection);
        verify(inbox).recordProcessed(any());
    }

    @Test void already_processed_short_circuits() {
        EventEnvelope envelope = event(List.of(
            new SalesOrderPlaced.PlacedLine(UUID.randomUUID(), 1, UUID.randomUUID(),
                "SKU", "P", new BigDecimal("1"), new BigDecimal("1"))
        ));
        when(inbox.alreadyProcessed(eq(envelope.eventId()),
            eq(SalesOrderPlacedHandler.CONSUMER_NAME))).thenReturn(true);

        handler.handle(envelope);

        verifyNoInteractions(projection);
        verify(inbox, never()).recordProcessed(any());
    }
}
