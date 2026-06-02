package com.northwood.manufacturing.application;

import com.northwood.manufacturing.application.inbox.ProductApprovedVendorProjection;
import com.northwood.manufacturing.application.inbox.ProductMaterialsCostProjection;
import com.northwood.manufacturing.application.inbox.ProductReplenishmentProjection;
import com.northwood.manufacturing.domain.events.ProductMaterialsCostComputed;
import com.northwood.shared.application.outbox.OutboxAppender;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Owns the materials-cost facets on {@code manufacturing.product_card}
 * ({@code materials_cost}, {@code currency_code}, {@code materials_cost_reason},
 * {@code materials_cost_captured_at}) and emits
 * {@link ProductMaterialsCostComputed} as the public contract.
 *
 * <h3>Why this lives in manufacturing-service, not product-service</h3>
 * materialsCost is a *computed* value, not master data. Putting it on the
 * Product aggregate would force product-service to consume cross-service
 * events (purchasing, BoM rollup), breaking the Open Host invariant that
 * product-service is producer-only. Computed values live with the engine
 * that computes them — manufacturing already projects active BoM and
 * approved vendors, so it owns the rollup. (See design-notes.md →
 * "Computed values live with the engine that computes them".)
 *
 * <h3>Routing rules</h3>
 * <ol>
 *   <li><b>Active BoM wins.</b> If a product has an active BoM (per the
 *       {@code product_card.active_bom_header_id} / {@code bom_header.status='active'}
 *       fallback), its materialsCost is the BoM rollup — sum across lines of
 *       {@code quantityPerFinishedUnit * (1 + scrap_factor%/100) * componentMaterialsCost}.
 *       Reason set per the trigger: {@code "bom_activated"} on initial
 *       activation, {@code "child_materials_cost_changed"} during a parent
 *       walk after a descendant moved.</li>
 *   <li><b>No BoM, is_purchased=true.</b> Falls through to the supplier-price
 *       path: preferred supplier's price becomes the materialsCost. Reason
 *       {@code "supplier_price_change"}.</li>
 *   <li><b>No BoM, is_purchased=false (or unknown).</b> Nothing to compute —
 *       reason {@code "inputs_missing"} with null cost.</li>
 *   <li><b>Ambiguous preferred supplier (0 or 2+ rows flagged preferred).</b>
 *       Same null-propagation as case 3.</li>
 *   <li><b>Component cost is null (inputs_missing) anywhere in the BoM.</b>
 *       Parent's materialsCost propagates as null with reason
 *       {@code "inputs_missing"}. The whole walk is "all-or-nothing": one
 *       missing input kills the parent's cost.</li>
 * </ol>
 *
 * <h3>Parent recursion</h3>
 * After {@link #applyAndWalk} writes a product's new cost, every product
 * whose active BoM lists it as a line is recomputed in the same transaction,
 * recursively. A {@link Set} of visited ids guards against the (impossible
 * by cycle-detector but defensively-checked) case of a cycle slipping through
 * — the walk is breadth-of-tree-bounded, never infinite.
 *
 * <h3>Currency policy (locked 2026-05-08)</h3>
 * The BoM rollup throws {@link IllegalStateException} on cross-currency
 * combinations. A later change adds {@code CurrencyConverter} integration when the
 * showcase needs it; today the supplier price-list is single-currency in
 * practice (AUD for the demo dataset) so the throw is defensive.
 *
 * <h3>Documented limitation: vendor-flip doesn't reprice immediately</h3>
 * A {@code product.ApprovedVendorListChanged} that switches the preferred
 * supplier does *not* trigger a recompute on its own. The cost auto-corrects
 * on the next {@code SupplierProductPriceChanged} from the new preferred.
 * See design-notes.md → "Vendor-flip recompute deferred".
 */
@Service
public class MaterialsCostRollupService {

    private static final Logger log = LoggerFactory.getLogger(MaterialsCostRollupService.class);

    /** Defensive bound on parent-walk depth. BoM is acyclic by enforcement. */
    private static final int MAX_WALK_DEPTH = 32;

    private final ProductReplenishmentProjection replenishment;
    private final ProductApprovedVendorProjection approvedVendors;
    private final ProductMaterialsCostProjection materialsCosts;
    private final BomLookup boms;
    private final OutboxAppender outbox;

    public MaterialsCostRollupService(
        ProductReplenishmentProjection replenishment,
        ProductApprovedVendorProjection approvedVendors,
        ProductMaterialsCostProjection materialsCosts,
        BomLookup boms,
        OutboxAppender outbox
    ) {
        this.replenishment = replenishment;
        this.approvedVendors = approvedVendors;
        this.materialsCosts = materialsCosts;
        this.boms = boms;
        this.outbox = outbox;
    }

    /**
     * Supplier-price-change entry: an inbound supplier price changed. If the product has
     * an active BoM, the BoM is authoritative — this event doesn't change
     * the parent's materialsCost (children's events do that via the parent
     * walk). Otherwise routes through the supplier-price path.
     *
     * <p>Called from the inbox handler inside its {@code @Transactional}
     * boundary; does not open a new transaction.
     */
    public void onSupplierPriceChange(
        UUID supplierId,
        UUID productId,
        String currencyCode,
        BigDecimal newUnitPrice
    ) {
        if (boms.findActiveByFinishedProductId(productId).isPresent()) {
            // BoM wins; this product's cost is computed from its components.
            // The supplier price is still relevant if a *child* (the actual
            // purchased item) is the one priced — that arrives as a separate
            // SupplierProductPriceChanged on the child product, which does
            // route through the supplier-price path and walks parents.
            log.debug("rollup skipped via supplier path: product={} has active BoM (BoM wins)", productId);
            return;
        }

        var rep = replenishment.findByProductId(productId);
        if (rep.isEmpty() || !rep.get().isPurchased()) {
            log.debug("rollup skipped: product={} is not purchased (replenishment={})", productId, rep);
            return;
        }

        Optional<UUID> preferred = approvedVendors.findPreferredSupplierId(productId);
        if (preferred.isEmpty()) {
            log.debug("rollup → inputs_missing: product={} has no unique preferred supplier", productId);
            applyAndWalk(productId, null, null, "inputs_missing", new HashSet<>(), 0);
            return;
        }
        if (!preferred.get().equals(supplierId)) {
            log.debug("rollup skipped: product={} preferred={} but event supplier={}",
                productId, preferred.get(), supplierId);
            return;
        }

        applyAndWalk(productId, newUnitPrice, currencyCode, "supplier_price_change", new HashSet<>(), 0);
    }

    /**
     * BoM-walk entry: recompute via BoM walk. Called from
     * {@code ActiveBomChangedHandler} after the active-BoM projection is updated,
     * and from the parent-walk when a child's cost changes.
     *
     * <p>If the product has no active BoM (defensive — shouldn't happen for
     * valid triggers) the call is a no-op.
     */
    public void recomputeViaBom(UUID productId, String reason) {
        recomputeViaBom(productId, reason, new HashSet<>(), 0);
    }

    private void recomputeViaBom(UUID productId, String reason, Set<UUID> visited, int depth) {
        if (depth >= MAX_WALK_DEPTH) {
            log.warn("rollup walk depth {} reached at product={} — bailing", depth, productId);
            return;
        }
        if (!visited.add(productId)) {
            log.warn("rollup walk re-entered product={} — bailing (cycle?)", productId);
            return;
        }

        Optional<BomLookup.ActiveBom> activeBomOpt = boms.findActiveByFinishedProductId(productId);
        if (activeBomOpt.isEmpty()) {
            // No active BoM — this should never happen for a valid BoM-walk
            // trigger. Defensive: leave existing cost alone (don't blow away
            // a supplier-price-derived row by writing inputs_missing).
            log.debug("recomputeViaBom: product={} has no active BoM, skipping", productId);
            return;
        }

        BomLookup.ActiveBom activeBom = activeBomOpt.get();
        BomRollupResult outcome = computeBomRollup(activeBom);
        if (outcome.inputsMissing) {
            applyAndWalk(productId, null, null, "inputs_missing", visited, depth);
        } else {
            applyAndWalk(productId, outcome.totalCost, outcome.currency, reason, visited, depth);
        }
    }

    private BomRollupResult computeBomRollup(BomLookup.ActiveBom activeBom) {
        BigDecimal total = BigDecimal.ZERO;
        String currency = null;
        boolean inputsMissing = false;

        for (BomLookup.Component line : activeBom.components()) {
            Optional<ProductMaterialsCostProjection.MaterialsCost> componentCostOpt =
                materialsCosts.findByProductId(line.componentProductId());
            if (componentCostOpt.isEmpty() || componentCostOpt.get().materialsCost() == null) {
                log.debug("BoM rollup → inputs_missing: bom={} component={} has no materials cost",
                    activeBom.bomHeaderId(), line.componentProductId());
                inputsMissing = true;
                continue;
            }
            ProductMaterialsCostProjection.MaterialsCost cc = componentCostOpt.get();

            if (currency == null) {
                currency = cc.currencyCode();
            } else if (!currency.equals(cc.currencyCode())) {
                throw new IllegalStateException(
                    "BoM rollup currency mismatch on bom=" + activeBom.bomHeaderId()
                        + ": already accumulating in " + currency
                        + " but component " + line.componentProductId()
                        + " has currency " + cc.currencyCode()
                        + ". A future change will resolve via CurrencyConverter; for now the rollup throws."
                );
            }

            BigDecimal qty = nullToZero(line.quantityPerFinishedUnit());
            BigDecimal scrapPct = nullToZero(line.scrapFactorPercent());
            BigDecimal scrapMultiplier = BigDecimal.ONE.add(
                scrapPct.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)
            );
            BigDecimal lineCost = qty.multiply(scrapMultiplier).multiply(cc.materialsCost());
            total = total.add(lineCost);
        }

        if (inputsMissing) {
            return new BomRollupResult(true, null, null);
        }
        // Round the rolled-up cost to the same scale as supplier prices (6dp).
        return new BomRollupResult(false, total.setScale(6, RoundingMode.HALF_UP), currency);
    }

    private static BigDecimal nullToZero(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private void applyAndWalk(
        UUID productId,
        BigDecimal cost,
        String currencyCode,
        String reason,
        Set<UUID> visited,
        int depth
    ) {
        if (visited.contains(productId)) {
            // Already applied to this product in this chain — guard against
            // diamond shapes (D depends on B + C, both of which depend on A).
            // The first walk through D applies; the second is suppressed.
        }
        visited.add(productId);

        // No-op suppression: skip projection write + event emission when the
        // outcome is identical to the existing row. Avoids a parent walk
        // cascading through unchanged cells and causes idempotent retriggers
        // (e.g. inbox at-least-once redelivery) to land cheaply.
        Optional<ProductMaterialsCostProjection.MaterialsCost> existing =
            materialsCosts.findByProductId(productId);
        if (existing.isPresent()
            && eqCost(existing.get().materialsCost(), cost)
            && eqStr(existing.get().currencyCode(), currencyCode)
            && eqStr(existing.get().reason(), reason)) {
            log.debug("rollup no-op for product={} (unchanged cost+currency+reason)", productId);
            return;
        }

        Instant now = Instant.now();
        materialsCosts.apply(productId, cost, currencyCode, reason, now);

        ProductMaterialsCostComputed event = new ProductMaterialsCostComputed(
            UUID.randomUUID(),
            productId,
            cost,
            currencyCode,
            reason,
            now
        );
        outbox.append(event, ProductMaterialsCostComputed.AGGREGATE_TYPE);
        log.info("emitted {} product={} cost={} currency={} reason={}",
            ProductMaterialsCostComputed.EVENT_TYPE, productId, cost, currencyCode, reason);

        // Walk parents: any product whose active BoM lists this product as a
        // line needs to recompute. Parents always have active BoMs by
        // construction (a "parent" relationship in the active BoM graph
        // implies they have one).
        for (UUID parentId : boms.findParentProductIdsByComponent(productId)) {
            recomputeViaBom(parentId, "child_materials_cost_changed", visited, depth + 1);
        }
    }

    private static boolean eqCost(BigDecimal a, BigDecimal b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.compareTo(b) == 0;
    }

    private static boolean eqStr(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
    }

    private record BomRollupResult(boolean inputsMissing, BigDecimal totalCost, String currency) {}
}
