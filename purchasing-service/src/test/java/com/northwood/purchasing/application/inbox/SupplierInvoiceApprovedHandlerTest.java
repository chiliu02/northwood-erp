package com.northwood.purchasing.application.inbox;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.northwood.finance.domain.FinanceAggregateTypes;
import com.northwood.finance.domain.events.SupplierInvoiceApproved;
import com.northwood.purchasing.application.saga.PurchaseToPaySagaManager;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.EventEnvelope;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class SupplierInvoiceApprovedHandlerTest {

    private static final UUID PO = UUID.randomUUID();

    @Mock InboxPort inbox;
    @Mock PurchaseToPaySagaManager sagaManager;
    @Mock PurchaseOrderPaymentProjection paymentProjection;

    private final ObjectMapper json = new ObjectMapper();
    private SupplierInvoiceApprovedHandler handler;

    @BeforeEach
    void setUp() {
        handler = new SupplierInvoiceApprovedHandler(inbox, sagaManager, paymentProjection, json);
    }

    private EventEnvelope event() {
        UUID eventId = UUID.randomUUID();
        SupplierInvoiceApproved payload = new SupplierInvoiceApproved(
            eventId, UUID.randomUUID(), "INV-001", "SUP-INV-X",
            PO, UUID.randomUUID(), "Acme",
            "AUD", new BigDecimal("1000.00"), Instant.now()
        );
        return new EventEnvelope(
            eventId, FinanceAggregateTypes.SUPPLIER_INVOICE, UUID.randomUUID(),
            SupplierInvoiceApproved.EVENT_TYPE, 1,
            json.writeValueAsString(payload),
            null, null, null, null, Instant.now()
        );
    }

    @Test void delegates_to_manager_bumps_invoiced_amount_and_records_processed() {
        handler.handle(event());

        verify(sagaManager).applySupplierInvoiceApproved(PO);
        verify(paymentProjection).addInvoicedAmount(PO, new BigDecimal("1000.00"));
        verify(inbox).recordProcessed(any());
    }

    @Test void already_processed_short_circuits() {
        EventEnvelope envelope = event();
        when(inbox.alreadyProcessed(eq(envelope.eventId()),
            eq(SupplierInvoiceApprovedHandler.CONSUMER_NAME))).thenReturn(true);

        handler.handle(envelope);

        verifyNoInteractions(sagaManager);
        verifyNoInteractions(paymentProjection);
        verify(inbox, never()).recordProcessed(any());
    }

    @Test void wrong_event_type_is_no_op() {
        EventEnvelope wrong = new EventEnvelope(
            UUID.randomUUID(), FinanceAggregateTypes.SUPPLIER_INVOICE, UUID.randomUUID(),
            "finance.SomethingElse", 1, "{}",
            null, null, null, null, Instant.now()
        );

        handler.handle(wrong);

        verify(inbox, never()).alreadyProcessed(any(), any());
        verifyNoInteractions(sagaManager);
        verifyNoInteractions(paymentProjection);
    }
}
