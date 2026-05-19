package com.northwood.sales.application.inbox;

import com.northwood.sales.domain.SalesOrder;

import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.COMPLETED;
import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.INVOICE_PAID;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.northwood.finance.domain.FinanceAggregateTypes;
import com.northwood.finance.domain.events.CustomerPaymentReceived;
import com.northwood.sales.application.saga.SalesOrderFulfilmentSagaManager;
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
class CustomerPaymentReceivedHandlerTest {

    private static final UUID SO = UUID.randomUUID();

    @Mock InboxPort inbox;
    @Mock SalesOrderFulfilmentSagaManager sagaManager;
    @Mock SalesOrderHeaderStatusProjection statusProjection;

    private final ObjectMapper json = new ObjectMapper();
    private CustomerPaymentReceivedHandler handler;

    @BeforeEach
    void setUp() {
        handler = new CustomerPaymentReceivedHandler(inbox, sagaManager, statusProjection, json);
    }

    private EventEnvelope event(String invoiceStatusAfter) {
        UUID eventId = UUID.randomUUID();
        CustomerPaymentReceived payload = new CustomerPaymentReceived(
            eventId, UUID.randomUUID(), "PMT-001",
            UUID.randomUUID(), SO, UUID.randomUUID(), "Acme",
            "EFT", "AUD",
            new BigDecimal("1000.00"), new BigDecimal("1000.00"),
            invoiceStatusAfter, Instant.now()
        );
        return new EventEnvelope(
            eventId, FinanceAggregateTypes.PAYMENT, UUID.randomUUID(),
            CustomerPaymentReceived.EVENT_TYPE, 1,
            json.writeValueAsString(payload),
            null, null, null, null, Instant.now()
        );
    }

    @Test void full_settlement_triggers_completed_projection() {
        when(sagaManager.applyCustomerPaymentReceived(eq(SO), eq(true))).thenReturn(COMPLETED);

        handler.handle(event("paid"));

        verify(sagaManager).applyCustomerPaymentReceived(SO, true);
        verify(statusProjection).markStatus(SO, SalesOrder.Status.COMPLETED);
        verify(inbox).recordProcessed(any());
    }

    @Test void partial_payment_does_not_project_completed() {
        when(sagaManager.applyCustomerPaymentReceived(eq(SO), eq(false))).thenReturn(INVOICE_PAID);

        handler.handle(event("partially_paid"));

        verify(sagaManager).applyCustomerPaymentReceived(SO, false);
        verify(statusProjection, never()).markStatus(any(), any());
    }

    @Test void already_processed_short_circuits() {
        EventEnvelope envelope = event("paid");
        when(inbox.alreadyProcessed(eq(envelope.eventId()), eq(CustomerPaymentReceivedHandler.CONSUMER_NAME)))
            .thenReturn(true);

        handler.handle(envelope);

        verify(sagaManager, never()).applyCustomerPaymentReceived(any(), org.mockito.ArgumentMatchers.anyBoolean());
        verifyNoInteractions(statusProjection);
    }

    @Test void wrong_event_type_is_no_op() {
        EventEnvelope wrong = new EventEnvelope(
            UUID.randomUUID(), FinanceAggregateTypes.PAYMENT, UUID.randomUUID(),
            "finance.SomethingElse", 1, "{}",
            null, null, null, null, Instant.now()
        );

        handler.handle(wrong);

        verify(inbox, never()).alreadyProcessed(any(), any());
        verify(sagaManager, never()).applyCustomerPaymentReceived(any(), org.mockito.ArgumentMatchers.anyBoolean());
    }
}
