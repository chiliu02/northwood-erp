package com.northwood.purchasing.domain;

import com.northwood.purchasing.domain.events.PurchaseOrderApproved;
import com.northwood.purchasing.domain.events.PurchaseOrderCancelled;
import com.northwood.purchasing.domain.events.PurchaseOrderCreated;
import com.northwood.purchasing.domain.events.PurchaseOrderCreated.OrderLine;
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
 * Aggregate root for a purchase order: header + lines. A requisition is
 * converted to a PO via {@link #fromRequisition}; the {@code autoApprove}
 * flag controls whether the new PO lands at {@code 'sent'} (auto-approve,
 * fires {@link PurchaseOrderApproved} immediately) or {@code 'draft'}
 * (waits for {@link #approve} from a human).
 *
 * <p>Approval policy (PO draft / approve, shipped 2026-05-06):
 * <ul>
 *   <li>Shortage-driven auto-PR: {@code autoApprove} reflects
 *       {@code northwood.purchasing.shortagePoAutoApprove} (default
 *       {@code true}) — the work-order saga can flow without a human.</li>
 *   <li>Manual PR (REST {@code POST /api/purchase-requisitions}):
 *       {@code autoApprove=false} always — manual PRs land at draft and
 *       require a human to call {@code POST /api/purchase-orders/{id}/approve}.</li>
 * </ul>
 *
 * <p>Money calculations: {@code lineTotal = orderedQuantity * unitPrice} per
 * line; {@code subtotalAmount = sum(lineTotal)}; {@code taxAmount = sum(taxAmount)};
 * {@code totalAmount = subtotalAmount + taxAmount}. All money kept in the order's
 * own {@code currencyCode}; cross-currency conversion belongs on a separate
 * journal-posting flow (gated on finance flesh-out).
 */
public final class PurchaseOrder {

    /**
     * Wire-format aggregate-type stamped onto {@code purchasing.outbox_message.aggregate_type}
     * for events this aggregate emits.
     */
    public static final String AGGREGATE_TYPE = PurchasingAggregateTypes.PURCHASE_ORDER;

    /**
     * Human-readable number prefix for new purchase orders; stamped by
     * {@code PurchaseOrderService.createFromRequisition}. Pure formatting
     * choice — no consumer dispatches on this value.
     */
    public static final String NUMBER_PREFIX = "PO-";

    /**
     * Character count of the random suffix appended to {@link #NUMBER_PREFIX}
     * when constructing a new purchase-order number (a
     * {@code UUID.randomUUID().toString().substring(0, …).toUpperCase()}
     * slice). Pairs with the prefix — together they define the full format.
     */
    public static final int NUMBER_SUFFIX_LENGTH = 8;

    /**
     * Purchase-order header status. Mirrors the schema CHECK on
     * {@code purchasing.purchase_order_header.status}. Lifecycle:
     * {@code DRAFT → SENT → PARTIALLY_RECEIVED → RECEIVED → PAID} (driven by
     * inbox handlers + receipt projection). Auto-approve flow skips
     * {@code DRAFT}. Schema-prep values are intermediate workflow states that
     * Java doesn't yet produce.
     */
    public enum Status {
        DRAFT("draft"),
        /** Schema-prep — not currently produced by Java. */
        PENDING_APPROVAL("pending_approval"),
        /** Schema-prep — not currently produced by Java. */
        APPROVED("approved"),
        SENT("sent"),
        PARTIALLY_RECEIVED("partially_received"),
        RECEIVED("received"),
        /** Schema-prep — not currently produced by Java. */
        PARTIALLY_INVOICED("partially_invoiced"),
        /** Schema-prep — not currently produced by Java. */
        INVOICED("invoiced"),
        PAID("paid"),
        /** Schema-prep — not currently produced by Java. */
        CLOSED("closed"),
        /** Schema-prep — not currently produced by Java. */
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
            throw Assert.unknownValue("purchase_order status", value);
        }
    }

    /**
     * Purchase-order line status. Mirrors the schema CHECK on
     * {@code purchasing.purchase_order_line.status}. Today's Java only writes
     * {@code OPEN} (initial); the remaining values are schema-prep for the
     * per-line receipt + invoice progression (Phase 2).
     */
    public enum LineStatus {
        OPEN("open"),
        /** Schema-prep — not currently produced by Java. */
        PARTIALLY_RECEIVED("partially_received"),
        /** Schema-prep — not currently produced by Java. */
        RECEIVED("received"),
        /** Schema-prep — not currently produced by Java. */
        INVOICED("invoiced"),
        /** Schema-prep — not currently produced by Java. */
        CLOSED("closed"),
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
            throw Assert.unknownValue("purchase_order_line status", value);
        }
    }

    private final PurchaseOrderId id;
    private final String purchaseOrderNumber;
    private final UUID supplierId;
    private final String supplierCode;
    private final String supplierName;
    private final UUID purchaseRequisitionHeaderId;
    private final String currencyCode;
    private final BigDecimal subtotalAmount;
    private final BigDecimal taxAmount;
    private final BigDecimal totalAmount;
    private Status status;
    private final List<PurchaseOrderLine> lines;
    private final long version;
    private final List<DomainEvent> pendingEvents = new ArrayList<>();

    /**
     * Factory: convert from an approved requisition.
     *
     * <p>When {@code autoApprove=true}, status starts at {@code 'sent'} and
     * both {@link PurchaseOrderCreated} (with {@code status="sent"}) AND
     * {@link PurchaseOrderApproved} fire in the same write — the saga walks
     * straight through to {@code waiting_for_goods}.
     *
     * <p>When {@code autoApprove=false}, status starts at {@code 'draft'} and
     * only {@link PurchaseOrderCreated} (with {@code status="draft"}) fires.
     * The saga holds at {@code 'started'} until a later {@link #approve} call.
     */
    public static PurchaseOrder fromRequisition(
        String purchaseOrderNumber,
        Supplier supplier,
        UUID purchaseRequisitionHeaderId,
        UUID sourceWorkOrderId,
        String currencyCode,
        List<PurchaseOrderLine> lines,
        boolean autoApprove
    ) {
        Assert.notNull(supplier, "supplier");
        Assert.notNull(purchaseRequisitionHeaderId, "purchaseRequisitionHeaderId");
        Assert.notEmpty(lines, "at least one line is required to create a PO");

        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal tax = BigDecimal.ZERO;
        for (PurchaseOrderLine l : lines) {
            subtotal = subtotal.add(l.lineTotal());
            tax = tax.add(l.taxAmount() == null ? BigDecimal.ZERO : l.taxAmount());
        }
        subtotal = subtotal.setScale(2, RoundingMode.HALF_UP);
        tax = tax.setScale(2, RoundingMode.HALF_UP);
        BigDecimal total = subtotal.add(tax);

        // A zero-total PO (every line fell back to a missing supplier price) is
        // never auto-approved/sent — it would only wedge the to-pay flow at
        // invoice time (invoiced_amount > total_amount = 0). It lands at 'draft'
        // for manual pricing + approval, where assertApprovable() gates it.
        boolean autoApproveAllowed = autoApprove && total.signum() > 0;
        Status initialStatus = autoApproveAllowed ? Status.SENT : Status.DRAFT;
        PurchaseOrderId id = PurchaseOrderId.newId();
        PurchaseOrder po = new PurchaseOrder(
            id, purchaseOrderNumber,
            supplier.id().value(), supplier.supplierCode(), supplier.name(),
            purchaseRequisitionHeaderId,
            Currencies.orBase(currencyCode),
            subtotal, tax, total,
            initialStatus,
            new ArrayList<>(lines),
            0L
        );

        List<OrderLine> wireLines = new ArrayList<>();
        for (PurchaseOrderLine l : lines) {
            wireLines.add(new OrderLine(
                l.id(), l.lineNumber(), l.productId(), l.productSku(), l.productName(),
                l.orderedQuantity(), l.unitPrice()
            ));
        }
        po.pendingEvents.add(new PurchaseOrderCreated(
            UUID.randomUUID(),
            id.value(),
            purchaseOrderNumber,
            supplier.id().value(), supplier.supplierCode(), supplier.name(),
            purchaseRequisitionHeaderId,
            sourceWorkOrderId,
            po.currencyCode,
            total,
            initialStatus.dbValue(),
            wireLines,
            Instant.now()
        ));
        if (autoApproveAllowed) {
            po.pendingEvents.add(new PurchaseOrderApproved(
                UUID.randomUUID(),
                id.value(),
                purchaseOrderNumber,
                supplier.id().value(),
                po.currencyCode,
                total,
                "system",
                "auto-approved (shortage-driven PR)",
                Instant.now()
            ));
        }
        return po;
    }

    /**
     * Assert this PO may be approved. Two classes of guard:
     * <ul>
     *   <li><b>status</b> — must be {@code 'draft'};</li>
     *   <li><b>internal consistency</b> — a positive {@code totalAmount} that
     *       equals {@code subtotalAmount + taxAmount}, with {@code subtotalAmount}
     *       and {@code taxAmount} equal to the sums across the lines.</li>
     * </ul>
     * Catches a zero-value PO (a line that fell back to a missing supplier price)
     * <em>and</em> a PO whose denormalised header totals have drifted from its
     * lines (e.g. a line {@code unit_price} edited without recomputing the
     * header — which later trips the {@code invoiced_amount <= total_amount}
     * CHECK at invoice time). Throws {@link PoNotApprovableException} on any
     * violation. Pure read — no mutation, no events — so it is safe to call from
     * a pre-transaction check at the API edge as well as from {@link #approve}.
     */
    public void assertApprovable() {
        if (status != Status.DRAFT) {
            throw new PoNotApprovableException(id, status);
        }
        BigDecimal lineSubtotal = BigDecimal.ZERO;
        BigDecimal lineTax = BigDecimal.ZERO;
        for (PurchaseOrderLine l : lines) {
            lineSubtotal = lineSubtotal.add(l.lineTotal());
            lineTax = lineTax.add(l.taxAmount() == null ? BigDecimal.ZERO : l.taxAmount());
        }
        lineSubtotal = lineSubtotal.setScale(2, RoundingMode.HALF_UP);
        lineTax = lineTax.setScale(2, RoundingMode.HALF_UP);
        if (totalAmount == null || totalAmount.signum() <= 0) {
            throw new PoNotApprovableException(id, status,
                "total amount is " + (totalAmount == null ? "unset" : totalAmount.toPlainString())
                    + " — price the lines before approving");
        }
        if (subtotalAmount == null || subtotalAmount.compareTo(lineSubtotal) != 0) {
            throw new PoNotApprovableException(id, status,
                "subtotal " + subtotalAmount + " does not equal the sum of line totals "
                    + lineSubtotal.toPlainString());
        }
        if (taxAmount == null || taxAmount.compareTo(lineTax) != 0) {
            throw new PoNotApprovableException(id, status,
                "tax " + taxAmount + " does not equal the sum of line tax " + lineTax.toPlainString());
        }
        BigDecimal expectedTotal = subtotalAmount.add(taxAmount).setScale(2, RoundingMode.HALF_UP);
        if (totalAmount.compareTo(expectedTotal) != 0) {
            throw new PoNotApprovableException(id, status,
                "total " + totalAmount.toPlainString() + " does not equal subtotal + tax "
                    + expectedTotal.toPlainString());
        }
    }

    /**
     * Approve a PO sitting at {@code 'draft'}. Flips to {@code 'sent'} and
     * emits {@link PurchaseOrderApproved}. Rejects with
     * {@link PoNotApprovableException} via {@link #assertApprovable} if the
     * status is anything else, or the header totals are inconsistent / zero.
     */
    public void approve(String approver, String reason) {
        assertApprovable();
        this.status = Status.SENT;
        pendingEvents.add(new PurchaseOrderApproved(
            UUID.randomUUID(),
            id.value(),
            purchaseOrderNumber,
            supplierId,
            currencyCode,
            totalAmount,
            approver,
            reason,
            Instant.now()
        ));
    }

    /**
     * Assert this PO may be rejected. A reject is the manager's "bin this draft"
     * counterpart to {@link #approve} at the draft gate — so it's allowed only
     * while the PO is still {@code 'draft'} (nothing has been sent to the
     * supplier, no downstream commitment to compensate). Throws
     * {@link PoNotRejectableException} from any other status. Pure read.
     */
    public void assertRejectable() {
        if (status != Status.DRAFT) {
            throw new PoNotRejectableException(id, status);
        }
    }

    /**
     * Reject (cancel) a draft PO. Flips {@code 'draft' → 'cancelled'} and emits
     * {@link PurchaseOrderCancelled}. Rejects with {@link PoNotRejectableException}
     * via {@link #assertRejectable} from any non-draft status. The owning service
     * terminates the purchase-to-pay saga in the same transaction.
     */
    public void reject(String cancelledBy, String reason) {
        assertRejectable();
        Status previous = this.status;
        this.status = Status.CANCELLED;
        pendingEvents.add(new PurchaseOrderCancelled(
            UUID.randomUUID(),
            id.value(),
            purchaseOrderNumber,
            supplierId,
            previous.dbValue(),
            cancelledBy,
            reason,
            Instant.now()
        ));
    }

    /** Factory: hydrate from the DB; emits no events. */
    public static PurchaseOrder reconstitute(
        PurchaseOrderId id, String purchaseOrderNumber,
        UUID supplierId, String supplierCode, String supplierName,
        UUID purchaseRequisitionHeaderId,
        String currencyCode,
        BigDecimal subtotalAmount, BigDecimal taxAmount, BigDecimal totalAmount,
        Status status,
        List<PurchaseOrderLine> lines, long version
    ) {
        return new PurchaseOrder(
            id, purchaseOrderNumber,
            supplierId, supplierCode, supplierName,
            purchaseRequisitionHeaderId,
            currencyCode,
            subtotalAmount, taxAmount, totalAmount,
            status,
            new ArrayList<>(lines), version
        );
    }

    private PurchaseOrder(
        PurchaseOrderId id, String purchaseOrderNumber,
        UUID supplierId, String supplierCode, String supplierName,
        UUID purchaseRequisitionHeaderId,
        String currencyCode,
        BigDecimal subtotalAmount, BigDecimal taxAmount, BigDecimal totalAmount,
        Status status,
        List<PurchaseOrderLine> lines, long version
    ) {
        this.id = id;
        this.purchaseOrderNumber = purchaseOrderNumber;
        this.supplierId = supplierId;
        this.supplierCode = supplierCode;
        this.supplierName = supplierName;
        this.purchaseRequisitionHeaderId = purchaseRequisitionHeaderId;
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

    public PurchaseOrderId id()                            { return id; }
    public String purchaseOrderNumber()                    { return purchaseOrderNumber; }
    public UUID supplierId()                               { return supplierId; }
    public String supplierCode()                           { return supplierCode; }
    public String supplierName()                           { return supplierName; }
    public UUID purchaseRequisitionHeaderId()              { return purchaseRequisitionHeaderId; }
    public String currencyCode()                           { return currencyCode; }
    public BigDecimal subtotalAmount()                     { return subtotalAmount; }
    public BigDecimal taxAmount()                          { return taxAmount; }
    public BigDecimal totalAmount()                        { return totalAmount; }
    public Status status()                                 { return status; }
    public List<PurchaseOrderLine> lines()                 { return List.copyOf(lines); }
    public long version()                                  { return version; }

    /** Thrown by {@link #approve} / {@link #assertApprovable} when the PO isn't in {@code 'draft'} status or its header totals are zero / inconsistent. */
    public static final class PoNotApprovableException extends RuntimeException {
        private final PurchaseOrderId orderId;
        private final Status currentStatus;

        public PoNotApprovableException(PurchaseOrderId orderId, Status currentStatus) {
            super("Purchase order " + orderId.value() + " is in status '" + currentStatus.dbValue()
                + "' and cannot be approved (must be 'draft')");
            this.orderId = orderId;
            this.currentStatus = currentStatus;
        }

        /** Consistency / value violation (zero or drifted header totals) with a specific detail. */
        public PoNotApprovableException(PurchaseOrderId orderId, Status currentStatus, String detail) {
            super("Purchase order " + orderId.value() + " cannot be approved: " + detail);
            this.orderId = orderId;
            this.currentStatus = currentStatus;
        }

        public PurchaseOrderId orderId()  { return orderId; }
        public Status currentStatus()     { return currentStatus; }
    }

    /** Thrown by {@link #reject} / {@link #assertRejectable} when the PO isn't in {@code 'draft'} status. */
    public static final class PoNotRejectableException extends RuntimeException {
        private final PurchaseOrderId orderId;
        private final Status currentStatus;

        public PoNotRejectableException(PurchaseOrderId orderId, Status currentStatus) {
            super("Purchase order " + orderId.value() + " is in status '" + currentStatus.dbValue()
                + "' and cannot be rejected (only a 'draft' PO can be rejected)");
            this.orderId = orderId;
            this.currentStatus = currentStatus;
        }

        public PurchaseOrderId orderId()  { return orderId; }
        public Status currentStatus()     { return currentStatus; }
    }
}
