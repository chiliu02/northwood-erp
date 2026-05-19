package com.northwood.finance.domain;

import com.northwood.finance.domain.events.CustomerInvoiceCreated;
import com.northwood.shared.domain.DomainEvent;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
            throw new IllegalArgumentException("Unknown customer_invoice status: " + value);
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
    private final List<CustomerInvoiceLine> lines;
    private final long version;
    private final List<DomainEvent> pendingEvents = new ArrayList<>();

    /** Factory: create + auto-post from a sales-order shipment. */
    public static CustomerInvoice create(
        String invoiceNumber,
        UUID salesOrderHeaderId,
        UUID customerId,
        String customerCode,
        String customerName,
        String currencyCode,
        List<CustomerInvoiceLine> lines
    ) {
        Objects.requireNonNull(salesOrderHeaderId, "salesOrderHeaderId");
        Objects.requireNonNull(customerId, "customerId");
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("at least one line is required");
        }

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
            currencyCode == null ? "AUD" : currencyCode,
            subtotal, tax, total,
            Status.POSTED,
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
        List<CustomerInvoiceLine> lines, long version
    ) {
        return new CustomerInvoice(
            id, invoiceNumber, salesOrderHeaderId,
            customerId, customerCode, customerName,
            currencyCode,
            subtotalAmount, taxAmount, totalAmount,
            status,
            new ArrayList<>(lines), version
        );
    }

    private CustomerInvoice(
        CustomerInvoiceId id, String invoiceNumber, UUID salesOrderHeaderId,
        UUID customerId, String customerCode, String customerName,
        String currencyCode,
        BigDecimal subtotalAmount, BigDecimal taxAmount, BigDecimal totalAmount,
        Status status,
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
    public List<CustomerInvoiceLine> lines()     { return List.copyOf(lines); }
    public long version()                        { return version; }
}
