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
import java.util.HashMap;
import java.util.Map;
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
 * <p><b>Header status is a brainless fold over the lines — the aggregate is its
 * sole writer.</b> The lines are the single source of truth; the header status
 * is a <i>derived label</i>, a projection of line state (<i>deltas get
 * aggregates, totals get projections</i> applied to <b>state</b>).
 * {@link #recomputeStatus()} is the faithful {@code classify(meet, join)} fold
 * of the line ship-bands ({@code docs/composed-state-machines.html} §13.3), so the
 * header ship-vocabulary <b>equals</b> the line vocabulary:
 * {@code open ⊏ partially_reserved ⊏ reserved ⊏ partially_shipped ⊏ shipped}.
 * The "all" rungs sit on {@code meet} ({@code reserved}, {@code shipped}); the
 * "some" rungs on {@code join} ({@code partially_reserved},
 * {@code partially_shipped}). On top of that fold region sit three absorbing
 * <b>order-level terminals</b> — top-down commands the fold must never override:
 * {@link #cancel} ({@code cancelled}), {@link #reject} ({@code rejected}),
 * {@link #complete} ({@code completed}). There is no blind {@code UPDATE status}
 * from a projection: the former {@code SalesOrderHeaderStatusProjection} is
 * retired.
 *
 * <p><b>Gates read the lines, not the header.</b> A {@code meet/join} fold to a
 * single scalar is inherently lossy, so the header can never be the gating oracle
 * for line-level policy. Cancel and amend therefore read <i>line predicates</i>
 * ({@link #anyLineShipped()} + {@link #isTerminal()}), not a header-status
 * allow-list — a policy change ("allow cancel at partially_reserved") becomes a
 * one-line predicate edit, no enum migration. Cancellable ≡ amendable, both
 * derived from the same two predicates so they cannot drift.
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
     * {@code sales.sales_order_header.status} — every value here is produced by
     * Java (no schema-prep placeholders).
     *
     * <p>Two categories (see the class Javadoc):
     * <ul>
     *   <li><b>Fold region</b> — {@code OPEN · PARTIALLY_RESERVED · RESERVED ·
     *       PARTIALLY_SHIPPED · SHIPPED}: a pure {@code meet/join} fold of the
     *       live-line ship-bands, so the header ship-vocabulary <b>equals</b> the
     *       {@link LineStatus} vocabulary ({@link #recomputeStatus()} is the sole
     *       writer). The {@code partially_*} names mean within-line-quantity
     *       partial at the line and cross-line straddle at the order — same
     *       {@code classify} shape, fractal (§2.3).</li>
     *   <li><b>Order-level terminals</b> — {@code COMPLETED · CANCELLED ·
     *       REJECTED}: top-down, absorbing decisions the fold must never override
     *       ({@link #complete} / {@link #cancel} / {@link #reject}).</li>
     * </ul>
     *
     * <p>Distinct from {@code SalesOrderFulfilmentSaga} state constants
     * (different domain — saga progress vs header lifecycle).
     */
    public enum Status {
        /** Fold region — no live line has begun supply (every live line {@code open}). */
        OPEN("open"),
        /** Fold region — some (not all) live lines reserved ({@code join ⊒ partially_reserved}, {@code meet ⊏ reserved}). */
        PARTIALLY_RESERVED("partially_reserved"),
        /** Fold region — every live line reserved ({@code meet ⊒ reserved}), none shipped. */
        RESERVED("reserved"),
        /** Fold region — some (not all) live lines have shipped; a backorder remains ({@code join ⊒ partially_shipped}). */
        PARTIALLY_SHIPPED("partially_shipped"),
        /** Fold region — every live line fully shipped ({@code meet == shipped}). */
        SHIPPED("shipped"),
        /** Order-level terminal — fully settled (top-down; {@link #complete}). */
        COMPLETED("completed"),
        /** Order-level terminal — customer-cancelled (top-down; {@link #cancel}). */
        CANCELLED("cancelled"),
        /** Order-level terminal — system-rejected, unsourceable (top-down; {@link #reject}). */
        REJECTED("rejected");

        private final String code;

        Status(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }

        public static Status fromCode(String value) {
            for (Status s : values()) {
                if (s.code.equals(value)) return s;
            }
            throw Assert.unknownValue("sales_order status", value);
        }
    }

    /**
     * SalesOrderLine fulfilment status — the per-line state machine the header
     * fold ({@link #recomputeStatus()}) rolls up, on the shipment axis. Mirrors
     * the schema CHECK on {@code sales.sales_order_line.line_status} — every
     * value here is produced by Java.
     *
     * <p>A to-order line (make / buy) carries <b>no</b> production/receipt
     * sub-state here: its supply progress lives in the fulfilment saga + the
     * manufacturing work order / inventory replenishment + reporting's
     * {@code manufacturing_status} / {@code stock_status}. The line itself moves
     * {@code open → reserved → shipped} once its order-pegged supply is reserved,
     * the same chain a stock line runs (see {@code docs/composed-state-machines.html}
     * §11 — the fold never branches on product type).
     */
    public enum LineStatus {
        OPEN("open"),
        RESERVED("reserved"),
        PARTIALLY_RESERVED("partially_reserved"),
        PARTIALLY_SHIPPED("partially_shipped"),
        SHIPPED("shipped"),
        CANCELLED("cancelled");

        private final String code;

        LineStatus(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }

        public static LineStatus fromCode(String value) {
            for (LineStatus s : values()) {
                if (s.code.equals(value)) return s;
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

    // Ship-progress lattice M_ship (docs/composed-state-machines.html §13.1): the
    // band a line status maps onto for the header fold. The fold region of
    // {@link Status} shares this vocabulary 1:1, so the bands carry the line
    // names. Ordered so meet/join are plain int min/max. `cancelled` is off the
    // chain (filtered before the fold), so it has no band.
    private static final int BAND_OPEN = 0;
    private static final int BAND_PARTIALLY_RESERVED = 1;
    private static final int BAND_RESERVED = 2;
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
            Status.OPEN,
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
            paymentTerms.code(),
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
     * via the simple cancel path (a live line has shipped — see
     * {@link #anyLineShipped()} / {@link #cancel}). The
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
            paymentTerms.code(),
            eventLines,
            orderFullyShipped,
            Instant.now()
        ));
        return new ShipmentOutcome(orderFullyShipped);
    }

    /**
     * <b>Request</b> cancellation of this order — phase 1 of the two-phase,
     * cross-service-arbitrated cancel. A top-down, order-level command, allowed
     * only while no live line has shipped <em>in sales' view</em> and the order
     * has not reached a terminal. It does <b>not</b> finalise the status: it only
     * emits {@link SalesOrderCancellationRequested}. Inventory then arbitrates
     * against any concurrent shipment (the ship-claim vs the cancellation-claim on
     * {@code sales_order_line_facts}) and replies — only on its
     * {@code SalesOrderCancellationApplied} ack does {@link #confirmCancellation()}
     * move the order to {@code cancelled}. This closes the cancel-vs-ship race: a
     * shipment that physically committed in inventory before the cancel reached it
     * wins, and the cancellation is silently dropped (the order ships).
     *
     * <p>The guard reads the <i>lines</i> ({@link #anyLineShipped()}), not a
     * header-status allow-list: the header is a lossy fold, so it can't be the
     * gating oracle (see the class Javadoc). {@link #isTerminal()} blocks a
     * re-cancel. Note this sales-local guard only catches shipments sales already
     * knows about; the inventory arbiter is what catches the in-flight race.
     *
     * @throws OrderNotCancellableException if the order has already shipped (in
     *         sales' view), completed, been cancelled, or rejected.
     */
    public void requestCancellation(String reason) {
        if (isTerminal() || anyLineShipped()) {
            throw new OrderNotCancellableException(id, status);
        }
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
     * <b>Confirm</b> cancellation — phase 2, driven by inventory's
     * {@code SalesOrderCancellationApplied} ack (which inventory emits only when no
     * line had shipped). Moves the order to the {@code cancelled} terminal. A no-op
     * if a shipment has since landed ({@link #anyLineShipped()}) or the order is
     * already terminal — that is the race-loser path: the ack and a late shipment
     * can interleave, and a shipped order must stay shipped, never flip to
     * cancelled. Idempotent.
     */
    public void confirmCancellation() {
        if (isTerminal() || anyLineShipped()) {
            return;
        }
        this.status = Status.CANCELLED;
        this.cancelledAt = Instant.now();
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
            + " cannot complete from '" + status.code() + "' — it must be fully shipped first");
        this.status = Status.COMPLETED;
    }

    /**
     * Reject the order — the absorbing terminal for an order that can never be
     * fulfilled (a short line's replenishment could not be sourced). Set by the
     * saga-driven {@code ReplenishmentCancelled} handler. Like {@link #cancel} it
     * emits {@link SalesOrderCancellationRequested} so inventory releases any
     * partial reservation; unlike cancel it lands in {@code rejected}
     * (system-driven) rather than {@code cancelled} (customer-driven). Idempotent
     * and defensive: a no-op once a live line has shipped or the order reached
     * any terminal (the same line-predicate guard as {@link #cancel}).
     */
    public void reject(String reason) {
        if (isTerminal() || anyLineShipped()) {
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
            totalAmount,
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
            totalAmount,
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
            totalAmount,
            Instant.now()
        ));
    }

    /**
     * Record inventory's stock-reservation outcome onto the live lines. Each
     * entry maps a {@code line_number} to the quantity inventory
     * reserved for it, moving the line onto the reservation band via
     * {@link SalesOrderLine#markReserved}; the header is then re-derived
     * ({@link #recomputeStatus()}) so reserved lines lift the order to
     * {@code partially_reserved} / {@code reserved} through the fold rather than a
     * blind projection write. Cancelled (removed) lines are skipped; line numbers absent from the
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

    /**
     * Coarse domain guard for the line-amendment mutators — the twin of
     * {@link #cancel}'s guard (cancellable ≡ amendable). Reads the same two line
     * predicates: a line may change while no live line has shipped
     * ({@link #anyLineShipped()}) and the order is not terminal
     * ({@link #isTerminal()}). The finer saga-state window (only before stock is
     * reserved) is enforced by the application service, which knows the
     * fulfilment saga's state.
     */
    private void assertAmendable() {
        if (isTerminal() || anyLineShipped()) {
            throw new OrderNotAmendableException(id, status);
        }
    }

    /**
     * True once any live line has shipped ({@code band ⊒ partially_shipped}) — the
     * point past which cancel/amend require the credit-note flow. Reads the lines
     * directly (the authoritative grain), not the lossy header fold.
     */
    private boolean anyLineShipped() {
        for (SalesOrderLine line : lines) {
            if (line.isCancelled()) {
                continue;
            }
            if (shipBand(line.lineStatus()) >= BAND_PARTIALLY_SHIPPED) {
                return true;
            }
        }
        return false;
    }

    /** True once the header has reached an absorbing order-level terminal. */
    private boolean isTerminal() {
        return status == Status.CANCELLED || status == Status.REJECTED || status == Status.COMPLETED;
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
     * faithful ship-axis {@code classify(meet, join)} fold of
     * {@code docs/composed-state-machines.html} §13.3, sibling to
     * {@link #recomputeTotals()} and called from the same mutators. Sole writer
     * of the fold region; the absorbing order-level terminals
     * ({@code cancelled} / {@code completed} / {@code rejected}) are left
     * untouched — a bottom-up fold must never undo a top-down terminal (§4/§5).
     *
     * <p>The header ship-vocabulary equals the {@link LineStatus} vocabulary, so
     * the ladder reads straight off {@code meet}/{@code join} of the bands:
     * <ul>
     *   <li>no live lines remain → {@code cancelled} (the removed-the-last-line
     *       edge; {@link #removeLine} guards against it — this is defensive);</li>
     *   <li>every live line {@code shipped} ({@code meet == SHIPPED}) →
     *       {@code shipped};</li>
     *   <li>some (not all) live lines shipped ({@code join ⊒ partially_shipped})
     *       → {@code partially_shipped};</li>
     *   <li>every live line reserved or beyond ({@code meet ⊒ reserved}) →
     *       {@code reserved};</li>
     *   <li>some (not all) live lines reserved ({@code join ⊒ partially_reserved})
     *       → {@code partially_reserved};</li>
     *   <li>otherwise (every live line still {@code open}) → {@code open}.</li>
     * </ul>
     *
     * <p>Cancelled lines are filtered (monoid-neutral), the same way
     * {@link #recomputeTotals()} skips them, so the result is invariant under
     * line order, insert order, and how shipments were batched.
     */
    private void recomputeStatus() {
        if (isTerminal()) {
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
        } else if (meet >= BAND_RESERVED) {
            next = Status.RESERVED;
        } else if (join >= BAND_PARTIALLY_RESERVED) {
            next = Status.PARTIALLY_RESERVED;
        } else {
            next = Status.OPEN;
        }
        this.status = next;
    }

    /** Coarsen a live line's status onto the ship-progress lattice {@code M_ship} (§13.1). */
    private static int shipBand(LineStatus lineStatus) {
        return switch (lineStatus) {
            case OPEN -> BAND_OPEN;
            case PARTIALLY_RESERVED -> BAND_PARTIALLY_RESERVED;
            case RESERVED -> BAND_RESERVED;
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
            super("Sales order " + orderId.value() + " is in status '" + currentStatus.code()
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
            super("Sales order " + orderId.value() + " is in status '" + currentStatus.code()
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
