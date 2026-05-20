package com.northwood.finance.domain;

import com.northwood.finance.domain.events.SupplierInvoiceApproved;
import com.northwood.finance.domain.events.SupplierInvoiceRejected;
import com.northwood.shared.domain.Currencies;
import com.northwood.shared.domain.DomainEvent;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
     * allocate. Other values are schema-prep for future workflow extensions.
     */
    public enum Status {
        /** Schema-prep — not currently produced by Java or trigger. */
        DRAFT("draft"),
        /** Schema-prep — not currently produced by Java or trigger. */
        THREE_WAY_MATCH_PENDING("three_way_match_pending"),
        /** Schema-prep — not currently produced by Java or trigger. */
        THREE_WAY_MATCH_PASSED("three_way_match_passed"),
        THREE_WAY_MATCH_FAILED("three_way_match_failed"),
        APPROVED("approved"),
        /** Schema-prep — not currently produced by Java or trigger. */
        POSTED("posted"),
        PARTIALLY_PAID("partially_paid"),
        PAID("paid"),
        /** Schema-prep — not currently produced by Java or trigger. */
        ON_HOLD("on_hold"),
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
            throw new IllegalArgumentException("Unknown supplier_invoice status: " + value);
        }
    }

    /**
     * Three-way match outcome (separate field from {@link Status}). Mirrors
     * the schema CHECK on {@code finance.supplier_invoice_header.match_status}.
     * Java writes MATCHED / VARIANCE / FAILED via {@code record()};
     * {@code NOT_MATCHED} is the DB DEFAULT for hand-inserted rows that
     * haven't yet run match.
     */
    public enum MatchStatus {
        /** Schema-prep — not currently produced by Java; DB DEFAULT for hand-inserted rows. */
        NOT_MATCHED("not_matched"),
        MATCHED("matched"),
        VARIANCE("variance"),
        FAILED("failed");

        private final String dbValue;

        MatchStatus(String dbValue) {
            this.dbValue = dbValue;
        }

        public String dbValue() {
            return dbValue;
        }

        public static MatchStatus fromDb(String value) {
            for (MatchStatus s : values()) {
                if (s.dbValue.equals(value)) return s;
            }
            throw new IllegalArgumentException("Unknown supplier_invoice match_status: " + value);
        }
    }

    private final SupplierInvoiceId id;
    private final String internalInvoiceNumber;
    private final String supplierInvoiceNumber;
    private final UUID purchaseOrderHeaderId;
    private final UUID goodsReceiptHeaderId;
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
        UUID goodsReceiptHeaderId,
        UUID supplierId,
        String supplierCode,
        String supplierName,
        String currencyCode,
        List<SupplierInvoiceLine> lines,
        MatchStatus matchOutcome
    ) {
        Objects.requireNonNull(purchaseOrderHeaderId, "purchaseOrderHeaderId");
        Objects.requireNonNull(supplierId, "supplierId");
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("at least one line is required");
        }

        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal tax = BigDecimal.ZERO;
        for (SupplierInvoiceLine l : lines) {
            subtotal = subtotal.add(l.lineTotal());
            tax = tax.add(l.taxAmount() == null ? BigDecimal.ZERO : l.taxAmount());
        }
        subtotal = subtotal.setScale(2, RoundingMode.HALF_UP);
        tax = tax.setScale(2, RoundingMode.HALF_UP);
        BigDecimal total = subtotal.add(tax);

        Objects.requireNonNull(matchOutcome, "matchOutcome");
        boolean matched = matchOutcome == MatchStatus.MATCHED;
        Status status = matched ? Status.APPROVED : Status.THREE_WAY_MATCH_FAILED;
        MatchStatus matchStatus = matchOutcome;

        SupplierInvoiceId id = SupplierInvoiceId.newId();
        SupplierInvoice si = new SupplierInvoice(
            id,
            internalInvoiceNumber,
            Objects.requireNonNull(supplierInvoiceNumber),
            purchaseOrderHeaderId, goodsReceiptHeaderId,
            supplierId, supplierCode, supplierName,
            Currencies.orBase(currencyCode),
            subtotal, tax, total,
            status, matchStatus,
            new ArrayList<>(lines),
            0L
        );
        if (matched) {
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
        UUID purchaseOrderHeaderId, UUID goodsReceiptHeaderId,
        UUID supplierId, String supplierCode, String supplierName,
        String currencyCode,
        BigDecimal subtotalAmount, BigDecimal taxAmount, BigDecimal totalAmount,
        Status status, MatchStatus matchStatus,
        List<SupplierInvoiceLine> lines, long version
    ) {
        return new SupplierInvoice(
            id, internalInvoiceNumber, supplierInvoiceNumber,
            purchaseOrderHeaderId, goodsReceiptHeaderId,
            supplierId, supplierCode, supplierName,
            currencyCode,
            subtotalAmount, taxAmount, totalAmount,
            status, matchStatus,
            new ArrayList<>(lines), version
        );
    }

    private SupplierInvoice(
        SupplierInvoiceId id, String internalInvoiceNumber, String supplierInvoiceNumber,
        UUID purchaseOrderHeaderId, UUID goodsReceiptHeaderId,
        UUID supplierId, String supplierCode, String supplierName,
        String currencyCode,
        BigDecimal subtotalAmount, BigDecimal taxAmount, BigDecimal totalAmount,
        Status status, MatchStatus matchStatus,
        List<SupplierInvoiceLine> lines, long version
    ) {
        this.id = id;
        this.internalInvoiceNumber = internalInvoiceNumber;
        this.supplierInvoiceNumber = supplierInvoiceNumber;
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
     * Manually approve an invoice that's parked at
     * {@code 'three_way_match_failed'}. The reviewer has decided the
     * variance is acceptable (e.g. agreed price tolerance) and overrides
     * the automated reject. Emits {@link SupplierInvoiceApproved} so the
     * existing P2P saga consumer advances unchanged.
     *
     * <p>Strict precondition: only allowed from
     * {@code three_way_match_failed}. Manually approving an already-approved
     * (or paid, or cancelled) invoice is rejected.
     */
    public void manualApprove(String reason) {
        if (status != Status.THREE_WAY_MATCH_FAILED) {
            throw new IllegalStateException(
                "Cannot manually approve invoice " + id.value()
                    + " in status=" + status.dbValue() + " (must be " + Status.THREE_WAY_MATCH_FAILED.dbValue() + ")"
            );
        }
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
        if (status != Status.THREE_WAY_MATCH_FAILED) {
            throw new IllegalStateException(
                "Cannot manually reject invoice " + id.value()
                    + " in status=" + status.dbValue() + " (must be " + Status.THREE_WAY_MATCH_FAILED.dbValue() + ")"
            );
        }
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
    public UUID goodsReceiptHeaderId()             { return goodsReceiptHeaderId; }
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
