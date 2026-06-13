package com.northwood.sales.application.inbox;

import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.COMPLETED;
import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.PREPAID;
import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.SUPPLY_SECURED;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.northwood.finance.domain.FinanceAggregateTypes;
import com.northwood.finance.domain.events.CustomerPaymentReceived;
import com.northwood.sales.application.SalesOrderService;
import com.northwood.sales.application.saga.SalesOrderFulfilmentSagaManager;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.EventEnvelope;
import com.northwood.shared.domain.Currencies;
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
    @Mock SalesOrderService salesOrders;
    @Mock com.northwood.sales.application.SalesOrderUpfrontPaymentSettledEmitter upfrontSettledEmitter;

    private final ObjectMapper json = new ObjectMapper();
    private CustomerPaymentReceivedHandler handler;

    @BeforeEach
    void setUp() {
        handler = new CustomerPaymentReceivedHandler(inbox, sagaManager, salesOrders, upfrontSettledEmitter, json);
    }

    private EventEnvelope event(String invoiceStatusAfter, boolean orderFullySettled) {
        UUID eventId = UUID.randomUUID();
        CustomerPaymentReceived payload = new CustomerPaymentReceived(
            eventId, UUID.randomUUID(), "PMT-001",
            UUID.randomUUID(), SO, UUID.randomUUID(), "Acme",
            "EFT", Currencies.AUD,
            new BigDecimal("1000.00"), new BigDecimal("1000.00"),
            invoiceStatusAfter, orderFullySettled, Instant.now()
        );
        return new EventEnvelope(
            eventId, FinanceAggregateTypes.PAYMENT, UUID.randomUUID(),
            CustomerPaymentReceived.EVENT_TYPE, 1,
            json.writeValueAsString(payload),
            null, null, null, null, Instant.now()
        );
    }

    @Test void full_settlement_completes_the_order() {
        // invoice paid AND order fully settled → saga completes.
        when(sagaManager.applyCustomerPaymentReceived(eq(SO), eq(true), eq(true))).thenReturn(COMPLETED);

        handler.handle(event("paid", true));

        verify(sagaManager).applyCustomerPaymentReceived(SO, true, true);
        verify(salesOrders).completeOrder(SO);
        verify(inbox).recordProcessed(any());
    }

    @Test void partial_payment_does_not_complete() {
        // Gate holds at supply_secured (or wherever the saga is) — not completed.
        when(sagaManager.applyCustomerPaymentReceived(eq(SO), eq(false), eq(false))).thenReturn(SUPPLY_SECURED);

        handler.handle(event("partially_paid", false));

        verify(sagaManager).applyCustomerPaymentReceived(SO, false, false);
        verify(salesOrders, never()).completeOrder(any());
        verify(upfrontSettledEmitter, never()).emitUpfrontPaymentSettled(any());
    }

    @Test void invoice_paid_but_order_not_settled_does_not_complete() {
        // One per-shipment invoice fully paid, but the order still owes another
        // shipment's invoice → invoiceFullySettled=true, orderFullySettled=false.
        when(sagaManager.applyCustomerPaymentReceived(eq(SO), eq(true), eq(false))).thenReturn(SUPPLY_SECURED);

        handler.handle(event("paid", false));

        verify(sagaManager).applyCustomerPaymentReceived(SO, true, false);
        verify(salesOrders, never()).completeOrder(any());
    }

    @Test void upfront_settlement_lifts_the_inventory_shipment_gate() {
        // Prepayment/deposit up-front invoice paid → saga reaches prepaid → emit
        // SalesOrderPrepaymentSettled so inventory lifts the shipment gate.
        when(sagaManager.applyCustomerPaymentReceived(eq(SO), eq(true), org.mockito.ArgumentMatchers.anyBoolean()))
            .thenReturn(PREPAID);

        handler.handle(event("paid", true));

        verify(upfrontSettledEmitter).emitUpfrontPaymentSettled(SO);
        verify(salesOrders, never()).completeOrder(any());
    }

    @Test void already_processed_short_circuits() {
        EventEnvelope envelope = event("paid", true);
        when(inbox.alreadyProcessed(eq(envelope.eventId()), eq(CustomerPaymentReceivedHandler.HANDLER_NAME)))
            .thenReturn(true);

        handler.handle(envelope);

        verify(sagaManager, never()).applyCustomerPaymentReceived(any(), org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.anyBoolean());
        verifyNoInteractions(salesOrders);
    }

    @Test void wrong_event_type_is_no_op() {
        EventEnvelope wrong = new EventEnvelope(
            UUID.randomUUID(), FinanceAggregateTypes.PAYMENT, UUID.randomUUID(),
            "finance.SomethingElse", 1, "{}",
            null, null, null, null, Instant.now()
        );

        handler.handle(wrong);

        verify(inbox, never()).alreadyProcessed(any(), any());
        verify(sagaManager, never()).applyCustomerPaymentReceived(any(), org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.anyBoolean());
    }
}
