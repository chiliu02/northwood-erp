package com.northwood.purchasing.application;

import com.northwood.product.domain.ApprovedVendor;
import com.northwood.purchasing.application.dto.PurchaseOrderView;
import com.northwood.purchasing.application.saga.PurchaseToPaySagaManager;
import com.northwood.purchasing.domain.PurchaseOrder;
import com.northwood.purchasing.domain.PurchaseOrderId;
import com.northwood.purchasing.domain.PurchaseOrderLine;
import com.northwood.purchasing.domain.PurchaseOrderRepository;
import com.northwood.purchasing.domain.PurchaseRequisition;
import com.northwood.purchasing.domain.PurchaseRequisitionId;
import com.northwood.purchasing.domain.PurchaseRequisitionLine;
import com.northwood.purchasing.domain.PurchaseRequisitionRepository;
import com.northwood.purchasing.domain.Supplier;
import com.northwood.purchasing.domain.SupplierId;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for purchase orders. Phase 2 supports a single use case:
 * {@link #convertFromRequisition} — auto-conversion of an approved requisition
 * to a sent purchase order. Manual creation, supplier selection, and
 * approval workflows arrive in later phases.
 *
 * <p>The conversion runs in one transaction with three writes:
 * <ol>
 *   <li>Insert the PurchaseOrder (emits {@code PurchaseOrderCreated} to the outbox).</li>
 *   <li>Mark the source PurchaseRequisition as {@code converted}.</li>
 *   <li>Insert a {@code purchase_to_pay_saga} row at {@code started}.</li>
 * </ol>
 *
 * <p>PO line {@code unit_price} comes from the supplier price list
 * ({@link SupplierProductPriceLookup}). Lines whose (supplier, product,
 * currency) tuple isn't on the list fall back to {@code 0} — surfaced in
 * the log so the missing-price entry can be added.
 */
@Service
public class PurchaseOrderService {

    /**
     * Application-layer wrapper around the domain
     * {@link PurchaseOrder.PoNotApprovableException}. Controllers catch this
     * (HTTP 409) instead of importing the domain exception type directly.
     */
    public static class PoNotApprovableException extends RuntimeException {
        public PoNotApprovableException(Throwable cause) {
            super(cause.getMessage(), cause);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(PurchaseOrderService.class);
    private static final String DEFAULT_CURRENCY = "AUD";

    private final PurchaseOrderRepository purchaseOrders;
    private final PurchaseRequisitionRepository purchaseRequisitions;
    private final SupplierQueryPort suppliers;
    private final PurchaseToPaySagaManager sagaManager;
    private final SupplierProductPriceLookup priceList;
    private final ApprovedVendorQueryPort approvedVendors;

    public PurchaseOrderService(
        PurchaseOrderRepository purchaseOrders,
        PurchaseRequisitionRepository purchaseRequisitions,
        SupplierQueryPort suppliers,
        PurchaseToPaySagaManager sagaManager,
        SupplierProductPriceLookup priceList,
        ApprovedVendorQueryPort approvedVendors
    ) {
        this.purchaseOrders = purchaseOrders;
        this.purchaseRequisitions = purchaseRequisitions;
        this.suppliers = suppliers;
        this.sagaManager = sagaManager;
        this.priceList = priceList;
        this.approvedVendors = approvedVendors;
    }

    /**
     * Convert an approved requisition to a purchase order with the
     * caller-supplied {@code autoApprove} policy:
     * <ul>
     *   <li>{@code autoApprove=true} — PO lands at {@code 'sent'} and emits
     *       both {@code PurchaseOrderCreated} and {@code PurchaseOrderApproved}
     *       in one txn (shortage-driven flow's default behaviour).</li>
     *   <li>{@code autoApprove=false} — PO lands at {@code 'draft'} and waits
     *       for {@link #approve} (manual PR flow always uses this).</li>
     * </ul>
     */
    @Transactional
    public PurchaseOrderId convertFromRequisition(PurchaseRequisitionId prId, boolean autoApprove) {
        PurchaseRequisition pr = purchaseRequisitions.findById(prId)
            .orElseThrow(() -> new IllegalArgumentException("No requisition " + prId.value()));
        if (pr.status() == PurchaseRequisition.Status.CONVERTED) {
            log.debug("requisition {} already converted; idempotent skip", prId.value());
            return null;
        }
        if (pr.status() != PurchaseRequisition.Status.APPROVED) {
            throw new IllegalStateException(
                "Cannot convert requisition " + prId.value() + " from status=" + pr.status().dbValue()
            );
        }

        Supplier supplier = pickSupplier(pr);
        List<PurchaseOrderLine> lines = buildLines(supplier.id(), pr.lines());

        PurchaseOrder po = PurchaseOrder.fromRequisition(
            PurchaseOrder.NUMBER_PREFIX + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
            supplier,
            pr.id().value(),
            pr.sourceWorkOrderId(),
            DEFAULT_CURRENCY,
            lines,
            autoApprove
        );
        purchaseOrders.save(po);

        pr.markConverted();
        purchaseRequisitions.save(pr);

        // Insert saga at 'started'. Auto-approve: immediately advance inline
        // to 'purchase_order_approved' so the worker's next tick takes it to
        // 'waiting_for_goods'. Manual flow: saga sits at 'started' until a
        // human calls approve().
        sagaManager.insertStarted(po.id().value());
        if (autoApprove) {
            sagaManager.approve(po.id().value());
        }

        log.info("converted requisition {} → purchase_order {} (status={}, supplier={}, {} line(s))",
            pr.requisitionNumber(), po.purchaseOrderNumber(), po.status().dbValue(),
            supplier.supplierCode(), lines.size());
        return po.id();
    }

    @Transactional(readOnly = true)
    public Optional<PurchaseOrderView> findById(UUID purchaseOrderHeaderId) {
        return purchaseOrders.findById(PurchaseOrderId.of(purchaseOrderHeaderId))
            .map(PurchaseOrderView::from);
    }

    /**
     * Approve a PO sitting at {@code 'draft'}. Flips the PO to {@code 'sent'}
     * and emits {@code purchasing.PurchaseOrderApproved}; the saga worker
     * advances {@code purchase_order_approved → waiting_for_goods} on its
     * next tick.
     */
    @Transactional
    public void approve(UUID purchaseOrderHeaderId, String approver, String reason) {
        PurchaseOrderId poId = PurchaseOrderId.of(purchaseOrderHeaderId);
        PurchaseOrder po = purchaseOrders.findById(poId)
            .orElseThrow(() -> new IllegalArgumentException("No purchase order " + purchaseOrderHeaderId));
        try {
            po.approve(approver, reason);
        } catch (PurchaseOrder.PoNotApprovableException e) {
            throw new PoNotApprovableException(e);
        }
        purchaseOrders.save(po);

        sagaManager.approve(purchaseOrderHeaderId);

        log.info("approved purchase_order {} (id={}) approver={} reason={}",
            po.purchaseOrderNumber(), purchaseOrderHeaderId, approver, reason);
    }

    /**
     * Supplier selection. The Shape A approved-vendor projection
     * ({@code purchasing.product_approved_vendor}) is the engineering
     * quality gate: only suppliers on the approved list for every line's
     * product are eligible. Within the eligible set we prefer entries
     * marked {@code is_preferred=true}; ties broken by supplier code.
     *
     * <p>Fallback chain:
     * <ol>
     *   <li>Suppliers approved for ALL lines (intersection across products),
     *       preferring the {@code is_preferred} ones.</li>
     *   <li>If no shared approved vendor: the line's
     *       {@code suggested_supplier_id} (set when the requisition was
     *       created).</li>
     *   <li>If no suggestion either: the global default supplier.</li>
     * </ol>
     *
     * <p>Multi-supplier-per-requisition (split into one PO per supplier)
     * is still a future slice — for now a multi-line PR with no shared
     * approved vendor falls through to the suggested/default path.
     */
    private Supplier pickSupplier(PurchaseRequisition pr) {
        Optional<Supplier> approved = pickFromApprovedVendors(pr.lines());
        if (approved.isPresent()) {
            return approved.get();
        }
        for (PurchaseRequisitionLine l : pr.lines()) {
            if (l.suggestedSupplierId() != null) {
                return suppliers.findById(SupplierId.of(l.suggestedSupplierId()))
                    .orElseGet(suppliers::defaultSupplier);
            }
        }
        return suppliers.defaultSupplier();
    }

    private Optional<Supplier> pickFromApprovedVendors(List<PurchaseRequisitionLine> lines) {
        if (lines.isEmpty()) return Optional.empty();
        // Intersect approved-vendor sets across all lines.
        Set<UUID> intersection = null;
        Set<UUID> preferredAcrossAll = new LinkedHashSet<>();
        boolean firstLine = true;
        for (PurchaseRequisitionLine l : lines) {
            List<ApprovedVendor> approved = approvedVendors.findApprovedFor(l.productId());
            if (approved.isEmpty()) {
                return Optional.empty();   // any line with no approved vendor → no shared eligible supplier
            }
            Set<UUID> thisLine = new LinkedHashSet<>();
            Set<UUID> thisLinePreferred = new LinkedHashSet<>();
            for (ApprovedVendor v : approved) {
                thisLine.add(v.supplierId());
                if (v.preferred()) {
                    thisLinePreferred.add(v.supplierId());
                }
            }
            if (firstLine) {
                intersection = thisLine;
                preferredAcrossAll.addAll(thisLinePreferred);
                firstLine = false;
            } else {
                intersection.retainAll(thisLine);
                preferredAcrossAll.retainAll(thisLinePreferred);
            }
            if (intersection.isEmpty()) {
                return Optional.empty();
            }
        }
        // Within the eligible set, prefer the cheapest. Total-line-cost
        // across all lines as the comparison. Fallback chain inside the
        // eligible set:
        //   1. preferred-for-every-line vendor with the cheapest total
        //   2. any eligible vendor with the cheapest total
        //   3. any eligible vendor (in case a price-list lookup is missing)
        Optional<Supplier> cheapestPreferred = pickCheapest(preferredAcrossAll, lines);
        if (cheapestPreferred.isPresent()) return cheapestPreferred;
        Optional<Supplier> cheapestEligible = pickCheapest(intersection, lines);
        if (cheapestEligible.isPresent()) return cheapestEligible;
        // Last resort: any eligible supplier we can hydrate.
        for (UUID supplierId : intersection) {
            Optional<Supplier> s = suppliers.findById(SupplierId.of(supplierId));
            if (s.isPresent()) return s;
        }
        return Optional.empty();
    }

    /**
     * Score eligible suppliers by total quote cost across all PR lines and
     * return the cheapest one we can hydrate. A supplier missing a
     * price-list entry for any line falls out of contention (we can't
     * compute a total without a price). Ties broken arbitrarily by the
     * iteration order of the eligible set.
     */
    private Optional<Supplier> pickCheapest(Set<UUID> eligible, List<PurchaseRequisitionLine> lines) {
        if (eligible == null || eligible.isEmpty()) return Optional.empty();
        UUID best = null;
        BigDecimal bestTotal = null;
        for (UUID supplierId : eligible) {
            BigDecimal total = quoteTotal(SupplierId.of(supplierId), lines);
            if (total == null) continue;  // missing price-list entry → not comparable
            if (bestTotal == null || total.compareTo(bestTotal) < 0) {
                best = supplierId;
                bestTotal = total;
            }
        }
        if (best == null) return Optional.empty();
        return suppliers.findById(SupplierId.of(best));
    }

    /**
     * Sum {@code requestedQuantity × unit_price} across the PR lines for a
     * given supplier. Returns null if any line is missing a price-list
     * entry (no quote computable).
     */
    private BigDecimal quoteTotal(SupplierId supplierId, List<PurchaseRequisitionLine> lines) {
        BigDecimal total = BigDecimal.ZERO;
        for (PurchaseRequisitionLine l : lines) {
            Optional<BigDecimal> price = priceList.findUnitPrice(
                supplierId, l.productId(), DEFAULT_CURRENCY,
                java.time.LocalDate.now(), l.requestedQuantity());
            if (price.isEmpty()) {
                return null;
            }
            total = total.add(l.requestedQuantity().multiply(price.get()));
        }
        return total;
    }

    /**
     * Compose PO lines from a converted PR. Each line's {@code unit_price} is
     * sourced from the supplier price list via tiered lookup
     * ({@code priceList.findUnitPrice} — the requested quantity feeds the
     * tier resolution so a 100-unit ask gets the volume discount tier).
     *
     * <p><b>Silent-fallback contract on missing price-list entry.</b> If no
     * price row exists for the (supplier, product, currency, effective_date)
     * tuple — the supplier hasn't been priced for that product, or the
     * effective-from window doesn't cover today — the unit price falls back
     * to {@code BigDecimal.ZERO} and a WARN log fires naming the supplier +
     * product. The PO line is created with zero unit price, zero line total,
     * and the PO total reflects the sum (likely zero or under-stated).
     * Downstream impact: the supplier receives a PO commitment for a product
     * at zero cost; reporting's PO-tracking view shows a zero {@code ordered_amount};
     * finance's 3-way match will compare invoice price against zero PO price
     * and park the invoice at {@code three_way_match_failed} for manual review
     * (which is the right loud surfacing — a human will catch the missing
     * price). Throwing here is rejected because shortage-driven PR-to-PO
     * conversion is auto-approved by config, and a hard failure mid-saga
     * would block the make-to-order flow waiting for raw materials. The
     * preferred tightening, when supplier price coverage matures, is to
     * reject the PR conversion at validation time rather than emit a
     * zero-price PO.
     */
    private List<PurchaseOrderLine> buildLines(SupplierId supplierId, List<PurchaseRequisitionLine> prLines) {
        List<PurchaseOrderLine> lines = new ArrayList<>();
        for (PurchaseRequisitionLine pl : prLines) {
            // Pass quantity so tiered pricing kicks in — a 100-unit request
            // gets the volume discount tier instead of the base price.
            BigDecimal unitPrice = priceList.findUnitPrice(
                    supplierId, pl.productId(), DEFAULT_CURRENCY,
                    java.time.LocalDate.now(), pl.requestedQuantity())
                .orElseGet(() -> {
                    log.warn("no price-list entry for supplier={} product={} ({}); falling back to 0",
                        supplierId.value(), pl.productSku(), pl.productId());
                    return BigDecimal.ZERO;
                });
            BigDecimal lineTotal = pl.requestedQuantity().multiply(unitPrice);
            lines.add(new PurchaseOrderLine(
                UUID.randomUUID(),
                pl.lineNumber(),
                pl.id(),
                pl.productId(),
                pl.productSku(),
                pl.productName(),
                pl.requestedQuantity(),
                unitPrice,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                lineTotal,
                PurchaseOrder.LineStatus.OPEN
            ));
        }
        return lines;
    }
}
