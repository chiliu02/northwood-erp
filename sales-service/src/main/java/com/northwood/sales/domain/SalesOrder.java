package com.northwood.sales.domain;

import com.northwood.sales.domain.events.SalesOrderCancellationRequested;
import com.northwood.sales.domain.events.SalesOrderPlaced;
import com.northwood.sales.domain.events.SalesOrderPlaced.PlacedLine;
import com.northwood.sales.domain.events.SalesOrderShipped;
import com.northwood.sales.domain.events.SalesOrderShipped.ShippedLine;
import com.northwood.shared.domain.Currencies;
import com.northwood.shared.domain.DomainEvent;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Aggregate root for a sales order: header + ordered lines.
 *
 * <p>Pricing/currency snapshot lives on the header. The fulfilment workflow
 * status is one column ({@code status}); cross-cutting flags (stock, mfg,
 * shipment, invoice) belong on the read model fed by saga events, not on the
 * aggregate itself.
 *
 * <p>Mutations are intent-named methods that emit domain events captured by
 * the application service for outbox publication.
 */
public final class SalesOrder {

    /** Caller-supplied per-line shipment data for {@link #recordShipped}. */
    public record ShippedLineInput(
        UUID salesOrderLineId,
        UUID productId,
        String productSku,
        String productName,
        BigDecimal shippedQuantity
    ) {}

