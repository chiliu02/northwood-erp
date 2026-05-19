package com.northwood.finance.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northwood.finance.domain.events.CustomerPaymentReceived;
import com.northwood.finance.domain.events.SupplierPaymentMade;
import com.northwood.shared.domain.DomainEvent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PaymentTest {

    private static final UUID SUPPLIER_A = UUID.randomUUID();
    private static final UUID CUSTOMER_A = UUID.randomUUID();
    private static final UUID INVOICE_1 = UUID.randomUUID();
    private static final UUID INVOICE_2 = UUID.randomUUID();
    private static final UUID PO_1 = UUID.randomUUID();
    private static final UUID PO_2 = UUID.randomUUID();
    private static final UUID SO_1 = UUID.randomUUID();

    @Nested
    class SingleInvoiceSupplierPayment {
        @Test void emits_one_event_with_status_after() {
            Payment p = Payment.recordSupplierPayment(
                "PMT-001", SUPPLIER_A, "Acme",
                LocalDate.of(2026, 6, 1), Payment.Method.BANK_TRANSFER,
                "AUD", new BigDecimal("100.00"),
                INVOICE_1, PO_1, "paid"
            );
            List<DomainEvent> events = p.pullPendingEvents();
            assertThat(events).hasSize(1).first().isInstanceOf(SupplierPaymentMade.class);
            SupplierPaymentMade e = (SupplierPaymentMade) events.get(0);
            assertThat(e.supplierInvoiceHeaderId()).isEqualTo(INVOICE_1);
            assertThat(e.purchaseOrderHeaderId()).isEqualTo(PO_1);
            assertThat(e.invoiceStatusAfter()).isEqualTo("paid");
            assertThat(e.allocatedAmount()).isEqualByComparingTo(new BigDecimal("100.00"));
        }

        @Test void single_allocation_on_payment() {
            Payment p = Payment.recordSupplierPayment(
                "PMT-001", SUPPLIER_A, "Acme",
                null, Payment.Method.CASH, "AUD", BigDecimal.TEN,
                INVOICE_1, PO_1, "partially_paid"
            );
            assertThat(p.allocations()).hasSize(1);
            assertThat(p.allocations().get(0).supplierInvoiceHeaderId()).isEqualTo(INVOICE_1);
        }

        @Test void rejects_zero_amount() {
            assertThatThrownBy(() -> Payment.recordSupplierPayment(
                "PMT", SUPPLIER_A, "A", null, Payment.Method.CASH, "AUD", BigDecimal.ZERO,
                INVOICE_1, PO_1, "paid"
            )).isInstanceOf(IllegalArgumentException.class);
        }

        @Test void rejects_null_supplier_id() {
            assertThatThrownBy(() -> Payment.recordSupplierPayment(
                "PMT", null, "A", null, Payment.Method.CASH, "AUD", BigDecimal.TEN,
                INVOICE_1, PO_1, "paid"
            )).isInstanceOf(NullPointerException.class);
        }

        @Test void rejects_null_invoice() {
            assertThatThrownBy(() -> Payment.recordSupplierPayment(
                "PMT", SUPPLIER_A, "A", null, Payment.Method.CASH, "AUD", BigDecimal.TEN,
                null, PO_1, "paid"
            )).isInstanceOf(NullPointerException.class);
        }

        @Test void rejects_null_purchase_order_id() {
            assertThatThrownBy(() -> Payment.recordSupplierPayment(
                "PMT", SUPPLIER_A, "A", null, Payment.Method.CASH, "AUD", BigDecimal.TEN,
                INVOICE_1, null, "paid"
            )).isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    class MultiInvoiceSupplierPayment {
        @Test void emits_one_event_per_allocation() {
            Payment p = Payment.recordMultiSupplierPayment(
                "PMT-MULTI", SUPPLIER_A, "Acme",
                LocalDate.of(2026, 6, 1), Payment.Method.BANK_TRANSFER, "AUD",
                List.of(
                    new Payment.SupplierAllocationLine(INVOICE_1, PO_1, new BigDecimal("100.00"), "paid"),
                    new Payment.SupplierAllocationLine(INVOICE_2, PO_2, new BigDecimal("50.00"), "partially_paid")
                )
            );
            List<DomainEvent> events = p.pullPendingEvents();
            assertThat(events).hasSize(2);
            assertThat(events).allMatch(e -> e instanceof SupplierPaymentMade);
            assertThat(((SupplierPaymentMade) events.get(0)).invoiceStatusAfter()).isEqualTo("paid");
            assertThat(((SupplierPaymentMade) events.get(1)).invoiceStatusAfter()).isEqualTo("partially_paid");
        }

        @Test void total_amount_sums_allocations() {
            Payment p = Payment.recordMultiSupplierPayment(
                "PMT-MULTI", SUPPLIER_A, "Acme",
                null, Payment.Method.BANK_TRANSFER, "AUD",
                List.of(
                    new Payment.SupplierAllocationLine(INVOICE_1, PO_1, new BigDecimal("100.00"), "paid"),
                    new Payment.SupplierAllocationLine(INVOICE_2, PO_2, new BigDecimal("50.00"), "partially_paid")
                )
            );
            assertThat(p.amount()).isEqualByComparingTo(new BigDecimal("150.00"));
        }

        @Test void event_carries_payment_total_and_per_allocation_amount() {
            Payment p = Payment.recordMultiSupplierPayment(
                "PMT-MULTI", SUPPLIER_A, "Acme",
                null, Payment.Method.BANK_TRANSFER, "AUD",
                List.of(
                    new Payment.SupplierAllocationLine(INVOICE_1, PO_1, new BigDecimal("100.00"), "paid"),
                    new Payment.SupplierAllocationLine(INVOICE_2, PO_2, new BigDecimal("50.00"), "partially_paid")
                )
            );
            List<DomainEvent> events = p.pullPendingEvents();
            SupplierPaymentMade first = (SupplierPaymentMade) events.get(0);
            // The "amount" on the event reflects payment-level total.
            assertThat(first.amount()).isEqualByComparingTo(new BigDecimal("150.00"));
            // The "allocatedAmount" reflects this specific invoice.
            assertThat(first.allocatedAmount()).isEqualByComparingTo(new BigDecimal("100.00"));
        }

        @Test void rejects_empty_allocation_list() {
            assertThatThrownBy(() -> Payment.recordMultiSupplierPayment(
                "PMT", SUPPLIER_A, "A", null, Payment.Method.CASH, "AUD", List.of()
            )).isInstanceOf(IllegalArgumentException.class);
        }

        @Test void rejects_allocation_with_zero_amount() {
            assertThatThrownBy(() -> Payment.recordMultiSupplierPayment(
                "PMT", SUPPLIER_A, "A", null, Payment.Method.CASH, "AUD",
                List.of(new Payment.SupplierAllocationLine(INVOICE_1, PO_1, BigDecimal.ZERO, "paid"))
            )).isInstanceOf(IllegalArgumentException.class);
        }

        @Test void rejects_null_supplier_id() {
            assertThatThrownBy(() -> Payment.recordMultiSupplierPayment(
                "PMT", null, "A", null, Payment.Method.CASH, "AUD",
                List.of(new Payment.SupplierAllocationLine(INVOICE_1, PO_1, BigDecimal.TEN, "paid"))
            )).isInstanceOf(NullPointerException.class);
        }

        @Test void rejects_allocation_with_null_invoice_id() {
            assertThatThrownBy(() -> Payment.recordMultiSupplierPayment(
                "PMT", SUPPLIER_A, "A", null, Payment.Method.CASH, "AUD",
                List.of(new Payment.SupplierAllocationLine(null, PO_1, BigDecimal.TEN, "paid"))
            )).isInstanceOf(NullPointerException.class);
        }

        @Test void rejects_allocation_with_null_purchase_order_id() {
            assertThatThrownBy(() -> Payment.recordMultiSupplierPayment(
                "PMT", SUPPLIER_A, "A", null, Payment.Method.CASH, "AUD",
                List.of(new Payment.SupplierAllocationLine(INVOICE_1, null, BigDecimal.TEN, "paid"))
            )).isInstanceOf(NullPointerException.class);
        }

        @Test void produces_one_payment_with_n_allocation_rows() {
            Payment p = Payment.recordMultiSupplierPayment(
                "PMT-MULTI", SUPPLIER_A, "Acme",
                null, Payment.Method.BANK_TRANSFER, "AUD",
                List.of(
                    new Payment.SupplierAllocationLine(INVOICE_1, PO_1, new BigDecimal("100"), "paid"),
                    new Payment.SupplierAllocationLine(INVOICE_2, PO_2, new BigDecimal("50"), "paid")
                )
            );
            assertThat(p.allocations()).hasSize(2);
        }
    }

    @Nested
    class CustomerPayment {
        @Test void single_emits_CustomerPaymentReceived() {
            Payment p = Payment.recordCustomerPayment(
                "PMT-C-001", CUSTOMER_A, "BigCorp",
                null, Payment.Method.BANK_TRANSFER, "AUD", new BigDecimal("500.00"),
                INVOICE_1, SO_1, "paid"
            );
            CustomerPaymentReceived e = (CustomerPaymentReceived) p.pullPendingEvents().get(0);
            assertThat(e.salesOrderHeaderId()).isEqualTo(SO_1);
            assertThat(e.customerInvoiceHeaderId()).isEqualTo(INVOICE_1);
            assertThat(e.invoiceStatusAfter()).isEqualTo("paid");
        }

        @Test void multi_emits_one_event_per_invoice() {
            Payment p = Payment.recordMultiCustomerPayment(
                "PMT-MULTI-C", CUSTOMER_A, "BigCorp",
                null, Payment.Method.BANK_TRANSFER, "AUD",
                List.of(
                    new Payment.CustomerAllocationLine(INVOICE_1, SO_1, new BigDecimal("100"), "paid"),
                    new Payment.CustomerAllocationLine(INVOICE_2, SO_1, new BigDecimal("50"), "partially_paid")
                )
            );
            assertThat(p.pullPendingEvents()).hasSize(2);
        }

        @Test void rejects_null_customer_id() {
            assertThatThrownBy(() -> Payment.recordCustomerPayment(
                "PMT", null, "A", null, Payment.Method.CASH, "AUD", BigDecimal.TEN,
                INVOICE_1, SO_1, "paid"
            )).isInstanceOf(NullPointerException.class);
        }

        @Test void rejects_null_customer_invoice_id() {
            assertThatThrownBy(() -> Payment.recordCustomerPayment(
                "PMT", CUSTOMER_A, "A", null, Payment.Method.CASH, "AUD", BigDecimal.TEN,
                null, SO_1, "paid"
            )).isInstanceOf(NullPointerException.class);
        }

        @Test void rejects_null_sales_order_id() {
            assertThatThrownBy(() -> Payment.recordCustomerPayment(
                "PMT", CUSTOMER_A, "A", null, Payment.Method.CASH, "AUD", BigDecimal.TEN,
                INVOICE_1, null, "paid"
            )).isInstanceOf(NullPointerException.class);
        }

        @Test void multi_rejects_null_customer_id() {
            assertThatThrownBy(() -> Payment.recordMultiCustomerPayment(
                "PMT-MULTI-C", null, "A", null, Payment.Method.BANK_TRANSFER, "AUD",
                List.of(new Payment.CustomerAllocationLine(INVOICE_1, SO_1, BigDecimal.TEN, "paid"))
            )).isInstanceOf(NullPointerException.class);
        }

        @Test void multi_rejects_allocation_with_null_invoice_id() {
            assertThatThrownBy(() -> Payment.recordMultiCustomerPayment(
                "PMT-MULTI-C", CUSTOMER_A, "A", null, Payment.Method.BANK_TRANSFER, "AUD",
                List.of(new Payment.CustomerAllocationLine(null, SO_1, BigDecimal.TEN, "paid"))
            )).isInstanceOf(NullPointerException.class);
        }

        @Test void multi_rejects_allocation_with_null_sales_order_id() {
            assertThatThrownBy(() -> Payment.recordMultiCustomerPayment(
                "PMT-MULTI-C", CUSTOMER_A, "A", null, Payment.Method.BANK_TRANSFER, "AUD",
                List.of(new Payment.CustomerAllocationLine(INVOICE_1, null, BigDecimal.TEN, "paid"))
            )).isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    class Direction {
        @Test void supplier_payment_is_outgoing() {
            Payment p = Payment.recordSupplierPayment(
                "PMT", SUPPLIER_A, "A", null, Payment.Method.CASH, "AUD", BigDecimal.TEN,
                INVOICE_1, PO_1, "paid"
            );
            assertThat(p.paymentDirection()).isEqualTo(Payment.Direction.OUTGOING);
            assertThat(p.paymentType()).isEqualTo(Payment.Type.SUPPLIER_PAYMENT);
        }

        @Test void customer_payment_is_incoming() {
            Payment p = Payment.recordCustomerPayment(
                "PMT", CUSTOMER_A, "A", null, Payment.Method.CASH, "AUD", BigDecimal.TEN,
                INVOICE_1, SO_1, "paid"
            );
            assertThat(p.paymentDirection()).isEqualTo(Payment.Direction.INCOMING);
            assertThat(p.paymentType()).isEqualTo(Payment.Type.CUSTOMER_PAYMENT);
        }
    }
}
