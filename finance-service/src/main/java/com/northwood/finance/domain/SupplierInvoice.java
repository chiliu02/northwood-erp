package com.northwood.finance.domain;

import com.northwood.finance.domain.events.SupplierInvoiceApproved;
import com.northwood.finance.domain.events.SupplierInvoiceRejected;
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
 * Aggregate root for a supplier invoice: header + lines. Phase 4 supports
 * one creation path: {@link #record}, which records all lines against a
 * specific PO + receipt and lets the application service decide on 3-way
 * match. The match outcome flips status either to {@code 'approved'} (full
 * match passes — emits {@link SupplierInvoiceApproved}) or
 * {@code 'three_way_match_failed'} (held for manual review).
 *
 * <p>Phase 4 simplifications: match is quantity-only since PO unit_price is
 * still 0 (Phase 2 follow-up); journal posting is deferred to a later slice
 * once {@code CurrencyConverter} + GL posting land.
 */
public final class SupplierInvoice {

    /**
     * Wire-format aggregate-type stamped onto {@code finance.outbox_message.aggregate_type}
     * for events this aggregate emits.
     */
    public static final String AGGREGATE_TYPE = FinanceAggregateTypes.SUPPLIER_INVOICE;

    /**
     * Supplier-invoice lifecycle status. Mirrors the schema CHECK on
     * {@code finance.supplier_invoice_header.status}. The aggregate is
     * <em>hybrid</em>: {@code record()} + {@code manualApprove()} +
     * {@code manualReject()} write APPROVED / THREE_WAY_MATCH_FAILED /
     * CANCELLED in Java; the {@code maintain_allocation_totals} DB trigger
     * subsequently flips to PARTIALLY_PAID / PAID as supplier payments
     * allocate.
     */
    public enum Status {
        THREE_WAY_MATCH_FAILED("three_way_match_failed"),
        APPROVED("approved"),
        PARTIALLY_PAID("partially_paid"),
        PAID("paid"),
        CANCELLED("cancelled");

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
            throw Assert.unknownValue("supplier_invoice status", value);
        }
    }

    /**
     * Three-way match outcome (separate field from {@link Status}). Mirrors
     * the schema CHECK on {@code finance.supplier_invoice_header.match_status}.
     * Java writes MATCHED / VARIANCE / FAILED via {@code record()} (the match
     * runs synchronously at record time, so there is no unmatched state).
     */
    public enum MatchStatus {
        MATCHED("matched"),
        VARIANCE("variance"),
        FAILED("failed");

        private final String code;

        MatchStatus(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }

        public static MatchStatus fromCode(String value) {
            for (MatchStatus s : values()) {
                if (s.code.equals(value)) return s;
            }
            throw Assert.unknownValue("supplier_invoice match_status", value);
        }
    }

    private final SupplierInvoiceId id;
    private final String internalInvoiceNumber;
    private final String supplierInvoiceNumber;
    private final UUID purchaseOrderHeaderId;
    private final String purchaseOrderNumber;
    private final UUID goodsReceiptHeaderId;
    private final String goodsReceiptNumber;
    private final UUID supplierId;
    private final String supplierCode;
    private final String supplierName;
    private final String currencyCode;
    private final BigDecimal subtotalAmount;
    private final BigDecimal taxAmount;
    private final BigDecimal totalAmount;
    private Status status;
    private MatchStatus matchStatus;
    private final List<SupplierInvoiceLine> lines;
    private final long version;
    private final List<DomainEvent> pendingEvents = new ArrayList<>();

    /**
     * Factory: record an invoice with a pre-decided match status. The
     * application service runs the 3-way match against
     * {@code purchase_order_line_facts} and decides {@code matched} /
     * {@code variance} / {@code failed} before calling this. On
     * {@code matched} the status is set to {@code 'approved'} and
     * {@link SupplierInvoiceApproved} fires.
     */
    public static SupplierInvoice record(
        String internalInvoiceNumber,
        String supplierInvoiceNumber,
        UUID purchaseOrderHeaderId,
        String purchaseOrderNumber,
        UUID goodsReceiptHeaderId,
        String goodsReceiptNumber,
        UUID supplierId,
        String supplierCode,
        String supplierName,
        String currencyCode,
        List<SupplierInvoiceLine> lines,
        MatchStatus matchOutcome
    ) {
        Assert.notNull(purchaseOrderHeaderId, "purchaseOrderHeaderId");
        Assert.notNull(supplierId, "supplierId");
        Assert.notEmpty(lines, "at least one line is required");

        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal tax = BigDecimal.ZERO;
        for (SupplierInvoiceLine l : lines) {
            subtotal = subtotal.add(l.lineTotal());
            tax = tax.add(l.taxAmount() == null ? BigDecimal.ZERO : l.taxAmount());
        }
        subtotal = subtotal.setScale(2, RoundingMode.HALF_UP);
        tax = tax.setScale(2, RoundingMode.HALF_UP);
        BigDecimal total = subtotal.add(tax);

        Assert.notNull(matchOutcome, "matchOutcome");
        // A zero-value invoice is never auto-approved even if the 3-way match
        // passed: auto-approval emits SupplierInvoiceApproved and posts the GL
        // (Dr GRNI / Cr AP), so a non-positive total would post a junk ledger
        // entry. Route it to manual review instead — manualApprove's
        // assertApprovable then blocks it until the value is corrected. (Header
        // totals are summed from the lines just above, so consistency holds by
        // construction here; only positivity needs guarding on this path.)
        boolean autoApprove = matchOutcome == MatchStatus.MATCHED && total.signum() > 0;
        Status status = autoApprove ? Status.APPROVED : Status.THREE_WAY_MATCH_FAILED;
        MatchStatus matchStatus = matchOutcome;

        SupplierInvoiceId id = SupplierInvoiceId.newId();
        SupplierInvoice si = new SupplierInvoice(
            id,
            internalInvoiceNumber,
            Assert.notNull(supplierInvoiceNumber, "supplierInvoiceNumber"),
            purchaseOrderHeaderId, purchaseOrderNumber, goodsReceiptHeaderId, goodsReceiptNumber,
            supplierId, supplierCode, supplierName,
            Currencies.orBase(currencyCode),
            subtotal, tax, total,
            status, matchStatus,
            new ArrayList<>(lines),
            0L
        );
        if (autoApprove) {
            si.pendingEvents.add(new SupplierInvoiceApproved(
                UUID.randomUUID(),
                id.value(),
                internalInvoiceNumber,
                supplierInvoiceNumber,
                purchaseOrderHeaderId,
                supplierId,
                supplierName,
                si.currencyCode,
                total,
                Instant.now()
            ));
        }
        return si;
    }

    /** Factory: hydrate from the DB; emits no events. */
    public static SupplierInvoice reconstitute(
        SupplierInvoiceId id, String internalInvoiceNumber, String supplierInvoiceNumber,
        UUID purchaseOrderHeaderId, String purchaseOrderNumber,
        UUID goodsReceiptHeaderId, String goodsReceiptNumber,
        UUID supplierId, String supplierCode, String supplierName,
        String currencyCode,
        BigDecimal subtotalAmount, BigDecimal taxAmount, BigDecimal totalAmount,
        Status status, MatchStatus matchStatus,
        List<SupplierInvoiceLine> lines, long version
    ) {
        return new SupplierInvoice(
            id, internalInvoiceNumber, supplierInvoiceNumber,
            purchaseOrderHeaderId, purchaseOrderNumber, goodsReceiptHeaderId, goodsReceiptNumber,
            supplierId, supplierCode, supplierName,
            currencyCode,
            subtotalAmount, taxAmount, totalAmount,
            status, matchStatus,
            new ArrayList<>(lines), version
        );
    }

    private SupplierInvoice(
        SupplierInvoiceId id, String internalInvoiceNumber, String supplierInvoiceNumber,
        UUID purchaseOrderHeaderId, String purchaseOrderNumber,
        UUID goodsReceiptHeaderId, String goodsReceiptNumber,
        UUID supplierId, String supplierCode, String supplierName,
        String currencyCode,
        BigDecimal subtotalAmount, BigDecimal taxAmount, BigDecimal totalAmount,
        Status status, MatchStatus matchStatus,
        List<SupplierInvoiceLine> lines, long version
    ) {
        this.id = id;
        this.internalInvoiceNumber = internalInvoiceNumber;
        this.supplierInvoiceNumber = supplierInvoiceNumber;
        this.purchaseOrderNumber = purchaseOrderNumber;
        this.goodsReceiptNumber = goodsReceiptNumber;
        this.purchaseOrderHeaderId = purchaseOrderHeaderId;
        this.goodsReceiptHeaderId = goodsReceiptHeaderId;
        this.supplierId = supplierId;
        this.supplierCode = supplierCode;
        this.supplierName = supplierName;
        this.currencyCode = currencyCode;
        this.subtotalAmount = subtotalAmount;
        this.taxAmount = taxAmount;
        this.totalAmount = totalAmount;
        this.status = status;
        this.matchStatus = matchStatus;
        this.lines = lines;
        this.version = version;
    }

    /**
     * Assert the invoice's header totals are internally consistent before it is
     * approved into the ledger: positive total, {@code total == subtotal + tax},
     * and {@code subtotal}/{@code tax} equal to the line sums. A reconstituted
     * invoice whose denormalised header has drifted from its lines (or is zero)
     * would otherwise post a bad GL entry (Dr GRNI / Cr AP) on the stored
     * {@code totalAmount} and feed the P2P {@code paid_amount <= total_amount}
     * chain. Pure read — mirrors purchasing's {@code PurchaseOrder.assertApprovable}
     * consistency block (the supplier-side twin).
     */
    public void assertConsistent() {
        BigDecimal lineSubtotal = BigDecimal.ZERO;
        BigDecimal lineTax = BigDecimal.ZERO;
        for (SupplierInvoiceLine l : lines) {
            lineSubtotal = lineSubtotal.add(l.lineTotal());
            lineTax = lineTax.add(l.taxAmount() == null ? BigDecimal.ZERO : l.taxAmount());
        }
        lineSubtotal = lineSubtotal.setScale(2, RoundingMode.HALF_UP);
        lineTax = lineTax.setScale(2, RoundingMode.HALF_UP);
        Assert.state(totalAmount != null && totalAmount.signum() > 0,
            "Supplier invoice " + id.value() + " total amount is "
                + (totalAmount == null ? "unset" : totalAmount.toPlainString())
                + " — cannot approve a zero-value invoice");
        Assert.state(subtotalAmount != null && subtotalAmount.compareTo(lineSubtotal) == 0,
            "Supplier invoice " + id.value() + " subtotal " + subtotalAmount
                + " does not equal the sum of line totals " + lineSubtotal.toPlainString());
        Assert.state(taxAmount != null && taxAmount.compareTo(lineTax) == 0,
            "Supplier invoice " + id.value() + " tax " + taxAmount
                + " does not equal the sum of line tax " + lineTax.toPlainString());
        BigDecimal expectedTotal = subtotalAmount.add(taxAmount).setScale(2, RoundingMode.HALF_UP);
        Assert.state(totalAmount.compareTo(expectedTotal) == 0,
            "Supplier invoice " + id.value() + " total " + totalAmount.toPlainString()
                + " does not equal subtotal + tax " + expectedTotal.toPlainString());
    }

    /**
     * Assert this invoice may be manually approved: status precondition
     * (parked at {@code three_way_match_failed}) AND internal consistency
     * ({@link #assertConsistent}). Pure read — the supplier-side twin of
     * purchasing's {@code PurchaseOrder.assertApprovable}, so the API can
     * fail fast (wrong status OR drifted totals) before any write transaction
     * or GL posting; {@link #manualApprove} calls it as the in-tx backstop.
     */
    public void assertApprovable() {
        Assert.state(status == Status.THREE_WAY_MATCH_FAILED, "Cannot manually approve invoice " + id.value()
                    + " in status=" + status.code() + " (must be " + Status.THREE_WAY_MATCH_FAILED.code() + ")");
        assertConsistent();
    }

    /**
     * Manually approve an invoice that's parked at
     * {@code 'three_way_match_failed'}. The reviewer has decided the
     * variance is acceptable (e.g. agreed price tolerance) and overrides
     * the automated reject. Validated by {@link #assertApprovable} (status
     * precondition + header/line consistency); emits {@link SupplierInvoiceApproved}
     * so the existing P2P saga consumer advances unchanged. Approving an
     * already-approved/paid/cancelled or inconsistent invoice is rejected.
     */
    public void manualApprove(String reason) {
        assertApprovable();
        this.status = Status.APPROVED;
        this.matchStatus = MatchStatus.MATCHED;
        pendingEvents.add(new SupplierInvoiceApproved(
            UUID.randomUUID(),
            id.value(),
            internalInvoiceNumber,
            supplierInvoiceNumber,
            purchaseOrderHeaderId,
            supplierId,
            supplierName,
            currencyCode,
            totalAmount,
            Instant.now()
        ));
    }

    /**
     * Manually reject an invoice parked at
     * {@code 'three_way_match_failed'}. Reviewer rejects the invoice
     * outright (e.g. supplier sent the wrong invoice; goods don't match).
     * Status flips to {@code 'cancelled'} (terminal). Emits
     * {@link SupplierInvoiceRejected} so purchasing's P2P saga consumer
     * lands the saga in {@code failed}; without this event the saga
     * would otherwise park at {@code goods_received} forever.
     */
    public void manualReject(String reason) {
        Assert.state(status == Status.THREE_WAY_MATCH_FAILED, "Cannot manually reject invoice " + id.value()
                    + " in status=" + status.code() + " (must be " + Status.THREE_WAY_MATCH_FAILED.code() + ")");
        this.status = Status.CANCELLED;
        pendingEvents.add(new SupplierInvoiceRejected(
            UUID.randomUUID(),
            id.value(),
            internalInvoiceNumber,
            supplierInvoiceNumber,
            purchaseOrderHeaderId,
            supplierId,
            supplierName,
            reason,
            Instant.now()
        ));
    }

    public List<DomainEvent> pullPendingEvents() {
        List<DomainEvent> out = List.copyOf(pendingEvents);
        pendingEvents.clear();
        return out;
    }

    public SupplierInvoiceId id()                  { return id; }
    public String internalInvoiceNumber()          { return internalInvoiceNumber; }
    public String supplierInvoiceNumber()          { return supplierInvoiceNumber; }
    public UUID purchaseOrderHeaderId()            { return purchaseOrderHeaderId; }
    public String purchaseOrderNumber()            { return purchaseOrderNumber; }
    public UUID goodsReceiptHeaderId()             { return goodsReceiptHeaderId; }
    public String goodsReceiptNumber()             { return goodsReceiptNumber; }
    public UUID supplierId()                       { return supplierId; }
    public String supplierCode()                   { return supplierCode; }
    public String supplierName()                   { return supplierName; }
    public String currencyCode()                   { return currencyCode; }
    public BigDecimal subtotalAmount()             { return subtotalAmount; }
    public BigDecimal taxAmount()                  { return taxAmount; }
    public BigDecimal totalAmount()                { return totalAmount; }
    public Status status()                         { return status; }
    public MatchStatus matchStatus()               { return matchStatus; }
    public List<SupplierInvoiceLine> lines()       { return List.copyOf(lines); }
    public long version()                          { return version; }
}
