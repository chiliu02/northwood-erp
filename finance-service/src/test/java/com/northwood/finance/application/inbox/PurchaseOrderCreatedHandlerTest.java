package com.northwood.finance.application.inbox;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.northwood.purchasing.domain.PurchasingAggregateTypes;
import com.northwood.purchasing.domain.events.PurchaseOrderCreated;
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
class PurchaseOrderCreatedHandlerTest {

    private static final UUID PO = UUID.randomUUID();
    private static final UUID SUPPLIER = UUID.randomUUID();

    @Mock InboxPort inbox;
    @Mock PurchaseOrderLineFactsProjection projection;

    private final ObjectMapper json = new ObjectMapper();
    private PurchaseOrderCreatedHandler handler;

    @BeforeEach
    void setUp() {
        handler = new PurchaseOrderCreatedHandler(inbox, projection, json);
    }

    private PurchaseOrderCreated.OrderLine line(int n, String unitPrice) {
        return new PurchaseOrderCreated.OrderLine(
            UUID.randomUUID(), n, UUID.randomUUID(), "SKU-" + n, "Product " + n,
            new BigDecimal("10"), new BigDecimal(unitPrice)
        );
    }

    private EventEnvelope event(List<PurchaseOrderCreated.OrderLine> lines) {
        UUID eventId = UUID.randomUUID();
        PurchaseOrderCreated payload = new PurchaseOrderCreated(
            eventId, PO, "PO-001", SUPPLIER, "SUP-001", "Acme",
            UUID.randomUUID(), null, null, Currencies.AUD,
            new BigDecimal("0"), "draft", lines, Instant.now()
        );
        return new EventEnvelope(
            eventId, PurchasingAggregateTypes.PURCHASE_ORDER, PO,
            PurchaseOrderCreated.EVENT_TYPE, 1,
            json.writeValueAsString(payload),
            null, null, null, null, Instant.now()
        );
    }

    @Test void seeds_one_projection_row_per_line() {
        handler.handle(event(List.of(line(10, "5.00"), line(20, "12.00"))));

        verify(projection, times(2)).applyPurchaseOrderCreated(
            eq(PO), eq(SUPPLIER), eq("Acme"), eq(Currencies.AUD),
            any(), any(), any(), any(), any(), any()
        );
        verify(inbox).recordProcessed(any());
    }

    @Test void already_processed_short_circuits() {
        EventEnvelope envelope = event(List.of(line(10, "5")));
        when(inbox.alreadyProcessed(eq(envelope.eventId()),
            eq(PurchaseOrderCreatedHandler.HANDLER_NAME))).thenReturn(true);

        handler.handle(envelope);

        verifyNoInteractions(projection);
        verify(inbox, never()).recordProcessed(any());
    }

    @Test void wrong_event_type_is_no_op() {
        EventEnvelope wrong = new EventEnvelope(
            UUID.randomUUID(), PurchasingAggregateTypes.PURCHASE_ORDER, UUID.randomUUID(),
            "purchasing.SomethingElse", 1, "{}",
            null, null, null, null, Instant.now()
        );

        handler.handle(wrong);

        verify(inbox, never()).alreadyProcessed(any(), any());
        verifyNoInteractions(projection);
    }
}
