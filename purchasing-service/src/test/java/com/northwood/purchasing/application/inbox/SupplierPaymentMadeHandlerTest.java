package com.northwood.purchasing.application.inbox;

import static com.northwood.purchasing.domain.saga.PurchaseToPaySaga.COMPLETED;
import static com.northwood.purchasing.domain.saga.PurchaseToPaySaga.SUPPLIER_PAYMENT_MADE;
import static com.northwood.purchasing.domain.saga.PurchaseToPaySaga.WAITING_FOR_GOODS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.northwood.finance.domain.events.SupplierPaymentMade;
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
class SupplierPaymentMadeHandlerTest {

    private static final UUID PO = UUID.randomUUID();

    @Mock InboxPort inbox;
    @Mock PurchaseToPaySagaManager sagaManager;
    @Mock PurchaseOrderPaymentProjection paymentProjection;

    private final ObjectMapper json = new ObjectMapper();
    private SupplierPaymentMadeHandler handler;

    @BeforeEach
    void setUp() {
        handler = new SupplierPaymentMadeHandler(inbox, sagaManager, paymentProjection, json);
    }

    private EventEnvelope event(String invoiceStatusAfter, String allocatedAmount) {
        UUID eventId = UUID.randomUUID();
        SupplierPaymentMade payload = new SupplierPaymentMade(
            eventId, UUID.randomUUID(), "PMT-001",
            UUID.randomUUID(), PO, UUID.randomUUID(), "Acme",
            "EFT", "AUD",
            new BigDecimal(allocatedAmount), new BigDecimal(allocatedAmount),
            invoiceStatusAfter, Instant.now()
        );
        return new EventEnvelope(
            eventId, "Payment", UUID.randomUUID(),
            SupplierPaymentMade.EVENT_TYPE, 1,
            json.writeValueAsString(payload),
            null, null, null, null, Instant.now()
        );
    }

    @Test void full_settlement_calls_manager_with_true_and_marks_fully_paid() {
        when(sagaManager.applySupplierPaymentMade(eq(PO), eq(true))).thenReturn(COMPLETED);

        handler.handle(event("paid", "1000.00"));

        verify(sagaManager).applySupplierPaymentMade(PO, true);
        verify(paymentProjection).markFullyPaid(PO);
        verify(paymentProjection, never()).addPartialPayment(any(), any());
    }

    @Test void partial_settlement_calls_manager_with_false_and_marks_partial() {
        when(sagaManager.applySupplierPaymentMade(eq(PO), eq(false))).thenReturn(SUPPLIER_PAYMENT_MADE);

        handler.handle(event("partially_paid", "300.00"));

        verify(sagaManager).applySupplierPaymentMade(PO, false);
        verify(paymentProjection).addPartialPayment(PO, new BigDecimal("300.00"));
        verify(paymentProjection, never()).markFullyPaid(any());
    }

    @Test void unrelated_state_returned_by_manager_does_no_projection_writes() {
        // Manager returns the unchanged state — handler skips projection writes.
        when(sagaManager.applySupplierPaymentMade(eq(PO), anyBoolean())).thenReturn(WAITING_FOR_GOODS);

        handler.handle(event("paid", "1000.00"));

        verifyNoInteractions(paymentProjection);
    }

    @Test void already_processed_short_circuits() {
        EventEnvelope envelope = event("paid", "1000.00");
        when(inbox.alreadyProcessed(eq(envelope.eventId()),
            eq(SupplierPaymentMadeHandler.CONSUMER_NAME))).thenReturn(true);

        handler.handle(envelope);

        verifyNoInteractions(sagaManager);
        verifyNoInteractions(paymentProjection);
        verify(inbox, never()).recordProcessed(any());
    }

    @Test void wrong_event_type_is_no_op() {
        EventEnvelope wrong = new EventEnvelope(
            UUID.randomUUID(), "Payment", UUID.randomUUID(),
            "finance.SomethingElse", 1, "{}",
            null, null, null, null, Instant.now()
        );

        handler.handle(wrong);

        verify(inbox, never()).alreadyProcessed(any(), any());
        verifyNoInteractions(sagaManager);
    }
}
