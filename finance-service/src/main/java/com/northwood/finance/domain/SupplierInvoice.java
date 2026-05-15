package com.northwood.finance.domain;

import com.northwood.finance.domain.events.SupplierInvoiceApproved;
import com.northwood.finance.domain.events.SupplierInvoiceRejected;
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

    // ------------------------------------------------------------
    // Status constants — wire-format strings stored in
    // finance.supplier_invoice_header.status. The DB CHECK constraint and
    // event payloads are the canonical form; these are the Java-side
    // ergonomic mirroring the saga-state-constant pattern.
    // ------------------------------------------------------------
    public static final String APPROVED = "approved";
    public static final String THREE_WAY_MATCH_FAILED = "three_way_match_failed";
    public static final String PARTIALLY_PAID = "partially_paid";
    public static final String PAID = "paid";
    public static final String CANCELLED = "cancelled";

    /** Match status (separate field from status). */
    public static final String MATCH_MATCHED = "matched";
    public static final String MATCH_VARIANCE = "variance";
    public static final String MATCH_FAILED = "failed";

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
    private String status;
    private String matchStatus;
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
        String matchOutcome
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

        boolean matched = MATCH_MATCHED.equals(matchOutcome);
        String status = matched ? APPROVED : THREE_WAY_MATCH_FAILED;
        String matchStatus = matched ? MATCH_MATCHED : matchOutcome;

        SupplierInvoiceId id = SupplierInvoiceId.newId();
        SupplierInvoice si = new SupplierInvoice(
            id,
            internalInvoiceNumber,
            Objects.requireNonNull(supplierInvoiceNumber),
            purchaseOrderHeaderId, goodsReceiptHeaderId,
            supplierId, supplierCode, supplierName,
            currencyCode == null ? "AUD" : currencyCode,
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
        String status, String matchStatus,
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
        String status, String matchStatus,
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
        if (!THREE_WAY_MATCH_FAILED.equals(status)) {
            throw new IllegalStateException(
                "Cannot manually approve invoice " + id.value()
                    + " in status=" + status + " (must be " + THREE_WAY_MATCH_FAILED + ")"
            );
        }
        this.status = APPROVED;
        this.matchStatus = "matched";
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
        if (!THREE_WAY_MATCH_FAILED.equals(status)) {
            throw new IllegalStateException(
                "Cannot manually reject invoice " + id.value()
                    + " in status=" + status + " (must be " + THREE_WAY_MATCH_FAILED + ")"
            );
        }
        this.status = CANCELLED;
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
    public String status()                         { return status; }
    public String matchStatus()                    { return matchStatus; }
    public List<SupplierInvoiceLine> lines()       { return List.copyOf(lines); }
    public long version()                          { return version; }
}
