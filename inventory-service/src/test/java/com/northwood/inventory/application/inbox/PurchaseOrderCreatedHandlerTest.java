package com.northwood.inventory.application.inbox;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.northwood.purchasing.domain.events.PurchaseOrderCreated;
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
class PurchaseOrderCreatedHandlerTest {

    private static final UUID PO = UUID.randomUUID();

    @Mock InboxPort inbox;
    @Mock PurchaseOrderLineFactsProjection projection;

    private final ObjectMapper json = new ObjectMapper();
    private PurchaseOrderCreatedHandler handler;

    @BeforeEach
    void setUp() {
        handler = new PurchaseOrderCreatedHandler(inbox, projection, json);
    }

    private EventEnvelope event(List<PurchaseOrderCreated.OrderLine> lines) {
        UUID eventId = UUID.randomUUID();
        PurchaseOrderCreated payload = new PurchaseOrderCreated(
            eventId, PO, "PO-001",
            UUID.randomUUID(), "SUP-001", "Acme Supplies",
            UUID.randomUUID(), null, "AUD",
            new BigDecimal("500.00"), "draft",
            lines, Instant.now()
        );
        return new EventEnvelope(
            eventId, "PurchaseOrder", PO,
            PurchaseOrderCreated.EVENT_TYPE, 1,
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
            new PurchaseOrderCreated.OrderLine(line1, 1, prodA, "RM-A", "Raw A",
                new BigDecimal("10"), new BigDecimal("5")),
            new PurchaseOrderCreated.OrderLine(line2, 2, prodB, "RM-B", "Raw B",
                new BigDecimal("20"), new BigDecimal("3"))
        )));

        verify(projection).applyPurchaseOrderCreated(PO, line1, prodA);
        verify(projection).applyPurchaseOrderCreated(PO, line2, prodB);
        verify(inbox).recordProcessed(any());
    }

    @Test void empty_lines_records_processed_without_projection_writes() {
        handler.handle(event(List.of()));

        verifyNoInteractions(projection);
        verify(inbox).recordProcessed(any());
    }

    @Test void already_processed_short_circuits() {
        EventEnvelope envelope = event(List.of(
            new PurchaseOrderCreated.OrderLine(UUID.randomUUID(), 1, UUID.randomUUID(),
                "RM", "R", new BigDecimal("1"), new BigDecimal("1"))
        ));
        when(inbox.alreadyProcessed(eq(envelope.eventId()),
            eq(PurchaseOrderCreatedHandler.CONSUMER_NAME))).thenReturn(true);

        handler.handle(envelope);

        verifyNoInteractions(projection);
        verify(inbox, never()).recordProcessed(any());
    }
}
