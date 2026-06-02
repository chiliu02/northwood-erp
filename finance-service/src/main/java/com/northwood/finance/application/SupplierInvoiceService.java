package com.northwood.finance.application;

import com.northwood.finance.application.dto.RecordSupplierInvoiceCommand;
import com.northwood.finance.application.dto.SupplierInvoiceView;
import com.northwood.finance.application.inbox.PurchaseOrderLineFactsProjection;
import com.northwood.finance.application.inbox.PurchaseOrderLineFactsProjection.LineFacts;
import com.northwood.finance.domain.SupplierInvoice;
import com.northwood.finance.domain.SupplierInvoiceId;
import com.northwood.finance.domain.SupplierInvoiceLine;
import com.northwood.finance.domain.SupplierInvoiceRepository;
import com.northwood.shared.domain.LineNumbering;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for supplier invoices. Phase 4 supports one use case:
 * {@link #recordInvoice}, which records a supplier invoice and runs a
 * quantity-only 3-way match against {@code finance.purchase_order_line_facts}.
 *
 * <p>Match algorithm (per line):
 * <ul>
 *   <li>Locate the {@code po_line_facts} row by {@code purchase_order_line_id}.
 *       If missing → {@code failed} (PO event hasn't propagated yet, or the
 *       caller passed a bogus line id).</li>
 *   <li>Aggregate invoice quantities by {@code purchase_order_line_id} (an
 *       invoice may have multiple lines pointing at one PO line if e.g. the
 *       receipt arrived in two batches). The cumulative invoiced quantity
 *       (existing {@code invoiced_quantity} + this invoice's contribution)
 *       must not exceed {@code received_quantity}.</li>
 *   <li>If every line matches → {@code matched} → invoice goes
 *       {@code 'approved'}, emits {@code SupplierInvoiceApproved}, bumps the
 *       projection's {@code invoiced_quantity}.</li>
 *   <li>Otherwise → {@code failed} → invoice goes
 *       {@code 'three_way_match_failed'} (held for manual review; no event).</li>
 * </ul>
 *
 * <p><b>Price-variance check (shipped 2026-05-06):</b> per line, compare
 * the invoice's {@code unitPrice} against the PO line's {@code unitPrice} via
 * {@code abs(invoiceUnit - poUnit) / poUnit}. If the relative variance exceeds
 * {@code northwood.finance.match.priceTolerancePercent} (default 2.0%), match
 * fails and the invoice parks at {@code 'three_way_match_failed'} for manual
 * review. A zero PO unit_price (e.g. seeded data) is treated as "no price to
 * match against" and skipped — quantity-only check still runs.
 */
@Service
public class SupplierInvoiceService {

    private static final Logger log = LoggerFactory.getLogger(SupplierInvoiceService.class);

    private final SupplierInvoiceRepository supplierInvoices;
    private final PurchaseOrderLineFactsProjection purchaseOrderLineFacts;
    private final JournalEntryService journals;
    private final BigDecimal priceTolerancePercent;

    public SupplierInvoiceService(
        SupplierInvoiceRepository supplierInvoices,
        PurchaseOrderLineFactsProjection purchaseOrderLineFacts,
        JournalEntryService journals,
        @Value("${northwood.finance.match.priceTolerancePercent:2.0}") BigDecimal priceTolerancePercent
    ) {
        this.supplierInvoices = supplierInvoices;
        this.purchaseOrderLineFacts = purchaseOrderLineFacts;
        this.journals = journals;
        this.priceTolerancePercent = priceTolerancePercent;
    }

    @Transactional
    public SupplierInvoiceView recordInvoice(RecordSupplierInvoiceCommand command) {
        // Aggregate invoiced quantity per PO line so we can check cumulative
        // against received_quantity before we trust each line individually.
        Map<UUID, BigDecimal> invoicedByPoLine = new LinkedHashMap<>();
        for (RecordSupplierInvoiceCommand.Line line : command.lines()) {
            invoicedByPoLine.merge(
                line.purchaseOrderLineId(),
                line.quantity() == null ? BigDecimal.ZERO : line.quantity(),
                BigDecimal::add
            );
        }

        SupplierInvoice.MatchStatus matchOutcome = decideMatchOutcome(
            command.lines(), invoicedByPoLine, command.purchaseOrderHeaderId()
        );

        List<SupplierInvoiceLine> lines = new ArrayList<>();
        int lineNumber = LineNumbering.START;
        for (RecordSupplierInvoiceCommand.Line line : command.lines()) {
            BigDecimal qty = line.quantity();
            BigDecimal unit = line.unitPrice() == null ? BigDecimal.ZERO : line.unitPrice();
            BigDecimal taxRate = line.taxRate() == null ? BigDecimal.ZERO : line.taxRate();
            BigDecimal lineSubtotal = qty.multiply(unit).setScale(2, RoundingMode.HALF_UP);
            BigDecimal lineTax = lineSubtotal.multiply(taxRate).setScale(2, RoundingMode.HALF_UP);
            lines.add(new SupplierInvoiceLine(
                UUID.randomUUID(), lineNumber,
                line.purchaseOrderLineId(),
                line.goodsReceiptLineId(),
                line.productId(), line.productSku(), line.productName(),
                qty, unit,
                taxRate, lineTax,
                lineSubtotal
            ));
            lineNumber += LineNumbering.STEP;
        }

        SupplierInvoice invoice = SupplierInvoice.record(
            command.internalInvoiceNumber(),
            command.supplierInvoiceNumber(),
            command.purchaseOrderHeaderId(),
            command.goodsReceiptHeaderId(),
            command.supplierId(),
            command.supplierCode(),
            command.supplierName(),
            command.currencyCode(),
            lines,
            matchOutcome
        );
        supplierInvoices.save(invoice);

        if (matchOutcome == SupplierInvoice.MatchStatus.MATCHED) {
            // Bump invoiced_quantity on the projection so a subsequent invoice
            // for the same PO sees the cumulative figure.
            invoicedByPoLine.forEach(purchaseOrderLineFacts::bumpInvoiced);

            // Phase 5b: post the GL pair (Dr COGS, Cr AP) in the same txn.
            journals.postSupplierInvoiceApproval(
                invoice.id().value(),
                invoice.supplierName(),
                invoice.supplierInvoiceNumber(),
                invoice.totalAmount(),
                invoice.currencyCode(),
                java.time.LocalDate.now()
            );
        }

        log.info("recorded supplier invoice {} for purchase_order={} → status={} (match={})",
            invoice.internalInvoiceNumber(), command.purchaseOrderHeaderId(),
            invoice.status().dbValue(), invoice.matchStatus().dbValue());
        return SupplierInvoiceView.from(invoice);
    }

    private SupplierInvoice.MatchStatus decideMatchOutcome(
        List<RecordSupplierInvoiceCommand.Line> invoiceLines,
        Map<UUID, BigDecimal> invoicedByPoLine,
        UUID expectedPoHeaderId
    ) {
        // Per-invoice-line: price variance vs PO line's snapshotted unit_price.
        // (Quantity is checked aggregated below since multiple invoice lines
        // can point at one PO line; price is per-line because the invoiced
        // unit price is what we're verifying.)
        for (RecordSupplierInvoiceCommand.Line line : invoiceLines) {
            UUID poLineId = line.purchaseOrderLineId();
            if (poLineId == null) continue;  // caught in the cumulative pass below
            LineFacts facts = purchaseOrderLineFacts.findByLineId(poLineId);
            if (facts == null) continue;     // caught in the cumulative pass below
            if (priceVariesOutsideTolerance(line.unitPrice(), facts.unitPrice())) {
                log.warn("invoice line price {} varies from PO line price {} by more than {}% for po_line={}",
                    line.unitPrice(), facts.unitPrice(), priceTolerancePercent, poLineId);
                return SupplierInvoice.MatchStatus.FAILED;
            }
        }

        // Per-PO-line aggregated: cumulative invoiced quantity must not exceed
        // received quantity. Also checks line existence + PO header consistency.
        for (Map.Entry<UUID, BigDecimal> e : invoicedByPoLine.entrySet()) {
            UUID poLineId = e.getKey();
            BigDecimal toInvoice = e.getValue();
            if (poLineId == null) {
                log.warn("invoice line missing purchase_order_line_id; cannot 3-way match");
                return SupplierInvoice.MatchStatus.FAILED;
            }
            LineFacts facts = purchaseOrderLineFacts.findByLineId(poLineId);
            if (facts == null) {
                log.warn("no po_line_facts for purchase_order_line_id={}; PO event may not have arrived", poLineId);
                return SupplierInvoice.MatchStatus.FAILED;
            }
            if (!facts.purchaseOrderHeaderId().equals(expectedPoHeaderId)) {
                log.warn("invoice claims po_header={} but line {} belongs to po_header={}",
                    expectedPoHeaderId, poLineId, facts.purchaseOrderHeaderId());
                return SupplierInvoice.MatchStatus.FAILED;
            }
            BigDecimal cumulativeAfter = facts.invoicedQuantity().add(toInvoice);
            if (cumulativeAfter.compareTo(facts.receivedQuantity()) > 0) {
                log.warn("invoice line {} would over-invoice: cumulative={} > received={}",
                    poLineId, cumulativeAfter, facts.receivedQuantity());
                return SupplierInvoice.MatchStatus.FAILED;
            }
        }
        return SupplierInvoice.MatchStatus.MATCHED;
    }

    /**
     * True when the invoice's per-unit price differs from the PO line's
     * snapshotted unit_price by more than the configured tolerance. Skips the
     * check (returns false) when either side is missing or the PO unit_price
     * is zero — typically seed data without a price; the quantity check still
     * runs.
     */
    private boolean priceVariesOutsideTolerance(BigDecimal invoiceUnit, BigDecimal poUnit) {
        if (invoiceUnit == null || poUnit == null) return false;
        if (poUnit.signum() == 0) return false;
        BigDecimal variancePercent = invoiceUnit.subtract(poUnit).abs()
            .divide(poUnit, 6, RoundingMode.HALF_UP)
            .multiply(new BigDecimal("100"));
        return variancePercent.compareTo(priceTolerancePercent) > 0;
    }

    /**
     * Manually approve an invoice that's parked at
     * {@code 'three_way_match_failed'}. Emits {@code SupplierInvoiceApproved}
     * (P2P saga consumer reacts unchanged), runs the GL posting (Dr GRNI /
     * Cr AP), and bumps the {@code purchase_order_line_facts} projection
     * with the invoiced quantities so subsequent invoices on the same PO
     * see the cumulative count correctly.
     */
    @Transactional
    public void manualApprove(UUID supplierInvoiceHeaderId, String reviewer, String reason) {
        SupplierInvoice invoice = supplierInvoices.findById(SupplierInvoiceId.of(supplierInvoiceHeaderId))
            .orElseThrow(() -> new IllegalArgumentException("No supplier invoice " + supplierInvoiceHeaderId));
        invoice.manualApprove(reason);
        supplierInvoices.save(invoice);

        // Bump the PO line facts projection's invoiced_quantity. We don't
        // re-aggregate the invoice's lines here — projection logic stays
        // line-by-line for parity with the auto-approve path. The lines
        // were saved when the invoice was first recorded (the PR row).
        for (SupplierInvoiceLine l : invoice.lines()) {
            if (l.purchaseOrderLineId() == null) continue;
            purchaseOrderLineFacts.bumpInvoiced(l.purchaseOrderLineId(), l.quantity());
        }

        journals.postSupplierInvoiceApproval(
            invoice.id().value(),
            invoice.supplierName(),
            invoice.internalInvoiceNumber(),
            invoice.totalAmount(),
            invoice.currencyCode(),
            java.time.LocalDate.now()
        );

        log.info(
            "manually approved supplier invoice {} (id={}) reviewer={} reason={}",
            invoice.internalInvoiceNumber(), supplierInvoiceHeaderId, reviewer, reason
        );
    }

    /**
     * Manually reject an invoice parked at {@code 'three_way_match_failed'}.
     * Status flips to {@code 'cancelled'}; no event, no GL movement, no
     * projection update — the invoice never approached approval.
     */
    @Transactional
    public void manualReject(UUID supplierInvoiceHeaderId, String reviewer, String reason) {
        SupplierInvoice invoice = supplierInvoices.findById(SupplierInvoiceId.of(supplierInvoiceHeaderId))
            .orElseThrow(() -> new IllegalArgumentException("No supplier invoice " + supplierInvoiceHeaderId));
        invoice.manualReject(reason);
        supplierInvoices.save(invoice);
        log.info(
            "manually rejected supplier invoice {} (id={}) reviewer={} reason={}",
            invoice.internalInvoiceNumber(), supplierInvoiceHeaderId, reviewer, reason
        );
    }

    /** List supplier invoices currently parked at three_way_match_failed. */
    @Transactional(readOnly = true)
    public List<SupplierInvoiceView> findPendingReview() {
        return supplierInvoices.findByStatus(SupplierInvoice.Status.THREE_WAY_MATCH_FAILED).stream()
            .map(SupplierInvoiceView::from)
            .toList();
    }

    @Transactional(readOnly = true)
    public List<SupplierInvoiceView> findAll() {
        return supplierInvoices.findAll().stream().map(SupplierInvoiceView::from).toList();
    }

    @Transactional(readOnly = true)
    public Optional<SupplierInvoiceView> findById(UUID supplierInvoiceHeaderId) {
        return supplierInvoices.findById(SupplierInvoiceId.of(supplierInvoiceHeaderId))
            .map(SupplierInvoiceView::from);
    }

}
