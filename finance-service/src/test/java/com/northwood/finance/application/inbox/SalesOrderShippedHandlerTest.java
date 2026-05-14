package com.northwood.finance.application.inbox;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.northwood.finance.application.CustomerInvoiceService;
import com.northwood.sales.domain.events.SalesOrderShipped;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.EventEnvelope;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class SalesOrderShippedHandlerTest {

    private static final UUID SO = UUID.randomUUID();

    @Mock InboxPort inbox;
    @Mock CustomerInvoiceService invoices;

    private final ObjectMapper json = new ObjectMapper();
    private SalesOrderShippedHandler handler;

    @BeforeEach
    void setUp() {
        handler = new SalesOrderShippedHandler(inbox, invoices, json);
    }

    private EventEnvelope event() {
        UUID eventId = UUID.randomUUID();
        SalesOrderShipped payload = new SalesOrderShipped(
            eventId, SO, "SO-001", UUID.randomUUID(), "SHP-001",
            UUID.randomUUID(), "CUST-001", "Acme",
            LocalDate.now(), "AUD",
            List.of(new SalesOrderShipped.ShippedLine(
                UUID.randomUUID(), 10, UUID.randomUUID(), "SKU", "Product",
                new BigDecimal("2"), new BigDecimal("100.00"), new BigDecimal("0.10")
            )),
            Instant.now()
        );
        return new EventEnvelope(
            eventId, "SalesOrder", SO,
            SalesOrderShipped.EVENT_TYPE, 1,
            json.writeValueAsString(payload),
            null, null, null, null, Instant.now()
        );
    }

    @Test void delegates_to_invoice_service_and_records_processed() {
        handler.handle(event());

        verify(invoices).createFromShippedOrder(any(SalesOrderShipped.class));
        verify(inbox).recordProcessed(any());
    }

    @Test void already_processed_short_circuits() {
        EventEnvelope envelope = event();
        when(inbox.alreadyProcessed(eq(envelope.eventId()),
            eq(SalesOrderShippedHandler.CONSUMER_NAME))).thenReturn(true);

        handler.handle(envelope);

        verifyNoInteractions(invoices);
        verify(inbox, never()).recordProcessed(any());
    }

    @Test void wrong_event_type_is_no_op() {
        EventEnvelope wrong = new EventEnvelope(
            UUID.randomUUID(), "SalesOrder", UUID.randomUUID(),
            "sales.SomethingElse", 1, "{}",
            null, null, null, null, Instant.now()
        );

        handler.handle(wrong);

        verify(inbox, never()).alreadyProcessed(any(), any());
        verifyNoInteractions(invoices);
    }
}
