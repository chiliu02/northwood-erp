package com.northwood.finance.domain;

import com.northwood.finance.domain.events.CustomerPaymentReceived;
import com.northwood.finance.domain.events.SupplierPaymentMade;
import com.northwood.shared.domain.DomainEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Aggregate root for a payment: header + allocations. Phase 5a supports
 * outgoing supplier payments only — incoming customer payments arrive in
 * phase 5c.
 *
 * <p>Money invariants are enforced at the schema level (the
 * {@code maintain_allocation_totals} trigger keeps {@code amount_allocated}
 * and the matching invoice's {@code paid_amount} consistent). The
 * application code only inserts rows; the trigger does the bookkeeping.
 *
 * <p>The aggregate emits {@link SupplierPaymentMade} for cross-context
 * consumption — purchasing's P2P saga uses it to advance the PO to closed
 * once the invoice is fully paid.
 */
public final class Payment {

    /** Per-invoice allocation input for {@link #recordMultiSupplierPayment}. */
    public record SupplierAllocationLine(
        UUID supplierInvoiceHeaderId,
        UUID purchaseOrderHeaderId,
        BigDecimal amount,
        String invoiceStatusAfter
    ) {}

    /** Per-invoice allocation input for {@link #recordMultiCustomerPayment}. */
    public record CustomerAllocationLine(
        UUID customerInvoiceHeaderId,
        UUID salesOrderHeaderId,
        BigDecimal amount,
        String invoiceStatusAfter
    ) {}

    /**
     * Wire-format aggregate-type stamped onto {@code finance.outbox_message.aggregate_type}
     * for events this aggregate emits.
     */
    public static final String AGGREGATE_TYPE = FinanceAggregateTypes.PAYMENT;

    /** Status — wire-format string stored in finance.payment.status. */
    public static final String POSTED = "posted";


    private final PaymentId id;
    private final String paymentNumber;
    private final String paymentDirection;
    private final String paymentType;
    private final UUID customerId;
    private final UUID supplierId;
    private final String partyName;
    private final LocalDate paymentDate;
    private final String paymentMethod;
    private final String currencyCode;
    private final BigDecimal amount;
    private final String status;
    private final List<PaymentAllocation> allocations;
    private final long version;
    private final List<DomainEvent> pendingEvents = new ArrayList<>();

    /**
     * Factory: post an outgoing supplier payment. The application service
     * supplies the invoice's status-after-this-payment ({@code paid} or
     * {@code partially_paid}) and the originating PO id so the emitted
     * event can route to purchasing's saga without a cross-schema lookup.
     */
    public static Payment recordSupplierPayment(
        String paymentNumber,
        UUID supplierId,
        String supplierName,
        LocalDate paymentDate,
        String paymentMethod,
        String currencyCode,
        BigDecimal amount,
        UUID supplierInvoiceHeaderId,
        UUID purchaseOrderHeaderId,
        String invoiceStatusAfter
    ) {
        Objects.requireNonNull(supplierId, "supplierId");
        Objects.requireNonNull(supplierInvoiceHeaderId, "supplierInvoiceHeaderId");
        Objects.requireNonNull(purchaseOrderHeaderId, "purchaseOrderHeaderId");
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be > 0");
        }

        PaymentId id = PaymentId.newId();
        PaymentAllocation allocation = new PaymentAllocation(
            UUID.randomUUID(),
            null,
            supplierInvoiceHeaderId,
            amount,
            "posted"
        );
        Payment p = new Payment(
            id, paymentNumber,
            "outgoing", "supplier_payment",
            null, supplierId, supplierName,
            paymentDate == null ? LocalDate.now() : paymentDate,
            paymentMethod,
            currencyCode == null ? "AUD" : currencyCode,
            amount,
            "posted",
            List.of(allocation),
            0L
        );

        p.pendingEvents.add(new SupplierPaymentMade(
            UUID.randomUUID(),
            id.value(),
            paymentNumber,
            supplierInvoiceHeaderId,
            purchaseOrderHeaderId,
            supplierId,
            supplierName,
            paymentMethod,
            p.currencyCode,
            amount,
            amount,
            invoiceStatusAfter,
            Instant.now()
        ));
        return p;
    }

    /**
     * Factory: post an incoming customer payment. Mirror of
     * {@link #recordSupplierPayment} for the AR direction. The application
     * service supplies the invoice's status-after-this-payment and the
     * originating sales-order id so the emitted event can route to sales'
     * fulfilment saga without a cross-schema lookup.
     */
    public static Payment recordCustomerPayment(
        String paymentNumber,
        UUID customerId,
        String customerName,
        LocalDate paymentDate,
        String paymentMethod,
        String currencyCode,
        BigDecimal amount,
        UUID customerInvoiceHeaderId,
        UUID salesOrderHeaderId,
        String invoiceStatusAfter
    ) {
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(customerInvoiceHeaderId, "customerInvoiceHeaderId");
        Objects.requireNonNull(salesOrderHeaderId, "salesOrderHeaderId");
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be > 0");
        }

        PaymentId id = PaymentId.newId();
        PaymentAllocation allocation = new PaymentAllocation(
            UUID.randomUUID(),
            customerInvoiceHeaderId,
            null,
            amount,
            "posted"
        );
        Payment p = new Payment(
            id, paymentNumber,
            "incoming", "customer_payment",
            customerId, null, customerName,
            paymentDate == null ? LocalDate.now() : paymentDate,
            paymentMethod,
            currencyCode == null ? "AUD" : currencyCode,
            amount,
            "posted",
            List.of(allocation),
            0L
        );

        p.pendingEvents.add(new CustomerPaymentReceived(
            UUID.randomUUID(),
            id.value(),
            paymentNumber,
            customerInvoiceHeaderId,
            salesOrderHeaderId,
            customerId,
            customerName,
            paymentMethod,
            p.currencyCode,
            amount,
            amount,
            invoiceStatusAfter,
            Instant.now()
        ));
        return p;
    }

    /**
     * Factory: post a multi-invoice supplier payment. One physical payment
     * covers several approved supplier invoices from the same supplier.
     * Constraint: all allocations must reference the same supplier and same
     * currency (the application service validates this before calling).
     * Emits one {@link SupplierPaymentMade} per allocation so each P2P saga
     * (one per PO) gets a routed event.
     */
    public static Payment recordMultiSupplierPayment(
        String paymentNumber,
        UUID supplierId,
        String supplierName,
        LocalDate paymentDate,
        String paymentMethod,
        String currencyCode,
        List<SupplierAllocationLine> lines
    ) {
        Objects.requireNonNull(supplierId, "supplierId");
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("at least one allocation line is required");
        }
        BigDecimal total = BigDecimal.ZERO;
        for (SupplierAllocationLine l : lines) {
            Objects.requireNonNull(l.supplierInvoiceHeaderId, "supplierInvoiceHeaderId");
            Objects.requireNonNull(l.purchaseOrderHeaderId, "purchaseOrderHeaderId");
            if (l.amount == null || l.amount.signum() <= 0) {
                throw new IllegalArgumentException("each allocation amount must be > 0");
            }
            total = total.add(l.amount);
        }

        PaymentId id = PaymentId.newId();
        List<PaymentAllocation> allocations = new ArrayList<>();
        for (SupplierAllocationLine l : lines) {
            allocations.add(new PaymentAllocation(
                UUID.randomUUID(),
                null,
                l.supplierInvoiceHeaderId,
                l.amount,
                "posted"
            ));
        }
        Payment p = new Payment(
            id, paymentNumber,
            "outgoing", "supplier_payment",
            null, supplierId, supplierName,
            paymentDate == null ? LocalDate.now() : paymentDate,
            paymentMethod,
            currencyCode == null ? "AUD" : currencyCode,
            total,
            "posted",
            allocations,
            0L
        );

        Instant now = Instant.now();
        for (SupplierAllocationLine l : lines) {
            p.pendingEvents.add(new SupplierPaymentMade(
                UUID.randomUUID(),
                id.value(),
                paymentNumber,
                l.supplierInvoiceHeaderId,
                l.purchaseOrderHeaderId,
                supplierId,
                supplierName,
                paymentMethod,
                p.currencyCode,
                total,           // payment-level total (informational)
                l.amount,        // allocated to THIS invoice
                l.invoiceStatusAfter,
                now
            ));
        }
        return p;
    }

    /**
     * Factory: post a multi-invoice customer payment. Mirror of
     * {@link #recordMultiSupplierPayment} for AR.
     */
    public static Payment recordMultiCustomerPayment(
        String paymentNumber,
        UUID customerId,
        String customerName,
        LocalDate paymentDate,
        String paymentMethod,
        String currencyCode,
        List<CustomerAllocationLine> lines
    ) {
        Objects.requireNonNull(customerId, "customerId");
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("at least one allocation line is required");
        }
        BigDecimal total = BigDecimal.ZERO;
        for (CustomerAllocationLine l : lines) {
            Objects.requireNonNull(l.customerInvoiceHeaderId, "customerInvoiceHeaderId");
            Objects.requireNonNull(l.salesOrderHeaderId, "salesOrderHeaderId");
            if (l.amount == null || l.amount.signum() <= 0) {
                throw new IllegalArgumentException("each allocation amount must be > 0");
            }
            total = total.add(l.amount);
        }

        PaymentId id = PaymentId.newId();
        List<PaymentAllocation> allocations = new ArrayList<>();
        for (CustomerAllocationLine l : lines) {
            allocations.add(new PaymentAllocation(
                UUID.randomUUID(),
                l.customerInvoiceHeaderId,
                null,
                l.amount,
                "posted"
            ));
        }
        Payment p = new Payment(
            id, paymentNumber,
            "incoming", "customer_payment",
            customerId, null, customerName,
            paymentDate == null ? LocalDate.now() : paymentDate,
            paymentMethod,
            currencyCode == null ? "AUD" : currencyCode,
            total,
            "posted",
            allocations,
            0L
        );

        Instant now = Instant.now();
        for (CustomerAllocationLine l : lines) {
            p.pendingEvents.add(new CustomerPaymentReceived(
                UUID.randomUUID(),
                id.value(),
                paymentNumber,
                l.customerInvoiceHeaderId,
                l.salesOrderHeaderId,
                customerId,
                customerName,
                paymentMethod,
                p.currencyCode,
                total,           // payment-level total
                l.amount,        // allocated to THIS invoice
                l.invoiceStatusAfter,
                now
            ));
        }
        return p;
    }

    /** Factory: hydrate from the DB; emits no events. */
    public static Payment reconstitute(
        PaymentId id, String paymentNumber, String paymentDirection, String paymentType,
        UUID customerId, UUID supplierId, String partyName,
        LocalDate paymentDate, String paymentMethod, String currencyCode,
        BigDecimal amount, String status,
        List<PaymentAllocation> allocations, long version
    ) {
        return new Payment(
            id, paymentNumber, paymentDirection, paymentType,
            customerId, supplierId, partyName,
            paymentDate, paymentMethod, currencyCode,
            amount, status,
            new ArrayList<>(allocations), version
        );
    }

    private Payment(
        PaymentId id, String paymentNumber, String paymentDirection, String paymentType,
        UUID customerId, UUID supplierId, String partyName,
        LocalDate paymentDate, String paymentMethod, String currencyCode,
        BigDecimal amount, String status,
        List<PaymentAllocation> allocations, long version
    ) {
        this.id = id;
        this.paymentNumber = paymentNumber;
        this.paymentDirection = paymentDirection;
        this.paymentType = paymentType;
        this.customerId = customerId;
        this.supplierId = supplierId;
        this.partyName = partyName;
        this.paymentDate = paymentDate;
        this.paymentMethod = paymentMethod;
        this.currencyCode = currencyCode;
        this.amount = amount;
        this.status = status;
        this.allocations = allocations;
        this.version = version;
    }

    public List<DomainEvent> pullPendingEvents() {
        List<DomainEvent> out = List.copyOf(pendingEvents);
        pendingEvents.clear();
        return out;
    }

    public PaymentId id()                          { return id; }
    public String paymentNumber()                  { return paymentNumber; }
    public String paymentDirection()               { return paymentDirection; }
    public String paymentType()                    { return paymentType; }
    public UUID customerId()                       { return customerId; }
    public UUID supplierId()                       { return supplierId; }
    public String partyName()                      { return partyName; }
    public LocalDate paymentDate()                 { return paymentDate; }
    public String paymentMethod()                  { return paymentMethod; }
    public String currencyCode()                   { return currencyCode; }
    public BigDecimal amount()                     { return amount; }
    public String status()                         { return status; }
    public List<PaymentAllocation> allocations()   { return List.copyOf(allocations); }
    public long version()                          { return version; }
}
