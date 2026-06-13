package com.northwood.finance.application.inbox;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.northwood.finance.application.JournalEntryService;
import com.northwood.finance.domain.CustomerInvoice;
import com.northwood.finance.domain.CustomerInvoiceRepository;
import com.northwood.finance.domain.CustomerInvoiceRepository.PaymentSnapshot;
import com.northwood.finance.domain.CustomerInvoiceRepository.ShipmentTimeInvoice;
import com.northwood.sales.domain.events.SalesOrderCancellationRequested;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.EventEnvelope;
import com.northwood.shared.domain.Currencies;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class SalesOrderCancellationRefundHandlerTest {

    private static final UUID ORDER = UUID.randomUUID();
    private static final UUID INVOICE = UUID.randomUUID();

    @Mock InboxPort inbox;
    @Mock JournalEntryService journals;
    @Mock CustomerInvoiceRepository customerInvoices;

    private final ObjectMapper json = new ObjectMapper();
    private SalesOrderCancellationRefundHandler handler;

    @BeforeEach
    void setUp() {
        handler = new SalesOrderCancellationRefundHandler(inbox, journals, customerInvoices, json);
    }

    private EventEnvelope event() {
        UUID eventId = UUID.randomUUID();
        SalesOrderCancellationRequested payload = new SalesOrderCancellationRequested(
            eventId, ORDER, "SO-001", UUID.randomUUID(), "customer changed mind", Instant.now());
        return new EventEnvelope(
            eventId, "SalesOrder", ORDER,
            SalesOrderCancellationRequested.EVENT_TYPE, 1, json.writeValueAsString(payload),
            null, null, null, null, Instant.now());
    }

    private ShipmentTimeInvoice invoice(CustomerInvoice.InvoiceType type) {
        return new ShipmentTimeInvoice(
            INVOICE, "INV-001", type, "Acme Corp", Currencies.AUD,
            new BigDecimal("150.00"), false);
    }

    private void stubPaid(String paid) {
        when(customerInvoices.findPaymentSnapshot(INVOICE)).thenReturn(Optional.of(new PaymentSnapshot(
            UUID.randomUUID(), "Acme Corp", ORDER, Currencies.AUD,
            new BigDecimal("150.00"), new BigDecimal(paid),
            CustomerInvoice.Status.PAID, CustomerInvoice.InvoiceType.DEPOSIT)));
    }

    @Test void paid_deposit_order_refunds_dr_deposits_cr_bank() {
        when(customerInvoices.findInvoiceForShipment(ORDER))
            .thenReturn(Optional.of(invoice(CustomerInvoice.InvoiceType.DEPOSIT)));
        stubPaid("150.00");
        when(customerInvoices.markRefunded(INVOICE)).thenReturn(true);

        handler.handle(event());

        verify(journals).postCustomerRefund(
            eq(INVOICE), eq("Acme Corp"), eq("INV-001"),
            eq(new BigDecimal("150.00")), eq(Currencies.AUD), any());
        verify(inbox).recordProcessed(any());
    }

    @Test void paid_prepayment_order_refunds() {
        when(customerInvoices.findInvoiceForShipment(ORDER))
            .thenReturn(Optional.of(invoice(CustomerInvoice.InvoiceType.PREPAYMENT)));
        stubPaid("150.00");
        when(customerInvoices.markRefunded(INVOICE)).thenReturn(true);

        handler.handle(event());

        verify(journals).postCustomerRefund(eq(INVOICE), any(), any(), eq(new BigDecimal("150.00")), any(), any());
    }

    @Test void commercial_invoice_does_not_refund() {
        when(customerInvoices.findInvoiceForShipment(ORDER))
            .thenReturn(Optional.of(invoice(CustomerInvoice.InvoiceType.COMMERCIAL)));

        handler.handle(event());

        verify(journals, never()).postCustomerRefund(any(), any(), any(), any(), any(), any());
        verify(customerInvoices, never()).markRefunded(any());
    }

    @Test void no_invoice_does_not_refund() {
        when(customerInvoices.findInvoiceForShipment(ORDER)).thenReturn(Optional.empty());

        handler.handle(event());

        verify(journals, never()).postCustomerRefund(any(), any(), any(), any(), any(), any());
    }

    @Test void unpaid_deposit_does_not_refund() {
        when(customerInvoices.findInvoiceForShipment(ORDER))
            .thenReturn(Optional.of(invoice(CustomerInvoice.InvoiceType.DEPOSIT)));
        stubPaid("0");

        handler.handle(event());

        verify(journals, never()).postCustomerRefund(any(), any(), any(), any(), any(), any());
        verify(customerInvoices, never()).markRefunded(any());
    }

    @Test void already_refunded_does_not_post_twice() {
        when(customerInvoices.findInvoiceForShipment(ORDER))
            .thenReturn(Optional.of(invoice(CustomerInvoice.InvoiceType.DEPOSIT)));
        stubPaid("150.00");
        when(customerInvoices.markRefunded(INVOICE)).thenReturn(false);

        handler.handle(event());

        verify(journals, never()).postCustomerRefund(any(), any(), any(), any(), any(), any());
    }

    @Test void already_processed_short_circuits() {
        EventEnvelope envelope = event();
        when(inbox.alreadyProcessed(eq(envelope.eventId()), eq(SalesOrderCancellationRefundHandler.HANDLER_NAME)))
            .thenReturn(true);

        handler.handle(envelope);

        verifyNoInteractions(journals);
        verifyNoInteractions(customerInvoices);
        verify(inbox, never()).recordProcessed(any());
    }
}
