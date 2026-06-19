package com.northwood.inventory.application.inbox;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.northwood.inventory.application.StockReservationService;
import com.northwood.sales.domain.SalesAggregateTypes;
import com.northwood.sales.domain.events.SalesOrderCancellationRequested;
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
class SalesOrderCancellationRequestedHandlerTest {

    private static final UUID SO = UUID.randomUUID();

    @Mock InboxPort inbox;
    @Mock StockReservationService reservation;
    @Mock SalesOrderLineFactsProjection salesOrderLineFacts;

    private final ObjectMapper json = new ObjectMapper();
    private SalesOrderCancellationRequestedHandler handler;

    @BeforeEach
    void setUp() {
        handler = new SalesOrderCancellationRequestedHandler(inbox, reservation, salesOrderLineFacts, json);
    }

    private EventEnvelope event() {
        UUID eventId = UUID.randomUUID();
        SalesOrderCancellationRequested payload = new SalesOrderCancellationRequested(
            eventId, SO, "SO-001", UUID.randomUUID(),
            "customer requested", Instant.now()
        );
        return new EventEnvelope(
            eventId, SalesAggregateTypes.SALES_ORDER, SO,
            SalesOrderCancellationRequested.EVENT_TYPE, 1,
            json.writeValueAsString(payload),
            null, null, null, null, Instant.now()
        );
    }

    @Test void applied_when_no_line_shipped_releases_and_records_processed() {
        when(salesOrderLineFacts.tryClaimCancellation(SO)).thenReturn(true);

        handler.handle(event());

        verify(reservation).releaseForSalesOrder(SO);
        verify(inbox).recordProcessed(any());
    }

    @Test void rejected_when_a_line_already_shipped_does_not_release() {
        // A shipment won the cancel-vs-ship race; the cancellation must not release
        // stock (already consumed) and must not ack (so sales never confirms cancel).
        when(salesOrderLineFacts.tryClaimCancellation(SO)).thenReturn(false);

        handler.handle(event());

        verify(reservation, never()).releaseForSalesOrder(any());
        verify(inbox).recordProcessed(any());
    }

    @Test void already_processed_short_circuits() {
        EventEnvelope envelope = event();
        when(inbox.alreadyProcessed(eq(envelope.eventId()),
            eq(SalesOrderCancellationRequestedHandler.HANDLER_NAME))).thenReturn(true);

        handler.handle(envelope);

        verifyNoInteractions(reservation);
        verify(inbox, never()).recordProcessed(any());
    }

    @Test void wrong_event_type_is_no_op() {
        EventEnvelope wrong = new EventEnvelope(
            UUID.randomUUID(), SalesAggregateTypes.SALES_ORDER, UUID.randomUUID(),
            "sales.SomethingElse", 1, "{}",
            null, null, null, null, Instant.now()
        );

        handler.handle(wrong);

        verify(inbox, never()).alreadyProcessed(any(), any());
        verifyNoInteractions(reservation);
    }
}
