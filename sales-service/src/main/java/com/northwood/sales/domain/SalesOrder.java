package com.northwood.sales.domain;

import com.northwood.sales.domain.events.SalesOrderCancellationRequested;
import com.northwood.sales.domain.events.SalesOrderLineAdded;
import com.northwood.sales.domain.events.SalesOrderLineQuantityChanged;
import com.northwood.sales.domain.events.SalesOrderLineRemoved;
import com.northwood.sales.domain.events.SalesOrderPlaced;
import com.northwood.sales.domain.events.SalesOrderPlaced.PlacedLine;
import com.northwood.sales.domain.events.SalesOrderShipped;
import com.northwood.sales.domain.events.SalesOrderShipped.ShippedLine;
import com.northwood.shared.domain.Assert;
import com.northwood.shared.domain.Currencies;
import com.northwood.shared.domain.DomainEvent;
import com.northwood.shared.domain.LineNumbering;
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
import java.util.UUID;

/**
 * Aggregate root for a sales order: header + ordered lines.
 *
 * <p>Pricing/currency snapshot lives on the header. The fulfilment workflow
 * status is one column ({@code status}); cross-cutting flags (stock, mfg,
 * shipment, invoice) belong on the read model fed by saga events, not on the
 * aggregate itself.
 *
 * <p><b>Header status is a fold over the lines — the aggregate is its sole
 * writer.</b> {@link #recomputeStatus()} derives the ship-axis status
 * ({@code submitted} → {@code in_fulfilment} → {@code partially_shipped} →
 * {@code shipped}) from the live line states after every mutation — the
 * {@code classify(meet, join)} rollup of {@code docs/composed-state-machines.md}
 * §13.3 — and the absorbing lifecycle terminals are intent-named guarded
 * transitions: {@link #cancel} ({@code cancelled}), {@link #reject}
 * ({@code rejected}), {@link #complete} ({@code completed}). There is no blind
 * {@code UPDATE status} from a projection: the former
 * {@code SalesOrderHeaderStatusProjection} is retired.
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
        BigDecimal shippedQuantity,
        BigDecimal unitCost
    ) {}

    /**
     * Result of {@link #recordShipped}: {@code orderFullyShipped} is true when
     * every line's cumulative shipped quantity now meets its ordered quantity
     * (header moved to {@code shipped}); false for a partial shipment that
     * leaves a backorder (header at {@code partially_shipped}). The inbox
     * handler gates the fulfilment saga on this.
     */
    public record ShipmentOutcome(boolean orderFullyShipped) {}

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
        /** Some — but not all — lines have shipped; a backorder remains. */
        PARTIALLY_SHIPPED("partially_shipped"),
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
            throw Assert.unknownValue("sales_order status", value);
        }
    }

    /**
     * SalesOrderLine fulfilment status — the per-line state machine the header
     * fold ({@link #recomputeStatus()}) rolls up. Mirrors the schema CHECK on
     * {@code sales.sales_order_line.line_status}; values flagged
     * <i>schema-prep</i> are accepted by the column but not currently produced
     * by Java.
     */
    public enum LineStatus {
        OPEN("open"),
        RESERVED("reserved"),
        PARTIALLY_RESERVED("partially_reserved"),
        /** Schema-prep — not currently produced by Java (to-order extension). */
        WAITING_FOR_PRODUCTION("waiting_for_production"),
        /** Schema-prep — not currently produced by Java (to-order extension). */
        READY_TO_SHIP("ready_to_ship"),
        PARTIALLY_SHIPPED("partially_shipped"),
        SHIPPED("shipped"),
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
            throw Assert.unknownValue("sales_order_line line_status", value);
        }
    }

    /**
     * Wire-format aggregate-type stamped onto {@code sales.outbox_message.aggregate_type}
     * for events this aggregate emits. Same-service outbox writers reference this
     * constant; cross-service emitters that target this aggregate type carry their own
     * literal on the event class.
     */
    public static final String AGGREGATE_TYPE = SalesAggregateTypes.SALES_ORDER;

    private final SalesOrderId id;
    private final String orderNumber;
    // Customer aggregate slice (2026-05-08): customerId, customerCode,
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
    /**
     * Commercial {@link PaymentTerms} snapshotted from the customer at
     * placement (overridable per-order). Immutable on the order — change it
     * via cancel + replace, not a mutator.
     */
    private final PaymentTerms paymentTerms;
    /**
     * Up-front fraction (0,100] for {@link PaymentTerms#DEPOSIT} orders;
     * null for every other term. Immutable, like {@link #paymentTerms}.
     */
    private final BigDecimal depositPercent;
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
        EnumSet.of(Status.PARTIALLY_SHIPPED, Status.SHIPPED, Status.COMPLETED, Status.CANCELLED, Status.REJECTED);

    /**
     * Header statuses in which line amendment (add / change / remove) is allowed
     * — the coarse domain guard. Twin of {@link #NON_CANCELLABLE_STATUSES}: a
     * line can only change while the order is still being fulfilled, never once
     * it has shipped, completed, been cancelled, or rejected. The finer
     * saga-state window (only before stock is reserved, this slice) is enforced
     * by the application service, which knows the fulfilment saga's state.
     */
    private static final Set<Status> AMENDABLE_STATUSES =
        EnumSet.of(Status.SUBMITTED, Status.IN_FULFILMENT);

    // Ship-progress lattice M_ship (docs/composed-state-machines.md §13.1): the
    // coarse band a line status maps onto for the header fold. Ordered so meet/
    // join are plain int min/max. `cancelled` is off the chain (filtered before
    // the fold), so it has no band.
    private static final int BAND_NOT_STARTED = 0;
    private static final int BAND_IN_PROGRESS = 1;
    private static final int BAND_READY = 2;
    private static final int BAND_PARTIALLY_SHIPPED = 3;
    private static final int BAND_SHIPPED = 4;

    public static SalesOrder place(
        String orderNumber,
        UUID customerId,
        String customerCode,
        String customerName,
        LocalDate requestedDeliveryDate,
        String currencyCode,
        BigDecimal exchangeRate,
        PaymentTerms paymentTerms,
        BigDecimal depositPercent,
        List<SalesOrderLine> lines
    ) {
        Assert.notEmpty(lines, "at least one line is required");
        SalesOrderId id = SalesOrderId.newId();
        SalesOrder order = new SalesOrder(
            id,
            Assert.notNull(orderNumber, "orderNumber"),
            Assert.notNull(customerId, "customerId"),
            Assert.notNull(customerCode, "customerCode"),
            Assert.notNull(customerName, "customerName"),
            LocalDate.now(),
            requestedDeliveryDate,
            Status.SUBMITTED,
            Assert.notNull(currencyCode, "currencyCode"),
            exchangeRate == null ? BigDecimal.ONE : exchangeRate,
            Assert.notNull(paymentTerms, "paymentTerms"),
            depositPercent,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            null,
            0L,
            new ArrayList<>(lines)
        );
        order.recomputeTotals();
        order.recomputeStatus();

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
            paymentTerms.dbValue(),
            depositPercent,
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
        PaymentTerms paymentTerms,
        BigDecimal depositPercent,
        BigDecimal subtotalAmount,
        BigDecimal taxAmount,
        BigDecimal totalAmount,
        Instant cancelledAt,
        long version,
        List<SalesOrderLine> lines
    ) {
        return new SalesOrder(
            id, orderNumber, customerId, customerCode, customerName,
            orderDate, requestedDeliveryDate, status, currencyCode, exchangeRate, paymentTerms,
            depositPercent, subtotalAmount, taxAmount, totalAmount, cancelledAt, version, new ArrayList<>(lines)
        );
    }

    private SalesOrder(
        SalesOrderId id, String orderNumber, UUID customerId, String customerCode, String customerName,
        LocalDate orderDate, LocalDate requestedDeliveryDate, Status status, String currencyCode, BigDecimal exchangeRate,
        PaymentTerms paymentTerms,
        BigDecimal depositPercent,
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
        this.paymentTerms = paymentTerms;
        this.depositPercent = depositPercent;
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
     * <p><b>Partial shipments.</b> Each matched line accumulates its shipped
     * quantity ({@link SalesOrderLine#recordShipment}) and moves to
     * {@code shipped} or {@code partially_shipped}. The header moves to
     * {@code shipped} only when every line is fully shipped, else to
     * {@code partially_shipped}; either way the order is no longer cancellable
     * via the simple cancel path (see {@link #NON_CANCELLABLE_STATUSES}). The
     * returned {@link ShipmentOutcome#orderFullyShipped()} (also stamped on the
     * emitted {@link SalesOrderShipped}) tells the caller whether this shipment
     * completed the order — the saga gates {@code goods_shipped} on it.
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
    public ShipmentOutcome recordShipped(
        UUID shipmentHeaderId,
        String shipmentNumber,
        LocalDate shipmentDate,
        List<ShippedLineInput> shippedLines
    ) {
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
            // Accumulate the shipped quantity onto the matched line (moves it to
            // shipped / partially_shipped). An unmatched line (sentinel path
            // below) accumulates nothing — it can't be tied to an ordered line.
            if (matched != null) {
                matched.recordShipment(sl.shippedQuantity());
            }
            eventLines.add(new ShippedLine(
                sl.salesOrderLineId(), lineNumber,
                sl.productId(), sl.productSku(), sl.productName(),
                sl.shippedQuantity(), unitPrice, taxRate,
                // Cost is the shipment line's own value (independent of the price
                // match) — finance routes it to COGS, or to Promotions when the
                // line shipped free-of-charge (unitPrice == 0).
                sl.unitCost()
            ));
        }
        // Re-derive the header from the (now-updated) line multiset rather than
        // writing the column directly: the fold maps "every live line SHIPPED" →
        // shipped, "some shipped" → partially_shipped (§13.3). orderFullyShipped
        // is the meet == SHIPPED test the saga gates goods_shipped on.
        recomputeStatus();
        boolean orderFullyShipped = status == Status.SHIPPED;
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
            paymentTerms.dbValue(),
            eventLines,
            orderFullyShipped,
            Instant.now()
        ));
        return new ShipmentOutcome(orderFullyShipped);
    }

    /**
     * Cancel this order. Allowed only while the header status is in a
     * pre-shipped state — once goods have shipped, cancellation requires the
     * credit-note / return-goods flow which is out of scope.
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

    /**
     * Complete the order — the absorbing terminal past {@code shipped}, set when
     * the fulfilment saga reports full settlement. A guarded lifecycle
     * transition, not a blind {@code UPDATE status}: it asserts the order has
     * fully shipped first (the ship-axis meet the aggregate owns), so
     * {@code completed} can only ever advance from {@code shipped}. The cross-
     * aggregate paid signal stays the caller's (the saga's
     * {@code orderFullySettled}, fed from finance) — the pay axis is a
     * projection-total, not aggregate state (<i>deltas get aggregates, totals get
     * projections</i>). Idempotent: completing an already-{@code completed} order
     * is a no-op.
     *
     * @throws IllegalStateException if the order has not fully shipped.
     */
    public void complete() {
        if (status == Status.COMPLETED) {
            return;
        }
        Assert.state(status == Status.SHIPPED, "sales order " + id.value()
            + " cannot complete from '" + status.dbValue() + "' — it must be fully shipped first");
        this.status = Status.COMPLETED;
    }

    /**
     * Reject the order — the absorbing terminal for an order that can never be
     * fulfilled (a short line's replenishment could not be sourced). Set by the
     * saga-driven {@code ReplenishmentCancelled} handler. Like {@link #cancel} it
     * emits {@link SalesOrderCancellationRequested} so inventory releases any
     * partial reservation; unlike cancel it lands in {@code rejected}
     * (system-driven) rather than {@code cancelled} (customer-driven). Idempotent
     * and defensive: a no-op once the order has shipped or reached any terminal.
     */
    public void reject(String reason) {
        if (NON_CANCELLABLE_STATUSES.contains(status)) {
            return;
        }
        this.status = Status.REJECTED;
        this.pendingEvents.add(new SalesOrderCancellationRequested(
            UUID.randomUUID(),
            id.value(),
            orderNumber,
            customerId,
            reason,
            Instant.now()
        ));
    }

    /**
     * Add a new line to the order (line-amendment flow). The aggregate assigns
     * the line id + next {@code line_number} and emits {@link SalesOrderLineAdded}.
     * The caller (application service) has already resolved the unit price the
     * same way placement does. Returns the created line so the caller can surface
     * its id.
     *
     * @throws OrderNotAmendableException if the header status is past the
     *         amendable window.
     */
    public SalesOrderLine addLine(
        UUID productId,
        String productSku,
        String productName,
        BigDecimal orderedQuantity,
        BigDecimal unitPrice,
        BigDecimal taxRate
    ) {
        assertAmendable();
        int nextLineNumber = lines.stream().mapToInt(SalesOrderLine::lineNumber).max().orElse(0) + LineNumbering.STEP;
        SalesOrderLine line = new SalesOrderLine(
            UUID.randomUUID(),
            nextLineNumber,
            productId,
            productSku,
            productName,
            orderedQuantity,
            unitPrice,
            taxRate == null ? BigDecimal.ZERO : taxRate,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            LineStatus.OPEN
        );
        lines.add(line);
        recomputeTotals();
        recomputeStatus();
        this.pendingEvents.add(new SalesOrderLineAdded(
            UUID.randomUUID(),
            id.value(),
            line.lineId(),
            line.lineNumber(),
            productId,
            productSku,
            productName,
            orderedQuantity,
            unitPrice,
            Instant.now()
        ));
        return line;
    }

    /**
     * Change an existing line's ordered quantity and unit price (line-amendment
     * flow). A quantity-only amendment passes the unchanged price; a price-only
     * amendment passes the unchanged quantity. Emits
     * {@link SalesOrderLineQuantityChanged} carrying the before/after quantity
     * and the post-change price.
     *
     * @throws OrderNotAmendableException if past the amendable window.
     * @throws LineNotFoundException if no active line with that id exists.
     */
    public void changeLine(UUID lineId, BigDecimal newQuantity, BigDecimal newUnitPrice) {
        assertAmendable();
        SalesOrderLine line = activeLine(lineId);
        BigDecimal previousQuantity = line.orderedQuantity();
        line.amend(newQuantity, newUnitPrice);
        recomputeTotals();
        recomputeStatus();
        this.pendingEvents.add(new SalesOrderLineQuantityChanged(
            UUID.randomUUID(),
            id.value(),
            lineId,
            line.productId(),
            previousQuantity,
            newQuantity,
            newUnitPrice,
            Instant.now()
        ));
    }

    /**
     * Soft-remove a line (line-amendment flow): the line is flipped to
     * {@code cancelled} (kept so inventory can release against its id) and
     * excluded from totals. Emits {@link SalesOrderLineRemoved} with the
     * quantity that was on the line so inventory knows how much to release.
     *
     * <p>The {@link #place} invariant — an order always has at least one live
     * line — is preserved: removing the last live line throws. The caller drops
     * to cancel-the-order instead.
     *
     * @throws OrderNotAmendableException if past the amendable window.
     * @throws LineNotFoundException if no active line with that id exists.
     */
    public void removeLine(UUID lineId) {
        assertAmendable();
        SalesOrderLine line = activeLine(lineId);
        long liveLines = lines.stream().filter(l -> !l.isCancelled()).count();
        Assert.state(liveLines > 1, "cannot remove the last remaining line; cancel the order instead");
        BigDecimal previousQuantity = line.orderedQuantity();
        line.cancelLine();
        recomputeTotals();
        recomputeStatus();
        this.pendingEvents.add(new SalesOrderLineRemoved(
            UUID.randomUUID(),
            id.value(),
            lineId,
            line.productId(),
            previousQuantity,
            Instant.now()
        ));
    }

    /**
     * Record inventory's stock-reservation outcome onto the live lines (§2.29
     * item 2). Each entry maps a {@code line_number} to the quantity inventory
     * reserved for it, moving the line onto the reservation band via
     * {@link SalesOrderLine#markReserved}; the header is then re-derived
     * ({@link #recomputeStatus()}) so a reserved line lifts the order to
     * {@code in_fulfilment} through the fold rather than a blind projection
     * write. Cancelled (removed) lines are skipped; line numbers absent from the
     * map are left untouched. Emits no event — the reservation is inventory's
     * fact; this is the sales aggregate reflecting it so the line carries the
     * authoritative in-progress band.
     */
    public void recordReservation(Map<Integer, BigDecimal> reservedByLineNumber) {
        for (SalesOrderLine line : lines) {
            if (line.isCancelled()) {
                continue;
            }
            BigDecimal reserved = reservedByLineNumber.get(line.lineNumber());
            if (reserved != null) {
                line.markReserved(reserved);
            }
        }
        recomputeStatus();
    }

    private void assertAmendable() {
        if (!AMENDABLE_STATUSES.contains(status)) {
            throw new OrderNotAmendableException(id, status);
        }
    }

    private SalesOrderLine activeLine(UUID lineId) {
        for (SalesOrderLine line : lines) {
            if (line.lineId().equals(lineId) && !line.isCancelled()) {
                return line;
            }
        }
        throw new LineNotFoundException(id, lineId);
    }

    private void recomputeTotals() {
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal tax = BigDecimal.ZERO;
        for (SalesOrderLine line : lines) {
            if (line.isCancelled()) {
                continue;
            }
            subtotal = subtotal.add(line.lineSubtotal());
            tax = tax.add(line.taxAmount());
        }
        this.subtotalAmount = subtotal.setScale(2, RoundingMode.HALF_UP);
        this.taxAmount = tax.setScale(2, RoundingMode.HALF_UP);
        this.totalAmount = subtotal.add(tax).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Re-derive the header {@code status} from the live-line multiset — the
     * ship-axis {@code classify(meet, join)} fold of
     * {@code docs/composed-state-machines.md} §13.3, sibling to
     * {@link #recomputeTotals()} and called from the same mutators. Sole writer
     * of the derived region; the absorbing lifecycle terminals
     * ({@code cancelled} / {@code completed} / {@code rejected}) are left
     * untouched — a fold must never undo a top-down terminal (§4/§5).
     *
     * <ul>
     *   <li>no live lines remain → {@code cancelled} (the removed-the-last-line
     *       edge; {@link #removeLine} guards against it — this is defensive);</li>
     *   <li>every live line {@code shipped} ({@code meet == SHIPPED}) →
     *       {@code shipped};</li>
     *   <li>at least one line shipped ≥1 unit ({@code join ⊒ partially_shipped})
     *       → {@code partially_shipped};</li>
     *   <li>at least one line reserved / in progress ({@code join ⊒ in_progress})
     *       → {@code in_fulfilment};</li>
     *   <li>otherwise (every live line still {@code open}) → {@code submitted}.</li>
     * </ul>
     *
     * <p>Cancelled lines are filtered (monoid-neutral), the same way
     * {@link #recomputeTotals()} skips them, so the result is invariant under
     * line order, insert order, and how shipments were batched.
     */
    private void recomputeStatus() {
        if (status == Status.CANCELLED || status == Status.REJECTED || status == Status.COMPLETED) {
            return;
        }
        int meet = Integer.MAX_VALUE;
        int join = Integer.MIN_VALUE;
        for (SalesOrderLine line : lines) {
            if (line.isCancelled()) {
                continue;
            }
            int band = shipBand(line.lineStatus());
            meet = Math.min(meet, band);
            join = Math.max(join, band);
        }
        Status next;
        if (join == Integer.MIN_VALUE) {
            // No live lines — defensive; removeLine refuses to empty the order.
            next = Status.CANCELLED;
        } else if (meet == BAND_SHIPPED) {
            next = Status.SHIPPED;
        } else if (join >= BAND_PARTIALLY_SHIPPED) {
            next = Status.PARTIALLY_SHIPPED;
        } else if (join >= BAND_IN_PROGRESS) {
            next = Status.IN_FULFILMENT;
        } else {
            next = Status.SUBMITTED;
        }
        this.status = next;
    }

    /** Coarsen a live line's status onto the ship-progress lattice {@code M_ship} (§13.1). */
    private static int shipBand(LineStatus lineStatus) {
        return switch (lineStatus) {
            case OPEN -> BAND_NOT_STARTED;
            case PARTIALLY_RESERVED, WAITING_FOR_PRODUCTION -> BAND_IN_PROGRESS;
            case RESERVED, READY_TO_SHIP -> BAND_READY;
            case PARTIALLY_SHIPPED -> BAND_PARTIALLY_SHIPPED;
            case SHIPPED -> BAND_SHIPPED;
            // cancelled is off the chain — filtered out before the fold reaches here.
            case CANCELLED -> throw new IllegalStateException(
                "cancelled lines are filtered before the ship-band fold");
        };
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
    public PaymentTerms paymentTerms()        { return paymentTerms; }
    public BigDecimal depositPercent()        { return depositPercent; }
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

    /** Thrown by the line-amendment mutators when the header status is past the amendable window. */
    public static final class OrderNotAmendableException extends RuntimeException {
        private final SalesOrderId orderId;
        private final Status currentStatus;

        public OrderNotAmendableException(SalesOrderId orderId, Status currentStatus) {
            super("Sales order " + orderId.value() + " is in status '" + currentStatus.dbValue()
                + "' and its lines cannot be amended");
            this.orderId = orderId;
            this.currentStatus = currentStatus;
        }

        public SalesOrderId orderId()  { return orderId; }
        public Status currentStatus()  { return currentStatus; }
    }

    /** Thrown by {@link #changeLine}/{@link #removeLine} when the target line id is not an active line. */
    public static final class LineNotFoundException extends RuntimeException {
        private final SalesOrderId orderId;
        private final UUID lineId;

        public LineNotFoundException(SalesOrderId orderId, UUID lineId) {
            super("Sales order " + orderId.value() + " has no active line " + lineId);
            this.orderId = orderId;
            this.lineId = lineId;
        }

        public SalesOrderId orderId()  { return orderId; }
        public UUID lineId()           { return lineId; }
    }
}
