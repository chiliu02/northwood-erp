package com.northwood.finance.application;

import com.northwood.finance.application.dto.PaymentView;
import com.northwood.finance.application.dto.RecordCustomerPaymentCommand;
import com.northwood.finance.application.dto.RecordCustomerPaymentMultiCommand;
import com.northwood.finance.application.dto.RecordSupplierPaymentCommand;
import com.northwood.finance.application.dto.RecordSupplierPaymentMultiCommand;
import com.northwood.finance.domain.CustomerInvoice;
import com.northwood.finance.domain.CustomerInvoiceRepository;
import com.northwood.finance.domain.CustomerInvoiceRepository.PaymentSnapshot;
import com.northwood.finance.domain.Payment;
import com.northwood.finance.domain.Payment.CustomerAllocationLine;
import com.northwood.finance.domain.Payment.SupplierAllocationLine;
import com.northwood.finance.domain.PaymentId;
import com.northwood.finance.domain.PaymentRepository;
import com.northwood.finance.domain.SupplierInvoice;
import com.northwood.finance.domain.SupplierInvoiceRepository;
import com.northwood.shared.domain.Assert;
import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for payments. Phase 5a supports one use case:
 * {@link #recordSupplierPayment} — posts an outgoing payment, allocates the
 * full amount against a single approved supplier invoice, and emits
 * {@code SupplierPaymentMade}.
 *
 * <p>The {@code maintain_allocation_totals} trigger does the bookkeeping
 * (bumps {@code payment.amount_allocated}, the invoice's {@code paid_amount},
 * and flips invoice status to {@code paid} or {@code partially_paid}). The
 * service reads the invoice back post-insert to learn the resulting status
 * and stamps it onto the emitted event so purchasing's saga can decide
 * whether to close the PO.
 *
 * <p>Phase 5a limitations:
 * <ul>
 *   <li>Single-invoice allocation only — multi-invoice settlement is a
 *       future slice (the schema supports it; the API doesn't yet).</li>
 *   <li>No GL journal posting — the AP-debit / cash-credit pair lands when
 *       {@code CurrencyConverter} + journal-entry persistence are wired.</li>
 *   <li>Customer-payment direction is unsupported.</li>
 * </ul>
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository payments;
    private final SupplierInvoiceRepository supplierInvoices;
    private final CustomerInvoiceRepository customerInvoices;
    private final JournalEntryService journalEntries;

    public PaymentService(
        PaymentRepository payments,
        SupplierInvoiceRepository supplierInvoices,
        CustomerInvoiceRepository customerInvoices,
        JournalEntryService journalEntries
    ) {
        this.payments = payments;
        this.supplierInvoices = supplierInvoices;
        this.customerInvoices = customerInvoices;
        this.journalEntries = journalEntries;
    }

    @Transactional(readOnly = true)
    public Optional<PaymentView> findById(UUID paymentId) {
        return payments.findById(PaymentId.of(paymentId)).map(PaymentView::from);
    }

    @Transactional(readOnly = true)
    public List<PaymentView> findAll() {
        return payments.findAll().stream().map(PaymentView::from).toList();
    }

    @Transactional
    public PaymentView recordSupplierPayment(RecordSupplierPaymentCommand command) {
        SupplierInvoiceRepository.PaymentSnapshot inv = lookupSupplierInvoice(command.supplierInvoiceHeaderId());
        Assert.state(inv.status() == SupplierInvoice.Status.APPROVED || inv.status() == SupplierInvoice.Status.PARTIALLY_PAID, "Cannot pay supplier invoice " + command.supplierInvoiceHeaderId()
                    + " in status=" + inv.status().dbValue() + " (must be approved or partially_paid)");
        BigDecimal outstandingBefore = inv.totalAmount().subtract(inv.paidAmount());
        Assert.argument(command.amount().compareTo(outstandingBefore) <= 0, "Payment amount " + command.amount() + " exceeds outstanding " + outstandingBefore
                    + " on invoice " + command.supplierInvoiceHeaderId());

        BigDecimal paidAfter = inv.paidAmount().add(command.amount());
        String invoiceStatusAfter = paidAfter.compareTo(inv.totalAmount()) >= 0
            ? SupplierInvoice.Status.PAID.dbValue() : SupplierInvoice.Status.PARTIALLY_PAID.dbValue();

        Payment payment = Payment.recordSupplierPayment(
            command.paymentNumber(),
            inv.supplierId(),
            inv.supplierName(),
            command.paymentDate(),
            Payment.Method.fromDb(command.paymentMethod()),
            inv.currencyCode(),
            command.amount(),
            command.supplierInvoiceHeaderId(),
            inv.purchaseOrderHeaderId(),
            invoiceStatusAfter
        );
        payments.save(payment);

        // Phase 5b: GL pair (Dr AP, Cr Bank) in the same txn.
        journalEntries.postSupplierPayment(
            payment.id().value(),
            inv.supplierName(),
            payment.paymentNumber(),
            command.amount(),
            inv.currencyCode(),
            payment.paymentDate()
        );

        log.info(
            "posted supplier payment {} amount={} for invoice={} → invoice status={} (outstanding before={}, after={})",
            payment.paymentNumber(), command.amount(),
            command.supplierInvoiceHeaderId(),
            invoiceStatusAfter,
            outstandingBefore,
            outstandingBefore.subtract(command.amount())
        );
        return PaymentView.from(payment);
    }

    @Transactional
    public PaymentView recordCustomerPayment(RecordCustomerPaymentCommand command) {
        PaymentSnapshot inv = lookupCustomerInvoice(command.customerInvoiceHeaderId());
        Assert.state(inv.status() == CustomerInvoice.Status.POSTED || inv.status() == CustomerInvoice.Status.PARTIALLY_PAID, "Cannot pay customer invoice " + command.customerInvoiceHeaderId()
                    + " in status=" + inv.status().dbValue() + " (must be posted or partially_paid)");
        BigDecimal outstandingBefore = inv.totalAmount().subtract(inv.paidAmount());
        Assert.argument(command.amount().compareTo(outstandingBefore) <= 0, "Payment amount " + command.amount() + " exceeds outstanding " + outstandingBefore
                    + " on invoice " + command.customerInvoiceHeaderId());

        BigDecimal paidAfter = inv.paidAmount().add(command.amount());
        String invoiceStatusAfter = paidAfter.compareTo(inv.totalAmount()) >= 0
            ? CustomerInvoice.Status.PAID.dbValue() : CustomerInvoice.Status.PARTIALLY_PAID.dbValue();

        Payment payment = Payment.recordCustomerPayment(
            command.paymentNumber(),
            inv.customerId(),
            inv.customerName(),
            command.paymentDate(),
            Payment.Method.fromDb(command.paymentMethod()),
            inv.currencyCode(),
            command.amount(),
            command.customerInvoiceHeaderId(),
            inv.salesOrderHeaderId(),
            invoiceStatusAfter
        );
        payments.save(payment);

        // Phase 5b: GL pair in the same txn. Routes the credit side by
        // invoice_type — commercial → Cr AR (existing); prepayment →
        // Cr 2110 Customer Deposits.
        journalEntries.postCustomerPayment(
            payment.id().value(),
            inv.customerName(),
            payment.paymentNumber(),
            command.amount(),
            inv.currencyCode(),
            payment.paymentDate(),
            inv.invoiceType()
        );

        log.info(
            "posted customer payment {} amount={} for invoice={} → invoice status={} (outstanding before={}, after={})",
            payment.paymentNumber(), command.amount(),
            command.customerInvoiceHeaderId(),
            invoiceStatusAfter,
            outstandingBefore,
            outstandingBefore.subtract(command.amount())
        );
        return PaymentView.from(payment);
    }

    /**
     * Auto-records the full customer payment for a cash-on-delivery
     * order at shipment. Called by {@code SalesOrderShippedHandler} immediately
     * after it creates the COD shipment invoice (same transaction): COD settles
     * at the goods-delivered moment, so finance recognises the cash receipt
     * (Dr Cash / Cr AR, netting the Dr AR / Cr Revenue the invoice just posted)
     * in the same flow rather than waiting for an operator to record it. Always
     * settles the just-posted invoice in full. The payment number is
     * system-minted ({@link Payment#NUMBER_PREFIX}); the tender is
     * {@link Payment.Method#CASH} (cash collected on delivery — the
     * cash-on-delivery <i>term</i> lives on the order's payment_terms, not on
     * the payment method).
     */
    @Transactional
    public PaymentView recordCashOnDeliveryPayment(UUID customerInvoiceHeaderId, LocalDate paymentDate) {
        PaymentSnapshot inv = lookupCustomerInvoice(customerInvoiceHeaderId);
        Assert.state(inv.status() == CustomerInvoice.Status.POSTED,
            "COD auto-payment expects a freshly posted invoice " + customerInvoiceHeaderId
                + " (status=" + inv.status().dbValue() + ")");
        BigDecimal outstanding = inv.totalAmount().subtract(inv.paidAmount());
        Assert.argument(outstanding.signum() > 0,
            "COD invoice " + customerInvoiceHeaderId + " has nothing outstanding to settle");

        String paymentNumber = Payment.NUMBER_PREFIX
            + UUID.randomUUID().toString().substring(0, Payment.NUMBER_SUFFIX_LENGTH).toUpperCase();
        // Tender is CASH — money collected on delivery. The cash-on-delivery
        // *term* lives on the order's payment_terms, not on the payment method
        // (Method is tender type: bank_transfer / cash / card / cheque).
        Payment payment = Payment.recordCustomerPayment(
            paymentNumber,
            inv.customerId(),
            inv.customerName(),
            paymentDate,
            Payment.Method.CASH,
            inv.currencyCode(),
            outstanding,
            customerInvoiceHeaderId,
            inv.salesOrderHeaderId(),
            CustomerInvoice.Status.PAID.dbValue()
        );
        payments.save(payment);

        // GL: Dr Cash / Cr AR for a commercial invoice (routes the credit
        // side by invoice_type — COD invoices are commercial). Net of the
        // invoice's own Dr AR / Cr Revenue, the order books as Dr Cash / Cr Revenue.
        journalEntries.postCustomerPayment(
            payment.id().value(),
            inv.customerName(),
            payment.paymentNumber(),
            outstanding,
            inv.currencyCode(),
            paymentDate,
            inv.invoiceType()
        );

        log.info("auto-recorded COD payment {} amount={} for invoice={} (sales_order={})",
            payment.paymentNumber(), outstanding, customerInvoiceHeaderId, inv.salesOrderHeaderId());
        return PaymentView.from(payment);
    }

    /**
     * Multi-invoice supplier payment: one physical payment settles several
     * approved supplier invoices from the same supplier. Validates each
     * invoice's status, currency, and supplier match; computes per-invoice
     * status-after-allocation; emits one {@code SupplierPaymentMade} per
     * allocation so each P2P saga gets a routed event.
     */
    @Transactional
    public PaymentView recordSupplierPaymentMulti(RecordSupplierPaymentMultiCommand command) {
        Assert.notEmpty(command.invoices(), "at least one invoice allocation is required");
        UUID expectedSupplierId = null;
        String expectedSupplierName = null;
        String expectedCurrency = null;
        BigDecimal totalAmount = BigDecimal.ZERO;
        List<SupplierAllocationLine> lines = new ArrayList<>();
        for (RecordSupplierPaymentMultiCommand.InvoiceLine il : command.invoices()) {
            SupplierInvoiceRepository.PaymentSnapshot inv = lookupSupplierInvoice(il.supplierInvoiceHeaderId());
            Assert.state(inv.status() == SupplierInvoice.Status.APPROVED || inv.status() == SupplierInvoice.Status.PARTIALLY_PAID, "Cannot pay supplier invoice " + il.supplierInvoiceHeaderId()
                        + " in status=" + inv.status().dbValue() + " (must be approved or partially_paid)");
            if (expectedSupplierId == null) {
                expectedSupplierId = inv.supplierId();
                expectedSupplierName = inv.supplierName();
                expectedCurrency = inv.currencyCode();
            } else if (!expectedSupplierId.equals(inv.supplierId())) {
                throw new IllegalArgumentException(
                    "All invoices in a multi-allocation must be from the same supplier "
                        + "(found " + inv.supplierId() + " vs expected " + expectedSupplierId + ")"
                );
            } else if (!expectedCurrency.equals(inv.currencyCode())) {
                throw new IllegalArgumentException(
                    "All invoices in a multi-allocation must share the same currency "
                        + "(found " + inv.currencyCode() + " vs expected " + expectedCurrency + ")"
                );
            }
            BigDecimal outstandingBefore = inv.totalAmount().subtract(inv.paidAmount());
            Assert.argument(il.amount().compareTo(outstandingBefore) <= 0, "Allocation " + il.amount() + " exceeds outstanding " + outstandingBefore
                        + " on invoice " + il.supplierInvoiceHeaderId());
            BigDecimal paidAfter = inv.paidAmount().add(il.amount());
            String invoiceStatusAfter = paidAfter.compareTo(inv.totalAmount()) >= 0
                ? SupplierInvoice.Status.PAID.dbValue() : SupplierInvoice.Status.PARTIALLY_PAID.dbValue();
            totalAmount = totalAmount.add(il.amount());
            lines.add(new SupplierAllocationLine(
                il.supplierInvoiceHeaderId(),
                inv.purchaseOrderHeaderId(),
                il.amount(),
                invoiceStatusAfter
            ));
        }

        Payment payment = Payment.recordMultiSupplierPayment(
            command.paymentNumber(),
            expectedSupplierId,
            expectedSupplierName,
            command.paymentDate(),
            Payment.Method.fromDb(command.paymentMethod()),
            expectedCurrency,
            lines
        );
        payments.save(payment);

        // Single combined GL pair (Dr AP / Cr Bank) for the total — one
        // physical movement of cash, regardless of how many invoices it
        // settles. Per-invoice GL detail lives in the AP sub-ledger
        // (allocations on the payment), not the GL.
        journalEntries.postSupplierPayment(
            payment.id().value(),
            expectedSupplierName,
            payment.paymentNumber(),
            totalAmount,
            expectedCurrency,
            payment.paymentDate()
        );

        log.info(
            "posted multi-invoice supplier payment {} total={} across {} invoice(s) supplier={}",
            payment.paymentNumber(), totalAmount, lines.size(), expectedSupplierName
        );
        return PaymentView.from(payment);
    }

    /**
     * Multi-invoice customer payment: mirror of
     * {@link #recordSupplierPaymentMulti} for AR.
     */
    @Transactional
    public PaymentView recordCustomerPaymentMulti(RecordCustomerPaymentMultiCommand command) {
        Assert.notEmpty(command.invoices(), "at least one invoice allocation is required");
        UUID expectedCustomerId = null;
        String expectedCustomerName = null;
        String expectedCurrency = null;
        CustomerInvoice.InvoiceType expectedInvoiceType = null;
        BigDecimal totalAmount = BigDecimal.ZERO;
        List<CustomerAllocationLine> lines = new ArrayList<>();
        for (RecordCustomerPaymentMultiCommand.InvoiceLine il : command.invoices()) {
            PaymentSnapshot inv = lookupCustomerInvoice(il.customerInvoiceHeaderId());
            Assert.state(inv.status() == CustomerInvoice.Status.POSTED || inv.status() == CustomerInvoice.Status.PARTIALLY_PAID, "Cannot pay customer invoice " + il.customerInvoiceHeaderId()
                        + " in status=" + inv.status().dbValue() + " (must be posted or partially_paid)");
            if (expectedCustomerId == null) {
                expectedCustomerId = inv.customerId();
                expectedCustomerName = inv.customerName();
                expectedCurrency = inv.currencyCode();
                expectedInvoiceType = inv.invoiceType();
            } else if (!expectedCustomerId.equals(inv.customerId())) {
                throw new IllegalArgumentException(
                    "All invoices in a multi-allocation must be from the same customer "
                        + "(found " + inv.customerId() + " vs expected " + expectedCustomerId + ")"
                );
            } else if (!expectedCurrency.equals(inv.currencyCode())) {
                throw new IllegalArgumentException(
                    "All invoices in a multi-allocation must share the same currency "
                        + "(found " + inv.currencyCode() + " vs expected " + expectedCurrency + ")"
                );
            } else if (expectedInvoiceType != inv.invoiceType()) {
                // One physical receipt is one balanced journal-entry pair;
                // mixing commercial + prepayment invoices would force two
                // different credit accounts in the same journal. Reject —
                // caller must split into two payments.
                throw new IllegalArgumentException(
                    "All invoices in a multi-allocation must share the same invoice_type "
                        + "(found " + inv.invoiceType().dbValue() + " vs expected " + expectedInvoiceType.dbValue() + ")"
                );
            }
            BigDecimal outstandingBefore = inv.totalAmount().subtract(inv.paidAmount());
            Assert.argument(il.amount().compareTo(outstandingBefore) <= 0, "Allocation " + il.amount() + " exceeds outstanding " + outstandingBefore
                        + " on invoice " + il.customerInvoiceHeaderId());
            BigDecimal paidAfter = inv.paidAmount().add(il.amount());
            String invoiceStatusAfter = paidAfter.compareTo(inv.totalAmount()) >= 0
                ? CustomerInvoice.Status.PAID.dbValue() : CustomerInvoice.Status.PARTIALLY_PAID.dbValue();
            totalAmount = totalAmount.add(il.amount());
            lines.add(new CustomerAllocationLine(
                il.customerInvoiceHeaderId(),
                inv.salesOrderHeaderId(),
                il.amount(),
                invoiceStatusAfter
            ));
        }

        Payment payment = Payment.recordMultiCustomerPayment(
            command.paymentNumber(),
            expectedCustomerId,
            expectedCustomerName,
            command.paymentDate(),
            Payment.Method.fromDb(command.paymentMethod()),
            expectedCurrency,
            lines
        );
        payments.save(payment);

        journalEntries.postCustomerPayment(
            payment.id().value(),
            expectedCustomerName,
            payment.paymentNumber(),
            totalAmount,
            expectedCurrency,
            payment.paymentDate(),
            expectedInvoiceType
        );

        log.info(
            "posted multi-invoice customer payment {} total={} across {} invoice(s) customer={}",
            payment.paymentNumber(), totalAmount, lines.size(), expectedCustomerName
        );
        return PaymentView.from(payment);
    }

    private PaymentSnapshot lookupCustomerInvoice(UUID customerInvoiceHeaderId) {
        return customerInvoices.findPaymentSnapshot(customerInvoiceHeaderId)
            .orElseThrow(() -> new IllegalArgumentException(
                "No customer invoice with id=" + customerInvoiceHeaderId));
    }

    private SupplierInvoiceRepository.PaymentSnapshot lookupSupplierInvoice(UUID supplierInvoiceHeaderId) {
        return supplierInvoices.findPaymentSnapshot(supplierInvoiceHeaderId)
            .orElseThrow(() -> new IllegalArgumentException(
                "No supplier invoice with id=" + supplierInvoiceHeaderId));
    }

}
