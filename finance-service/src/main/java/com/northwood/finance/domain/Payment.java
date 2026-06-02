package com.northwood.finance.domain;

import com.northwood.finance.domain.events.CustomerPaymentReceived;
import com.northwood.finance.domain.events.SupplierPaymentMade;
import com.northwood.shared.domain.Assert;
import com.northwood.shared.domain.Currencies;
import com.northwood.shared.domain.DomainEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
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

    /**
     * Prefix for auto-generated payment numbers (parallel to other aggregates'
     * {@code NUMBER_PREFIX}). Operator-recorded payments supply their own
     * number via the command; system-recorded payments (the COD auto-payment)
     * mint one as {@code NUMBER_PREFIX + }random-suffix.
     */
    public static final String NUMBER_PREFIX = "PAY-";

    /** Character count of the random suffix appended to {@link #NUMBER_PREFIX}. */
    public static final int NUMBER_SUFFIX_LENGTH = 8;

    /**
     * Payment method. Mirrors the schema CHECK on
     * {@code finance.payment.payment_method}.
     */
    public enum Method {
        BANK_TRANSFER("bank_transfer"),
        CASH("cash"),
        CARD("card"),
        CHEQUE("cheque");

        private final String dbValue;

        Method(String dbValue) {
            this.dbValue = dbValue;
        }

        public String dbValue() {
            return dbValue;
        }

        public static Method fromDb(String value) {
            for (Method m : values()) {
                if (m.dbValue.equals(value)) return m;
            }
            throw Assert.unknownValue("payment_method", value);
        }
    }

    /**
     * Payment lifecycle status. Mirrors the schema CHECK on
     * {@code finance.payment.status}. Today's Java only ever writes
     * {@code POSTED}; {@code DRAFT} / {@code CANCELLED} / {@code REVERSED}
     * are schema-prep for future workflow paths.
     */
    public enum Status {
        /** Schema-prep — not currently produced by Java. */
        DRAFT("draft"),
        POSTED("posted"),
        /** Schema-prep — not currently produced by Java. */
        CANCELLED("cancelled"),
        /** Schema-prep — not currently produced by Java. */
        REVERSED("reversed");

        private final String dbValue;

        Status(String dbValue) {
            this.dbValue = dbValue;
        }

        public String dbValue() {
            return dbValue;
        }

        public static Status fromDb(String value) {
            for (Status s : values()) {
                if (s.dbValue.equals(value)) return s;
            }
            throw Assert.unknownValue("payment status", value);
        }
    }

    /**
     * Payment direction. No schema CHECK today, but the {@code payment_direction}
     * column is one-of-known-set — every {@code Payment.record*()} factory writes
     * one of these two values.
     */
    public enum Direction {
        INCOMING("incoming"),
        OUTGOING("outgoing");

        private final String dbValue;

        Direction(String dbValue) {
            this.dbValue = dbValue;
        }

        public String dbValue() {
            return dbValue;
        }

        public static Direction fromDb(String value) {
            for (Direction d : values()) {
                if (d.dbValue.equals(value)) return d;
            }
            throw Assert.unknownValue("payment_direction", value);
        }
    }

    /**
     * Payment type. No schema CHECK today, but the {@code payment_type} column
     * is one-of-known-set — outgoing payments are {@code SUPPLIER_PAYMENT},
     * incoming are {@code CUSTOMER_PAYMENT}. Pairs with {@link Direction}.
     */
    public enum Type {
        SUPPLIER_PAYMENT("supplier_payment"),
        CUSTOMER_PAYMENT("customer_payment");

        private final String dbValue;

        Type(String dbValue) {
            this.dbValue = dbValue;
        }

        public String dbValue() {
            return dbValue;
        }

        public static Type fromDb(String value) {
            for (Type t : values()) {
                if (t.dbValue.equals(value)) return t;
            }
            throw Assert.unknownValue("payment_type", value);
        }
    }

    /**
     * Allocation status (on {@link PaymentAllocation} child). Mirrors the
     * schema CHECK on {@code finance.payment_allocation.status}. Today's Java
     * only writes {@code POSTED}; {@code REVERSED} is schema-prep.
     */
    public enum AllocationStatus {
        POSTED("posted"),
        /** Schema-prep — not currently produced by Java. */
        REVERSED("reversed");

        private final String dbValue;

        AllocationStatus(String dbValue) {
            this.dbValue = dbValue;
        }

        public String dbValue() {
            return dbValue;
        }

        public static AllocationStatus fromDb(String value) {
            for (AllocationStatus s : values()) {
                if (s.dbValue.equals(value)) return s;
            }
            throw Assert.unknownValue("payment_allocation status", value);
        }
    }

    private final PaymentId id;
    private final String paymentNumber;
    private final Direction paymentDirection;
    private final Type paymentType;
    private final UUID customerId;
    private final UUID supplierId;
    private final String partyName;
    private final LocalDate paymentDate;
    private final Method paymentMethod;
    private final String currencyCode;
    private final BigDecimal amount;
    private final Status status;
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
        Method paymentMethod,
        String currencyCode,
        BigDecimal amount,
        UUID supplierInvoiceHeaderId,
        UUID purchaseOrderHeaderId,
        String invoiceStatusAfter
    ) {
        Assert.notNull(supplierId, "supplierId");
        Assert.notNull(supplierInvoiceHeaderId, "supplierInvoiceHeaderId");
        Assert.notNull(purchaseOrderHeaderId, "purchaseOrderHeaderId");
        Assert.argument(amount != null && amount.signum() > 0, "amount must be > 0");

        PaymentId id = PaymentId.newId();
        PaymentAllocation allocation = new PaymentAllocation(
            UUID.randomUUID(),
            null,
            supplierInvoiceHeaderId,
            amount,
            Payment.AllocationStatus.POSTED
        );
        Payment p = new Payment(
            id, paymentNumber,
            Direction.OUTGOING, Type.SUPPLIER_PAYMENT,
            null, supplierId, supplierName,
            paymentDate == null ? LocalDate.now() : paymentDate,
            paymentMethod,
            Currencies.orBase(currencyCode),
            amount,
            Status.POSTED,
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
            paymentMethod.dbValue(),
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
        Method paymentMethod,
        String currencyCode,
        BigDecimal amount,
        UUID customerInvoiceHeaderId,
        UUID salesOrderHeaderId,
        String invoiceStatusAfter
    ) {
        Assert.notNull(customerId, "customerId");
        Assert.notNull(customerInvoiceHeaderId, "customerInvoiceHeaderId");
        Assert.notNull(salesOrderHeaderId, "salesOrderHeaderId");
        Assert.argument(amount != null && amount.signum() > 0, "amount must be > 0");

        PaymentId id = PaymentId.newId();
        PaymentAllocation allocation = new PaymentAllocation(
            UUID.randomUUID(),
            customerInvoiceHeaderId,
            null,
            amount,
            Payment.AllocationStatus.POSTED
        );
        Payment p = new Payment(
            id, paymentNumber,
            Direction.INCOMING, Type.CUSTOMER_PAYMENT,
            customerId, null, customerName,
            paymentDate == null ? LocalDate.now() : paymentDate,
            paymentMethod,
            Currencies.orBase(currencyCode),
            amount,
            Status.POSTED,
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
            paymentMethod.dbValue(),
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
        Method paymentMethod,
        String currencyCode,
        List<SupplierAllocationLine> lines
    ) {
        Assert.notNull(supplierId, "supplierId");
        Assert.notEmpty(lines, "at least one allocation line is required");
        BigDecimal total = BigDecimal.ZERO;
        for (SupplierAllocationLine l : lines) {
            Assert.notNull(l.supplierInvoiceHeaderId, "supplierInvoiceHeaderId");
            Assert.notNull(l.purchaseOrderHeaderId, "purchaseOrderHeaderId");
            Assert.argument(l.amount != null && l.amount.signum() > 0, "each allocation amount must be > 0");
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
                Payment.AllocationStatus.POSTED
            ));
        }
        Payment p = new Payment(
            id, paymentNumber,
            Direction.OUTGOING, Type.SUPPLIER_PAYMENT,
            null, supplierId, supplierName,
            paymentDate == null ? LocalDate.now() : paymentDate,
            paymentMethod,
            Currencies.orBase(currencyCode),
            total,
            Status.POSTED,
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
                paymentMethod.dbValue(),
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
        Method paymentMethod,
        String currencyCode,
        List<CustomerAllocationLine> lines
    ) {
        Assert.notNull(customerId, "customerId");
        Assert.notEmpty(lines, "at least one allocation line is required");
        BigDecimal total = BigDecimal.ZERO;
        for (CustomerAllocationLine l : lines) {
            Assert.notNull(l.customerInvoiceHeaderId, "customerInvoiceHeaderId");
            Assert.notNull(l.salesOrderHeaderId, "salesOrderHeaderId");
            Assert.argument(l.amount != null && l.amount.signum() > 0, "each allocation amount must be > 0");
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
                Payment.AllocationStatus.POSTED
            ));
        }
        Payment p = new Payment(
            id, paymentNumber,
            Direction.INCOMING, Type.CUSTOMER_PAYMENT,
            customerId, null, customerName,
            paymentDate == null ? LocalDate.now() : paymentDate,
            paymentMethod,
            Currencies.orBase(currencyCode),
            total,
            Status.POSTED,
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
                paymentMethod.dbValue(),
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
        PaymentId id, String paymentNumber, Direction paymentDirection, Type paymentType,
        UUID customerId, UUID supplierId, String partyName,
        LocalDate paymentDate, Method paymentMethod, String currencyCode,
        BigDecimal amount, Status status,
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
        PaymentId id, String paymentNumber, Direction paymentDirection, Type paymentType,
        UUID customerId, UUID supplierId, String partyName,
        LocalDate paymentDate, Method paymentMethod, String currencyCode,
        BigDecimal amount, Status status,
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
    public Direction paymentDirection()            { return paymentDirection; }
    public Type paymentType()                      { return paymentType; }
    public UUID customerId()                       { return customerId; }
    public UUID supplierId()                       { return supplierId; }
    public String partyName()                      { return partyName; }
    public LocalDate paymentDate()                 { return paymentDate; }
    public Method paymentMethod()                  { return paymentMethod; }
    public String currencyCode()                   { return currencyCode; }
    public BigDecimal amount()                     { return amount; }
    public Status status()                         { return status; }
    public List<PaymentAllocation> allocations()   { return List.copyOf(allocations); }
    public long version()                          { return version; }
}
