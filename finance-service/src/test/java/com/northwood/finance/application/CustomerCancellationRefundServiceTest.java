package com.northwood.finance.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.northwood.finance.domain.CustomerInvoice;
import com.northwood.finance.domain.CustomerInvoiceRepository;
import com.northwood.finance.domain.CustomerInvoiceRepository.PaymentSnapshot;
import com.northwood.finance.domain.CustomerInvoiceRepository.ShipmentTimeInvoice;
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

/**
 * The refund logic now lives in {@link CustomerCancellationRefundService} (the three
 * thin terminal-event handlers — compensated / compensation-failed / rejected — all
 * delegate here). These cases moved verbatim from the old
 * {@code SalesOrderCancellationRefundHandlerTest}; the only behavioural change is the
 * <em>trigger</em> (a confirmed terminal, not the cancel request), which is asserted
 * at the acceptance tier.
 */
@ExtendWith(MockitoExtension.class)
class CustomerCancellationRefundServiceTest {

    private static final UUID ORDER = UUID.randomUUID();
    private static final UUID INVOICE = UUID.randomUUID();

    @Mock JournalEntryService journals;
    @Mock CustomerInvoiceRepository customerInvoices;

    private CustomerCancellationRefundService service;

    @BeforeEach
    void setUp() {
        service = new CustomerCancellationRefundService(journals, customerInvoices);
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

        service.refundUpfrontIfPaid(ORDER, Instant.now());

        verify(journals).postCustomerRefund(
            eq(INVOICE), eq("Acme Corp"), eq("INV-001"),
            eq(new BigDecimal("150.00")), eq(Currencies.AUD), any());
    }

    @Test void paid_prepayment_order_refunds() {
        when(customerInvoices.findInvoiceForShipment(ORDER))
            .thenReturn(Optional.of(invoice(CustomerInvoice.InvoiceType.PREPAYMENT)));
        stubPaid("150.00");
        when(customerInvoices.markRefunded(INVOICE)).thenReturn(true);

        service.refundUpfrontIfPaid(ORDER, Instant.now());

        verify(journals).postCustomerRefund(eq(INVOICE), any(), any(), eq(new BigDecimal("150.00")), any(), any());
    }

    @Test void commercial_invoice_does_not_refund() {
        when(customerInvoices.findInvoiceForShipment(ORDER))
            .thenReturn(Optional.of(invoice(CustomerInvoice.InvoiceType.COMMERCIAL)));

        service.refundUpfrontIfPaid(ORDER, Instant.now());

        verify(journals, never()).postCustomerRefund(any(), any(), any(), any(), any(), any());
        verify(customerInvoices, never()).markRefunded(any());
    }

    @Test void no_invoice_does_not_refund() {
        when(customerInvoices.findInvoiceForShipment(ORDER)).thenReturn(Optional.empty());

        service.refundUpfrontIfPaid(ORDER, Instant.now());

        verify(journals, never()).postCustomerRefund(any(), any(), any(), any(), any(), any());
    }

    @Test void unpaid_deposit_does_not_refund() {
        when(customerInvoices.findInvoiceForShipment(ORDER))
            .thenReturn(Optional.of(invoice(CustomerInvoice.InvoiceType.DEPOSIT)));
        stubPaid("0");

        service.refundUpfrontIfPaid(ORDER, Instant.now());

        verify(journals, never()).postCustomerRefund(any(), any(), any(), any(), any(), any());
        verify(customerInvoices, never()).markRefunded(any());
    }

    @Test void already_refunded_does_not_post_twice() {
        when(customerInvoices.findInvoiceForShipment(ORDER))
            .thenReturn(Optional.of(invoice(CustomerInvoice.InvoiceType.DEPOSIT)));
        stubPaid("150.00");
        when(customerInvoices.markRefunded(INVOICE)).thenReturn(false);

        service.refundUpfrontIfPaid(ORDER, Instant.now());

        verify(journals, never()).postCustomerRefund(any(), any(), any(), any(), any(), any());
    }
}
