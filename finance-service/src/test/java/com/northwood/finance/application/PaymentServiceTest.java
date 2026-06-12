package com.northwood.finance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.northwood.finance.application.dto.RecordCustomerPaymentCommand;
import com.northwood.finance.application.dto.RecordCustomerPaymentMultiCommand;
import com.northwood.finance.application.dto.RecordSupplierPaymentCommand;
import com.northwood.finance.application.dto.RecordSupplierPaymentMultiCommand;
import com.northwood.finance.domain.CustomerInvoice;
import com.northwood.finance.domain.CustomerInvoiceRepository;
import com.northwood.finance.domain.Payment;
import com.northwood.finance.domain.PaymentRepository;
import com.northwood.finance.domain.SupplierInvoice;
import com.northwood.finance.domain.SupplierInvoiceRepository;
import com.northwood.finance.domain.events.CustomerPaymentReceived;
import com.northwood.finance.domain.events.SupplierPaymentMade;
import com.northwood.shared.domain.Currencies;
import com.northwood.shared.domain.DomainEvent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    private static final UUID SUPPLIER = UUID.randomUUID();
    private static final UUID OTHER_SUPPLIER = UUID.randomUUID();
    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final UUID OTHER_CUSTOMER = UUID.randomUUID();
    private static final UUID PO = UUID.randomUUID();
    private static final UUID SO = UUID.randomUUID();
    private static final LocalDate PAY_DATE = LocalDate.of(2026, 6, 1);

    @Mock PaymentRepository payments;
    @Mock SupplierInvoiceRepository supplierInvoices;
    @Mock CustomerInvoiceRepository customerInvoices;
    @Mock JournalEntryService journals;

    private PaymentService service;

    @BeforeEach
    void setUp() {
        service = new PaymentService(payments, supplierInvoices, customerInvoices, journals);
    }

    private SupplierInvoiceRepository.PaymentSnapshot supplierSnap(
        UUID supplierId, String currency, String status, String total, String paid
    ) {
        return new SupplierInvoiceRepository.PaymentSnapshot(
            supplierId, "Supplier-" + supplierId.toString().substring(0, 4),
            PO, currency, new BigDecimal(total), new BigDecimal(paid),
            SupplierInvoice.Status.fromCode(status)
        );
    }

    private CustomerInvoiceRepository.PaymentSnapshot customerSnap(
        UUID customerId, String currency, String status, String total, String paid
    ) {
        return customerSnap(customerId, currency, status, total, paid, CustomerInvoice.InvoiceType.COMMERCIAL);
    }

    private CustomerInvoiceRepository.PaymentSnapshot customerSnap(
        UUID customerId, String currency, String status, String total, String paid,
        CustomerInvoice.InvoiceType invoiceType
    ) {
        return new CustomerInvoiceRepository.PaymentSnapshot(
            customerId, "Customer-" + customerId.toString().substring(0, 4),
            SO, currency, new BigDecimal(total), new BigDecimal(paid),
            CustomerInvoice.Status.fromCode(status),
            invoiceType
        );
    }

    private Payment capturedPayment() {
        ArgumentCaptor<Payment> cap = ArgumentCaptor.forClass(Payment.class);
        verify(payments).save(cap.capture());
        return cap.getValue();
    }

    private SupplierPaymentMade firstSupplierEvent(Payment p) {
        List<DomainEvent> events = p.pullPendingEvents();
        return (SupplierPaymentMade) events.get(0);
    }

    private CustomerPaymentReceived firstCustomerEvent(Payment p) {
        List<DomainEvent> events = p.pullPendingEvents();
        return (CustomerPaymentReceived) events.get(0);
    }

    @Nested
    class SingleSupplierPayment {

        @Test void approved_invoice_full_pay_emits_paid_status_and_posts_journal() {
            UUID invoiceId = UUID.randomUUID();
            when(supplierInvoices.findPaymentSnapshot(invoiceId)).thenReturn(Optional.of(
                supplierSnap(SUPPLIER, Currencies.AUD, "approved", "1100.00", "0.00")
            ));

            service.recordSupplierPayment(new RecordSupplierPaymentCommand(
                "PMT-001", invoiceId, new BigDecimal("1100.00"), "bank_transfer", PAY_DATE
            ));

            SupplierPaymentMade event = firstSupplierEvent(capturedPayment());
            assertThat(event.invoiceStatusAfter()).isEqualTo("paid");
            verify(journals).postSupplierPayment(
                any(), any(), eq("PMT-001"), eq(new BigDecimal("1100.00")), eq(Currencies.AUD), eq(PAY_DATE)
            );
        }

        @Test void partial_payment_emits_partially_paid_status() {
            UUID invoiceId = UUID.randomUUID();
            when(supplierInvoices.findPaymentSnapshot(invoiceId)).thenReturn(Optional.of(
                supplierSnap(SUPPLIER, Currencies.AUD, "approved", "1000.00", "0.00")
            ));

            service.recordSupplierPayment(new RecordSupplierPaymentCommand(
                "PMT-002", invoiceId, new BigDecimal("400.00"), "bank_transfer", PAY_DATE
            ));

            SupplierPaymentMade event = firstSupplierEvent(capturedPayment());
            assertThat(event.invoiceStatusAfter()).isEqualTo("partially_paid");
        }

        @Test void partially_paid_input_status_is_accepted_for_top_up() {
            UUID invoiceId = UUID.randomUUID();
            when(supplierInvoices.findPaymentSnapshot(invoiceId)).thenReturn(Optional.of(
                supplierSnap(SUPPLIER, Currencies.AUD, "partially_paid", "1000.00", "400.00")
            ));

            service.recordSupplierPayment(new RecordSupplierPaymentCommand(
                "PMT-003", invoiceId, new BigDecimal("600.00"), "bank_transfer", PAY_DATE
            ));

            SupplierPaymentMade event = firstSupplierEvent(capturedPayment());
            assertThat(event.invoiceStatusAfter()).isEqualTo("paid");
        }

        @Test void rejects_draft_invoice_status() {
            UUID invoiceId = UUID.randomUUID();
            when(supplierInvoices.findPaymentSnapshot(invoiceId)).thenReturn(Optional.of(
                supplierSnap(SUPPLIER, Currencies.AUD, "draft", "1000.00", "0.00")
            ));

            assertThatThrownBy(() -> service.recordSupplierPayment(new RecordSupplierPaymentCommand(
                "PMT-X", invoiceId, new BigDecimal("100.00"), "bank_transfer", PAY_DATE)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be approved or partially_paid");
            verify(payments, never()).save(any());
            verify(journals, never()).postSupplierPayment(any(), any(), any(), any(), any(), any());
        }

        @Test void rejects_when_amount_exceeds_outstanding() {
            UUID invoiceId = UUID.randomUUID();
            when(supplierInvoices.findPaymentSnapshot(invoiceId)).thenReturn(Optional.of(
                supplierSnap(SUPPLIER, Currencies.AUD, "approved", "500.00", "0.00")
            ));

            assertThatThrownBy(() -> service.recordSupplierPayment(new RecordSupplierPaymentCommand(
                "PMT-X", invoiceId, new BigDecimal("600.00"), "bank_transfer", PAY_DATE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds outstanding");
            verify(payments, never()).save(any());
        }

        @Test void rejects_unknown_invoice() {
            UUID invoiceId = UUID.randomUUID();
            when(supplierInvoices.findPaymentSnapshot(invoiceId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.recordSupplierPayment(new RecordSupplierPaymentCommand(
                "PMT-X", invoiceId, new BigDecimal("100.00"), "bank_transfer", PAY_DATE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No supplier invoice");
        }
    }

    @Nested
    class SingleCustomerPayment {

        @Test void posted_invoice_full_pay_emits_paid_and_posts_journal() {
            UUID invoiceId = UUID.randomUUID();
            when(customerInvoices.findPaymentSnapshot(invoiceId)).thenReturn(Optional.of(
                customerSnap(CUSTOMER, Currencies.AUD, "posted", "550.00", "0.00")
            ));
            // Order has only this invoice, 550 outstanding → paying 550 settles the order.
            when(customerInvoices.sumOutstandingForOrder(any())).thenReturn(new BigDecimal("550.00"));

            service.recordCustomerPayment(new RecordCustomerPaymentCommand(
                "PMT-C-001", invoiceId, new BigDecimal("550.00"), "bank_transfer", PAY_DATE
            ));

            CustomerPaymentReceived event = firstCustomerEvent(capturedPayment());
            assertThat(event.invoiceStatusAfter()).isEqualTo("paid");
            assertThat(event.orderFullySettled()).isTrue();
            verify(journals).postCustomerPayment(
                any(), any(), eq("PMT-C-001"), eq(new BigDecimal("550.00")), eq(Currencies.AUD), eq(PAY_DATE),
                eq(CustomerInvoice.InvoiceType.COMMERCIAL)
            );
        }

        @Test void invoice_paid_but_order_has_more_outstanding_reports_order_not_settled() {
            // Partial-shipment case: this per-shipment invoice (550) is paid in
            // full, but the order has another shipment's invoice still owing
            // (1200 outstanding across the order) → orderFullySettled must be false
            // so sales' saga does NOT complete the order.
            UUID invoiceId = UUID.randomUUID();
            when(customerInvoices.findPaymentSnapshot(invoiceId)).thenReturn(Optional.of(
                customerSnap(CUSTOMER, Currencies.AUD, "posted", "550.00", "0.00")
            ));
            when(customerInvoices.sumOutstandingForOrder(any())).thenReturn(new BigDecimal("1200.00"));

            service.recordCustomerPayment(new RecordCustomerPaymentCommand(
                "PMT-C-002", invoiceId, new BigDecimal("550.00"), "bank_transfer", PAY_DATE
            ));

            CustomerPaymentReceived event = firstCustomerEvent(capturedPayment());
            assertThat(event.invoiceStatusAfter()).isEqualTo("paid");
            assertThat(event.orderFullySettled()).isFalse();
        }

        @Test void rejects_draft_invoice_status() {
            UUID invoiceId = UUID.randomUUID();
            when(customerInvoices.findPaymentSnapshot(invoiceId)).thenReturn(Optional.of(
                customerSnap(CUSTOMER, Currencies.AUD, "draft", "100.00", "0.00")
            ));

            assertThatThrownBy(() -> service.recordCustomerPayment(new RecordCustomerPaymentCommand(
                "PMT-X", invoiceId, new BigDecimal("100.00"), "bank_transfer", PAY_DATE)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be posted or partially_paid");
        }

        @Test void rejects_amount_exceeds_outstanding() {
            UUID invoiceId = UUID.randomUUID();
            when(customerInvoices.findPaymentSnapshot(invoiceId)).thenReturn(Optional.of(
                customerSnap(CUSTOMER, Currencies.AUD, "posted", "500.00", "0.00")
            ));

            assertThatThrownBy(() -> service.recordCustomerPayment(new RecordCustomerPaymentCommand(
                "PMT-X", invoiceId, new BigDecimal("600.00"), "bank_transfer", PAY_DATE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds outstanding");
        }
    }

    @Nested
    class MultiSupplierPayment {

        @Test void two_invoices_same_supplier_posts_single_combined_journal() {
            UUID inv1 = UUID.randomUUID();
            UUID inv2 = UUID.randomUUID();
            when(supplierInvoices.findPaymentSnapshot(inv1)).thenReturn(Optional.of(
                supplierSnap(SUPPLIER, Currencies.AUD, "approved", "300.00", "0.00")
            ));
            when(supplierInvoices.findPaymentSnapshot(inv2)).thenReturn(Optional.of(
                supplierSnap(SUPPLIER, Currencies.AUD, "approved", "700.00", "0.00")
            ));

            service.recordSupplierPaymentMulti(new RecordSupplierPaymentMultiCommand(
                "PMT-M-001", "bank_transfer", PAY_DATE,
                List.of(
                    new RecordSupplierPaymentMultiCommand.InvoiceLine(inv1, new BigDecimal("300.00")),
                    new RecordSupplierPaymentMultiCommand.InvoiceLine(inv2, new BigDecimal("700.00"))
                )
            ));

            verify(payments, times(1)).save(any());
            verify(journals).postSupplierPayment(
                any(), any(), eq("PMT-M-001"), eq(new BigDecimal("1000.00")), eq(Currencies.AUD), eq(PAY_DATE)
            );
        }

        @Test void rejects_cross_supplier_invoices() {
            UUID inv1 = UUID.randomUUID();
            UUID inv2 = UUID.randomUUID();
            when(supplierInvoices.findPaymentSnapshot(inv1)).thenReturn(Optional.of(
                supplierSnap(SUPPLIER, Currencies.AUD, "approved", "300.00", "0.00")
            ));
            when(supplierInvoices.findPaymentSnapshot(inv2)).thenReturn(Optional.of(
                supplierSnap(OTHER_SUPPLIER, Currencies.AUD, "approved", "200.00", "0.00")
            ));

            assertThatThrownBy(() -> service.recordSupplierPaymentMulti(new RecordSupplierPaymentMultiCommand(
                "PMT-X", "bank_transfer", PAY_DATE,
                List.of(
                    new RecordSupplierPaymentMultiCommand.InvoiceLine(inv1, new BigDecimal("300.00")),
                    new RecordSupplierPaymentMultiCommand.InvoiceLine(inv2, new BigDecimal("200.00"))
                ))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("same supplier");
            verify(payments, never()).save(any());
        }

        @Test void rejects_cross_currency_invoices() {
            UUID inv1 = UUID.randomUUID();
            UUID inv2 = UUID.randomUUID();
            when(supplierInvoices.findPaymentSnapshot(inv1)).thenReturn(Optional.of(
                supplierSnap(SUPPLIER, Currencies.AUD, "approved", "300.00", "0.00")
            ));
            when(supplierInvoices.findPaymentSnapshot(inv2)).thenReturn(Optional.of(
                supplierSnap(SUPPLIER, Currencies.USD, "approved", "200.00", "0.00")
            ));

            assertThatThrownBy(() -> service.recordSupplierPaymentMulti(new RecordSupplierPaymentMultiCommand(
                "PMT-X", "bank_transfer", PAY_DATE,
                List.of(
                    new RecordSupplierPaymentMultiCommand.InvoiceLine(inv1, new BigDecimal("300.00")),
                    new RecordSupplierPaymentMultiCommand.InvoiceLine(inv2, new BigDecimal("200.00"))
                ))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("same currency");
        }

        @Test void rejects_empty_invoice_list() {
            assertThatThrownBy(() -> service.recordSupplierPaymentMulti(new RecordSupplierPaymentMultiCommand(
                "PMT-X", "bank_transfer", PAY_DATE, List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one");
        }

        @Test void rejects_when_per_line_amount_exceeds_outstanding() {
            UUID inv1 = UUID.randomUUID();
            when(supplierInvoices.findPaymentSnapshot(inv1)).thenReturn(Optional.of(
                supplierSnap(SUPPLIER, Currencies.AUD, "approved", "100.00", "0.00")
            ));

            assertThatThrownBy(() -> service.recordSupplierPaymentMulti(new RecordSupplierPaymentMultiCommand(
                "PMT-X", "bank_transfer", PAY_DATE,
                List.of(new RecordSupplierPaymentMultiCommand.InvoiceLine(inv1, new BigDecimal("200.00"))))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds outstanding");
        }
    }

    @Nested
    class MultiCustomerPayment {

        @Test void rejects_cross_customer_invoices() {
            UUID inv1 = UUID.randomUUID();
            UUID inv2 = UUID.randomUUID();
            when(customerInvoices.findPaymentSnapshot(inv1)).thenReturn(Optional.of(
                customerSnap(CUSTOMER, Currencies.AUD, "posted", "300.00", "0.00")
            ));
            when(customerInvoices.findPaymentSnapshot(inv2)).thenReturn(Optional.of(
                customerSnap(OTHER_CUSTOMER, Currencies.AUD, "posted", "200.00", "0.00")
            ));

            assertThatThrownBy(() -> service.recordCustomerPaymentMulti(new RecordCustomerPaymentMultiCommand(
                "PMT-X", "bank_transfer", PAY_DATE,
                List.of(
                    new RecordCustomerPaymentMultiCommand.InvoiceLine(inv1, new BigDecimal("300.00")),
                    new RecordCustomerPaymentMultiCommand.InvoiceLine(inv2, new BigDecimal("200.00"))
                ))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("same customer");
            verify(payments, never()).save(any());
        }
    }
}