    /**
     * SalesOrder header fulfilment status. Mirrors the schema CHECK on
     * {@code sales.sales_order_header.status}; values flagged
     * <i>schema-prep</i> are accepted by the column but not currently produced
     * by Java — kept on the enum so {@link #fromDb(String)} can parse them.
     *
     * <p>Distinct from {@code SalesOrderFulfilmentSaga} state constants
     * (different domain — saga progress vs header lifecycle).
     */
    public enum Status {
        /** Schema-prep — not currently produced by Java. */
        DRAFT("draft"),
        SUBMITTED("submitted"),
        /** Schema-prep — not currently produced by Java. */
        CONFIRMED("confirmed"),
        IN_FULFILMENT("in_fulfilment"),
        SHIPPED("shipped"),
        COMPLETED("completed"),
        CANCELLED("cancelled"),
        REJECTED("rejected");

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
            throw new IllegalArgumentException("Unknown sales_order status: " + value);
        }
    }

    /**
     * SalesOrderLine fulfilment status. Mirrors the schema CHECK on
     * {@code sales.sales_order_line.line_status}; values flagged
     * <i>schema-prep</i> are accepted by the column but not currently produced
     * by Java.
     */
    public enum LineStatus {
        OPEN("open"),
        RESERVED("reserved"),
        PARTIALLY_RESERVED("partially_reserved"),
        /** Schema-prep — not currently produced by Java. */
        WAITING_FOR_PRODUCTION("waiting_for_production"),
        /** Schema-prep — not currently produced by Java. */
        READY_TO_SHIP("ready_to_ship"),
        /** Schema-prep — not currently produced by Java. */
        PARTIALLY_SHIPPED("partially_shipped"),
        /** Schema-prep — not currently produced by Java. */
        SHIPPED("shipped"),
        /** Schema-prep — not currently produced by Java. */
        CANCELLED("cancelled");

        private final String dbValue;

        LineStatus(String dbValue) {
            this.dbValue = dbValue;
        }

        public String dbValue() {
            return dbValue;
        }

        public static LineStatus fromDb(String value) {
            for (LineStatus s : values()) {
                if (s.dbValue.equals(value)) return s;
            }
            throw new IllegalArgumentException("Unknown sales_order_line line_status: " + value);
        }
    }

    /**
     * Wire-format aggregate-type stamped onto {@code sales.outbox_message.aggregate_type}
     * for events this aggregate emits. Same-service outbox writers reference this
     * constant; cross-service emitters that target this aggregate type carry their own
     * literal on the event class (see {@code ManufacturingDispatched.AGGREGATE_TYPE}).
     */
    public static final String AGGREGATE_TYPE = SalesAggregateTypes.SALES_ORDER;

    private final SalesOrderId id;
    private final String orderNumber;
    // §2026-05-08 (Customer aggregate slice): customerId, customerCode,
    // customerName are snapshotted at place-order time and never refreshed.
    // Subsequent CustomerNameChanged events do NOT update existing orders or
    // reporting's sales_order_360_view.customer_name — the order shows the
    // name at time of placement (audit-correct ERP behaviour). See
    // design-notes.md → "Snapshotted reference data" + Customer aggregate
    // Javadoc for the locked policy.
    private final UUID customerId;
    private final String customerCode;
    private final String customerName;
    private final LocalDate orderDate;
    private final LocalDate requestedDeliveryDate;
    private Status status;
    private final String currencyCode;
    private final BigDecimal exchangeRate;
    private BigDecimal subtotalAmount;
    private BigDecimal taxAmount;
    private BigDecimal totalAmount;
    private Instant cancelledAt;
    private final long version;
    private final List<SalesOrderLine> lines;
    private final List<DomainEvent> pendingEvents = new ArrayList<>();

    /** Statuses past which a cancel is rejected with 409 (already shipped / paid / terminal). */
    private static final Set<Status> NON_CANCELLABLE_STATUSES =
        EnumSet.of(Status.SHIPPED, Status.COMPLETED, Status.CANCELLED, Status.REJECTED);

    public static SalesOrder place(
        String orderNumber,
        UUID customerId,
        String customerCode,
        String customerName,
        LocalDate requestedDeliveryDate,
        String currencyCode,
        BigDecimal exchangeRate,
        List<SalesOrderLine> lines
    ) {
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("at least one line is required");
        }
        SalesOrderId id = SalesOrderId.newId();
        SalesOrder order = new SalesOrder(
            id,
            Objects.requireNonNull(orderNumber, "orderNumber"),
            Objects.requireNonNull(customerId, "customerId"),
            Objects.requireNonNull(customerCode, "customerCode"),
            Objects.requireNonNull(customerName, "customerName"),
            LocalDate.now(),
            requestedDeliveryDate,
            Status.SUBMITTED,
            Objects.requireNonNull(currencyCode, "currencyCode"),
            exchangeRate == null ? BigDecimal.ONE : exchangeRate,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            null,
            0L,
            new ArrayList<>(lines)
        );
        order.recomputeTotals();

        List<PlacedLine> placedLines = new ArrayList<>();
        for (SalesOrderLine line : order.lines) {
            placedLines.add(new PlacedLine(
                line.lineId(),
                line.lineNumber(),
                line.productId(),
                line.productSku(),
                line.productName(),
                line.orderedQuantity(),
                line.unitPrice()
            ));
        }
        order.pendingEvents.add(new SalesOrderPlaced(
            UUID.randomUUID(),
            id.value(),
            order.orderNumber,
            customerId,
            customerCode,
            customerName,
            currencyCode,
            order.totalAmount,
            placedLines,
            Instant.now()
        ));
        return order;
    }

    public static SalesOrder reconstitute(
        SalesOrderId id,
        String orderNumber,
        UUID customerId,
        String customerCode,
        String customerName,
        LocalDate orderDate,
        LocalDate requestedDeliveryDate,
        Status status,
        String currencyCode,
        BigDecimal exchangeRate,
        BigDecimal subtotalAmount,
        BigDecimal taxAmount,
        BigDecimal totalAmount,
        Instant cancelledAt,
        long version,
        List<SalesOrderLine> lines
    ) {
        return new SalesOrder(
            id, orderNumber, customerId, customerCode, customerName,
            orderDate, requestedDeliveryDate, status, currencyCode, exchangeRate,
            subtotalAmount, taxAmount, totalAmount, cancelledAt, version, new ArrayList<>(lines)
        );
    }

    private SalesOrder(
        SalesOrderId id, String orderNumber, UUID customerId, String customerCode, String customerName,
        LocalDate orderDate, LocalDate requestedDeliveryDate, Status status, String currencyCode, BigDecimal exchangeRate,
        BigDecimal subtotalAmount, BigDecimal taxAmount, BigDecimal totalAmount, Instant cancelledAt, long version,
        List<SalesOrderLine> lines
    ) {
        this.id = id;
        this.orderNumber = orderNumber;
        this.customerId = customerId;
        this.customerCode = customerCode;
        this.customerName = customerName;
        this.orderDate = orderDate;
        this.requestedDeliveryDate = requestedDeliveryDate;
        this.status = status;
        this.currencyCode = currencyCode;
        this.exchangeRate = exchangeRate;
        this.subtotalAmount = subtotalAmount;
        this.taxAmount = taxAmount;
        this.totalAmount = totalAmount;
        this.cancelledAt = cancelledAt;
        this.version = version;
        this.lines = lines;
    }

    /**
     * Record that goods for this order have shipped. Emits
     * {@link SalesOrderShipped} carrying per-line pricing + tax (which
     * inventory's {@code ShipmentPosted} doesn't know — pricing is sales'
     * domain). The aggregate joins shipped lines to its own ordered lines by
     * {@code salesOrderLineId} so it can surface {@code lineNumber},
     * {@code unitPrice}, and {@code taxRate} on the emitted event.
     *
     * <p>Also flips the header status to {@code 'shipped'} — once shipped,
     * the order is no longer cancellable (see {@link #NON_CANCELLABLE_STATUSES}).
     *
     * <p><b>Sentinel-zero fallback for unmatched lines.</b> If an inbound
     * {@link ShippedLineInput} has a {@code salesOrderLineId} that's null or
     * not present in {@link #lines}, the emitted {@code ShippedLine} carries
     * {@code lineNumber=0}, {@code unitPrice=BigDecimal.ZERO}, and
     * {@code taxRate=BigDecimal.ZERO} rather than throwing. Rationale: this
     * method is invoked from an inbox handler reacting to a fait-accompli
     * {@code inventory.ShipmentPosted}; refusing to emit would stall the
     * sales fulfilment saga and prevent finance from auto-creating the
     * customer invoice. The miss is dead-defensive today —
     * {@code Shipment} aggregate populates {@code salesOrderLineId} end-to-end
     * — but if line-id drift ever happens (line edited between reservation
     * and shipment, partial-line decomposition on the inventory side), the
     * saga continues. Downstream consumer
     * {@code finance.CustomerInvoiceService.createFromShippedOrder} trusts
     * these prices verbatim, so a miss propagates as a zero-amount invoice
     * line; that service logs at DEBUG when it sees the sentinel. To tighten
     * later: throw here (loud failure), or fall back to a
     * {@code ProductCardLookup} on the sales side (preserves saga, emits
     * a real number).
     */
    public void recordShipped(
        UUID shipmentHeaderId,
        String shipmentNumber,
        LocalDate shipmentDate,
        List<ShippedLineInput> shippedLines
    ) {
        this.status = Status.SHIPPED;
        Map<UUID, SalesOrderLine> byLineId = new HashMap<>();
        for (SalesOrderLine line : lines) {
            byLineId.put(line.lineId(), line);
        }
        List<ShippedLine> eventLines = new ArrayList<>();
        for (ShippedLineInput sl : shippedLines) {
            SalesOrderLine matched = sl.salesOrderLineId() == null ? null : byLineId.get(sl.salesOrderLineId());
            int lineNumber = matched != null ? matched.lineNumber() : 0;
            BigDecimal unitPrice = matched != null ? matched.unitPrice() : BigDecimal.ZERO;
            BigDecimal taxRate = matched != null ? matched.taxRate() : BigDecimal.ZERO;
            eventLines.add(new ShippedLine(
                sl.salesOrderLineId(), lineNumber,
                sl.productId(), sl.productSku(), sl.productName(),
                sl.shippedQuantity(), unitPrice, taxRate
            ));
        }
        this.pendingEvents.add(new SalesOrderShipped(
            UUID.randomUUID(),
            id.value(),
            orderNumber,
            shipmentHeaderId,
            shipmentNumber,
            customerId,
            customerCode,
            customerName,
            shipmentDate,
            Currencies.orBase(currencyCode),
            eventLines,
            Instant.now()
        ));
    }

    /**
     * Cancel this order. Allowed only while the header status is in a
     * pre-shipped state — once goods have shipped, cancellation requires the
     * credit-note / return-goods flow which is out of scope (dev-todo §4.2).
     *
     * <p>Idempotent in the sense that calling cancel on an already-cancelled
     * order throws — the application service translates this to HTTP 409. If
     * idempotent re-cancel is wanted later, swap the throw for a return.
     *
     * @throws OrderNotCancellableException if the order has already shipped,
     *         completed, been cancelled, or rejected.
     */
    public void cancel(String reason) {
        if (NON_CANCELLABLE_STATUSES.contains(status)) {
            throw new OrderNotCancellableException(id, status);
        }
        this.status = Status.CANCELLED;
        this.cancelledAt = Instant.now();
        this.pendingEvents.add(new SalesOrderCancellationRequested(
            UUID.randomUUID(),
            id.value(),
            orderNumber,
            customerId,
            reason,
            cancelledAt
        ));
    }

    private void recomputeTotals() {
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal tax = BigDecimal.ZERO;
        for (SalesOrderLine line : lines) {
            subtotal = subtotal.add(line.lineSubtotal());
            tax = tax.add(line.taxAmount());
        }
        this.subtotalAmount = subtotal.setScale(2, RoundingMode.HALF_UP);
        this.taxAmount = tax.setScale(2, RoundingMode.HALF_UP);
        this.totalAmount = subtotal.add(tax).setScale(2, RoundingMode.HALF_UP);
    }

    public List<DomainEvent> pullPendingEvents() {
        List<DomainEvent> out = List.copyOf(pendingEvents);
        pendingEvents.clear();
        return out;
    }

    public SalesOrderId id()                  { return id; }
    public String orderNumber()               { return orderNumber; }
    public UUID customerId()                  { return customerId; }
    public String customerCode()              { return customerCode; }
    public String customerName()              { return customerName; }
    public LocalDate orderDate()              { return orderDate; }
    public LocalDate requestedDeliveryDate()  { return requestedDeliveryDate; }
    public Status status()                    { return status; }
    public String currencyCode()              { return currencyCode; }
    public BigDecimal exchangeRate()          { return exchangeRate; }
    public BigDecimal subtotalAmount()        { return subtotalAmount; }
    public BigDecimal taxAmount()             { return taxAmount; }
    public BigDecimal totalAmount()           { return totalAmount; }
    public Instant cancelledAt()              { return cancelledAt; }
    public long version()                     { return version; }
    public List<SalesOrderLine> lines()       { return List.copyOf(lines); }

    /** Thrown by {@link #cancel(String)} when the order is already in a non-cancellable state. */
    public static final class OrderNotCancellableException extends RuntimeException {
        private final SalesOrderId orderId;
        private final Status currentStatus;

        public OrderNotCancellableException(SalesOrderId orderId, Status currentStatus) {
            super("Sales order " + orderId.value() + " is in status '" + currentStatus.dbValue()
                + "' and cannot be cancelled");
            this.orderId = orderId;
            this.currentStatus = currentStatus;
        }

        public SalesOrderId orderId()  { return orderId; }
        public Status currentStatus()  { return currentStatus; }
    }
}
