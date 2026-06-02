package com.northwood.finance.domain;

import com.northwood.finance.domain.events.CustomerInvoiceCreated;
import com.northwood.shared.domain.Assert;
import com.northwood.shared.domain.Currencies;
import com.northwood.shared.domain.DomainEvent;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Aggregate root for a customer invoice: header + lines. Phase 5c supports
 * one creation path: {@link #create}, auto-invoked from
 * {@code sales.SalesOrderShipped}. Goes straight to status {@code 'posted'}.
 *
 * <p>The {@code maintain_allocation_totals} DB trigger will keep
 * {@code paid_amount} + {@code status} consistent when customer payments
 * allocate against this invoice (phase 5c payment slice).
 */
public final class CustomerInvoice {

    /**
     * Wire-format aggregate-type stamped onto {@code finance.outbox_message.aggregate_type}
     * for events this aggregate emits.
     */
    public static final String AGGREGATE_TYPE = FinanceAggregateTypes.CUSTOMER_INVOICE;

    /**
     * Human-readable number prefix for new customer invoices; stamped by
     * {@code CustomerInvoiceService.create}. Pure formatting choice — no
     * consumer dispatches on this value.
     */
    public static final String NUMBER_PREFIX = "INV-";

    /**
     * Character count of the random suffix appended to {@link #NUMBER_PREFIX}
     * when constructing a new customer-invoice number (a
     * {@code UUID.randomUUID().toString().substring(0, …).toUpperCase()}
     * slice). Pairs with the prefix — together they define the full format.
     */
    public static final int NUMBER_SUFFIX_LENGTH = 8;

    /**
     * Customer-invoice lifecycle status. Mirrors the schema CHECK on
     * {@code finance.customer_invoice_header.status}. Lifecycle:
     * {@code POSTED → PARTIALLY_PAID → PAID} (the {@code maintain_allocation_totals}
     * DB trigger flips the column as customer payments allocate). Java only
     * ever writes {@code POSTED} itself; {@code PARTIALLY_PAID} / {@code PAID}
     * arrive via the trigger but must be parseable on read.
     */
    public enum Status {
        /** Schema-prep — not currently produced by Java or trigger. */
        DRAFT("draft"),
        POSTED("posted"),
        PARTIALLY_PAID("partially_paid"),
        PAID("paid"),
        /** Schema-prep — not currently produced by Java or trigger. */
        CANCELLED("cancelled");

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
            throw Assert.unknownValue("customer_invoice status", value);
        }
    }

    /**
     * Discriminator between the two AR commercial patterns — mirrors the
     * schema CHECK on {@code finance.customer_invoice_header.invoice_type}.
     * <ul>
     *   <li>{@link #COMMERCIAL} — invoice created at shipment, posts
     *       Dr AR / Cr Revenue at creation, payment posts Dr Cash / Cr AR
     *       (Northwood's existing on-shipment flow).</li>
     *   <li>{@link #PREPAYMENT} — invoice created at order placement, NO GL
     *       at creation; payment posts Dr Cash / Cr 2110 Customer Deposits;
     *       shipment (Slice C) reclassifies Dr 2110 / Cr Revenue.</li>
     *   <li>{@link #DEPOSIT} — part-payment invoice created at placement for
     *       {@code total × deposit_percent}, NO GL at creation; payment posts
     *       Dr Cash / Cr 2110 (same as prepayment); shipment recognises the
     *       deposit portion and a {@link #BALANCE} invoice carries the
     *       remainder.</li>
     *   <li>{@link #BALANCE} — the remaining {@code total − deposit} invoiced
     *       at shipment, posting Dr AR / Cr Revenue (like
     *       {@link #COMMERCIAL}).</li>
     * </ul>
     */
    public enum InvoiceType {
        COMMERCIAL("commercial"),
        PREPAYMENT("prepayment"),
        DEPOSIT("deposit"),
        BALANCE("balance");

        private final String dbValue;

        InvoiceType(String dbValue) {
            this.dbValue = dbValue;
        }

        public String dbValue() {
            return dbValue;
        }

        public static InvoiceType fromDb(String value) {
            for (InvoiceType t : values()) {
                if (t.dbValue.equals(value)) return t;
            }
            throw Assert.unknownValue("customer_invoice invoice_type", value);
        }
    }

    private final CustomerInvoiceId id;
    private final String invoiceNumber;
    private final UUID salesOrderHeaderId;
    private final UUID customerId;
    private final String customerCode;
    private final String customerName;
    private final String currencyCode;
    private final BigDecimal subtotalAmount;
    private final BigDecimal taxAmount;
    private final BigDecimal totalAmount;
    private final Status status;
    private final InvoiceType invoiceType;
    private final List<CustomerInvoiceLine> lines;
    private final long version;
    private final List<DomainEvent> pendingEvents = new ArrayList<>();

    /** Factory: create + auto-post from a sales-order shipment ({@link InvoiceType#COMMERCIAL}). */
    public static CustomerInvoice create(
        String invoiceNumber,
        UUID salesOrderHeaderId,
        UUID customerId,
        String customerCode,
        String customerName,
        String currencyCode,
        List<CustomerInvoiceLine> lines
    ) {
        return build(InvoiceType.COMMERCIAL, invoiceNumber, salesOrderHeaderId,
            customerId, customerCode, customerName, currencyCode, lines);
    }

    /**
     * Factory: create + auto-post a prepayment invoice from a
     * sales order at placement (no GL post at creation — Treatment A). Emits
     * the same {@link CustomerInvoiceCreated} event the on-shipment path
     * emits; downstream branching on {@link InvoiceType} happens at the
     * payment-receipt GL handler in finance.
     */
    public static CustomerInvoice createPrepayment(
        String invoiceNumber,
        UUID salesOrderHeaderId,
        UUID customerId,
        String customerCode,
        String customerName,
        String currencyCode,
        List<CustomerInvoiceLine> lines
    ) {
        return build(InvoiceType.PREPAYMENT, invoiceNumber, salesOrderHeaderId,
            customerId, customerCode, customerName, currencyCode, lines);
    }

    /**
     * Factory: create + auto-post a <b>deposit</b> invoice (a
     * part-payment on account) from a single synthetic deposit line. Like
     * {@link #createPrepayment}, posts no GL at creation (Treatment A) —
     * the deposit hits the GL only when paid (Dr Cash / Cr 2110).
     */
    public static CustomerInvoice createDeposit(
        String invoiceNumber,
        UUID salesOrderHeaderId,
        UUID customerId,
        String customerCode,
        String customerName,
        String currencyCode,
        List<CustomerInvoiceLine> lines
    ) {
        return build(InvoiceType.DEPOSIT, invoiceNumber, salesOrderHeaderId,
            customerId, customerCode, customerName, currencyCode, lines);
    }

    /**
     * Factory: create + auto-post the <b>balance</b> invoice for a
     * deposit order at shipment (the remaining {@code total − deposit}). Behaves
     * like {@link #create} (commercial) — the caller posts Dr AR / Cr Revenue
     * for the balance; the deposit portion is recognised separately against the
     * deposit invoice in the shipment-time COGS handler.
     */
    public static CustomerInvoice createBalance(
        String invoiceNumber,
        UUID salesOrderHeaderId,
        UUID customerId,
        String customerCode,
        String customerName,
        String currencyCode,
        List<CustomerInvoiceLine> lines
    ) {
        return build(InvoiceType.BALANCE, invoiceNumber, salesOrderHeaderId,
            customerId, customerCode, customerName, currencyCode, lines);
    }

    private static CustomerInvoice build(
        InvoiceType invoiceType,
        String invoiceNumber,
        UUID salesOrderHeaderId,
        UUID customerId,
        String customerCode,
        String customerName,
        String currencyCode,
        List<CustomerInvoiceLine> lines
    ) {
        Assert.notNull(salesOrderHeaderId, "salesOrderHeaderId");
        Assert.notNull(customerId, "customerId");
        Assert.notEmpty(lines, "at least one line is required");

        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal tax = BigDecimal.ZERO;
        for (CustomerInvoiceLine l : lines) {
            subtotal = subtotal.add(l.lineTotal());
            tax = tax.add(l.taxAmount() == null ? BigDecimal.ZERO : l.taxAmount());
        }
        subtotal = subtotal.setScale(2, RoundingMode.HALF_UP);
        tax = tax.setScale(2, RoundingMode.HALF_UP);
        BigDecimal total = subtotal.add(tax);

        CustomerInvoiceId id = CustomerInvoiceId.newId();
        CustomerInvoice ci = new CustomerInvoice(
            id, invoiceNumber, salesOrderHeaderId,
            customerId, customerCode, customerName,
            Currencies.orBase(currencyCode),
            subtotal, tax, total,
            Status.POSTED,
            invoiceType,
            new ArrayList<>(lines), 0L
        );

        ci.pendingEvents.add(new CustomerInvoiceCreated(
            UUID.randomUUID(),
            id.value(),
            invoiceNumber,
            salesOrderHeaderId,
            customerId,
            customerCode,
            customerName,
            ci.currencyCode,
            total,
            Status.POSTED.dbValue(),
            Instant.now()
        ));
        return ci;
    }

    /** Factory: hydrate from the DB; emits no events. */
    public static CustomerInvoice reconstitute(
        CustomerInvoiceId id, String invoiceNumber, UUID salesOrderHeaderId,
        UUID customerId, String customerCode, String customerName,
        String currencyCode,
        BigDecimal subtotalAmount, BigDecimal taxAmount, BigDecimal totalAmount,
        Status status,
        InvoiceType invoiceType,
        List<CustomerInvoiceLine> lines, long version
    ) {
        return new CustomerInvoice(
            id, invoiceNumber, salesOrderHeaderId,
            customerId, customerCode, customerName,
            currencyCode,
            subtotalAmount, taxAmount, totalAmount,
            status,
            invoiceType,
            new ArrayList<>(lines), version
        );
    }

    private CustomerInvoice(
        CustomerInvoiceId id, String invoiceNumber, UUID salesOrderHeaderId,
        UUID customerId, String customerCode, String customerName,
        String currencyCode,
        BigDecimal subtotalAmount, BigDecimal taxAmount, BigDecimal totalAmount,
        Status status,
        InvoiceType invoiceType,
        List<CustomerInvoiceLine> lines, long version
    ) {
        this.id = id;
        this.invoiceNumber = invoiceNumber;
        this.salesOrderHeaderId = salesOrderHeaderId;
        this.customerId = customerId;
        this.customerCode = customerCode;
        this.customerName = customerName;
        this.currencyCode = currencyCode;
        this.subtotalAmount = subtotalAmount;
        this.taxAmount = taxAmount;
        this.totalAmount = totalAmount;
        this.status = status;
        this.invoiceType = Assert.notNull(invoiceType, "invoiceType");
        this.lines = lines;
        this.version = version;
    }

    public List<DomainEvent> pullPendingEvents() {
        List<DomainEvent> out = List.copyOf(pendingEvents);
        pendingEvents.clear();
        return out;
    }

    public CustomerInvoiceId id()                { return id; }
    public String invoiceNumber()                { return invoiceNumber; }
    public UUID salesOrderHeaderId()             { return salesOrderHeaderId; }
    public UUID customerId()                     { return customerId; }
    public String customerCode()                 { return customerCode; }
    public String customerName()                 { return customerName; }
    public String currencyCode()                 { return currencyCode; }
    public BigDecimal subtotalAmount()           { return subtotalAmount; }
    public BigDecimal taxAmount()                { return taxAmount; }
    public BigDecimal totalAmount()              { return totalAmount; }
    public Status status()                       { return status; }
    public InvoiceType invoiceType()             { return invoiceType; }
    public List<CustomerInvoiceLine> lines()     { return List.copyOf(lines); }
    public long version()                        { return version; }
}
