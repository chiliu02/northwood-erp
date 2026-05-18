package com.northwood.manufacturing.application;

import com.northwood.manufacturing.application.dto.BomFlatComponentView;
import com.northwood.manufacturing.application.dto.BomTreeView;
import com.northwood.manufacturing.application.dto.BomTreeView.BomNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Read-side service for BOM views. Two presentations of the same recursive
 * walk over {@link BomLookup}:
 *
 * <ul>
 *   <li>{@link #findActiveTreeByProductId} — hierarchical view: sub-assembly
 *       components (those that themselves have an active BOM) are expanded
 *       inline as nested {@link BomNode} children; raw materials terminate.
 *       Wire format for {@code GET /api/boms/by-product/{id}}.</li>
 *   <li>{@link #findFlatComponentsByProductId} — flattened view: every
 *       component in the hierarchy as a single list entry, each carrying its
 *       cumulative per-finished-unit quantity (parent quantity × this
 *       quantity × scrap-factor multiplier, recursive). Wire format for
 *       {@code GET /api/boms/by-product/{id}/flat}.</li>
 * </ul>
 *
 * <p>Both methods orchestrate over {@link BomLookup} only — no JDBC leak
 * into {@code application/}. They share the recursive-walk machinery and
 * differ only in the accumulator: tree mode assembles {@link BomNode}
 * children, flat mode appends to a list with multiplied quantities.
 *
 * <p>Cycle protection: {@code BomCycleDetector} guarantees no cycles can be
 * saved, so the recursion is bounded by the depth of the legitimately-saved
 * graph. The visited set is a defensive belt-and-suspenders in case the
 * detector ever misses a path.
 *
 * <p><b>N+1 perf note</b>: each {@link BomLookup#findActiveByFinishedProductId}
 * call issues its own SQL — one per BOM in the hierarchy. Accepted today at
 * demo depth (~3 levels: FG → SA → RM). Scheduled for replacement with a
 * single recursive-CTE query under {@code dev-todo.md} §2.24.3; once that
 * lands, both methods will share the same one-query implementation and reshape
 * the result client-side into tree or flat form.
 */
@Service
public class BomViewService {

    private final BomLookup boms;

    public BomViewService(BomLookup boms) {
        this.boms = boms;
    }

    public Optional<BomTreeView> findActiveTreeByProductId(UUID rootProductId) {
        Optional<BomLookup.ActiveBom> rootBom = boms.findActiveByFinishedProductId(rootProductId);
        if (rootBom.isEmpty()) {
            return Optional.empty();
        }
        BomLookup.BomHeaderIdentity identity = boms.findActiveBomIdentity(rootProductId)
            .orElse(new BomLookup.BomHeaderIdentity("(unknown)", "(unknown)"));

        Set<UUID> visited = new HashSet<>();
        visited.add(rootProductId);
        List<BomNode> children = expandTreeComponents(rootBom.get().components(), visited);
        return Optional.of(new BomTreeView(
            rootBom.get().bomHeaderId(),
            rootProductId,
            identity.productSku(),
            identity.productName(),
            children
        ));
    }

    /**
     * Walk the active BOM hierarchy from {@code rootProductId} and return
     * every component as a flat list, each entry carrying its cumulative
     * per-finished-unit quantity (multiplied through every ancestor's
     * quantity × scrap-factor along the path from root).
     *
     * <p>Returns an empty list when no active BOM exists for the root.
     * Same component appearing at multiple positions in the tree (e.g. a
     * raw material used in two different sub-assemblies) surfaces as
     * multiple list entries — one per path — rather than being aggregated;
     * callers can group/sum by {@code componentProductId} if a deduped
     * total is needed.
     */
    public List<BomFlatComponentView> findFlatComponentsByProductId(UUID rootProductId) {
        Optional<BomLookup.ActiveBom> rootBom = boms.findActiveByFinishedProductId(rootProductId);
        if (rootBom.isEmpty()) {
            return List.of();
        }
        Set<UUID> visited = new HashSet<>();
        visited.add(rootProductId);
        List<BomFlatComponentView> flat = new ArrayList<>();
        flattenComponents(rootBom.get().components(), BigDecimal.ONE, 1, visited, flat);
        return flat;
    }

    private List<BomNode> expandTreeComponents(List<BomLookup.Component> components, Set<UUID> visited) {
        List<BomNode> out = new ArrayList<>(components.size());
        for (BomLookup.Component c : components) {
            UUID childId = c.componentProductId();
            boolean canRecurse = visited.add(childId);
            Optional<BomLookup.ActiveBom> childBom = canRecurse
                ? boms.findActiveByFinishedProductId(childId)
                : Optional.empty();
            UUID subBomId = childBom.map(BomLookup.ActiveBom::bomHeaderId).orElse(null);
            List<BomNode> grandchildren = childBom
                .map(b -> expandTreeComponents(b.components(), visited))
                .orElseGet(List::of);
            out.add(new BomNode(
                childId,
                c.componentSku(),
                c.componentName(),
                c.componentKind(),
                c.quantityPerFinishedUnit(),
                c.scrapFactorPercent(),
                subBomId,
                grandchildren
            ));
            visited.remove(childId);
        }
        return out;
    }

    private void flattenComponents(
        List<BomLookup.Component> components,
        BigDecimal parentCumulativeQty,
        int depth,
        Set<UUID> visited,
        List<BomFlatComponentView> out
    ) {
        for (BomLookup.Component c : components) {
            UUID childId = c.componentProductId();
            // Per-finished-unit quantity at THIS level, including this line's
            // scrap factor: quantityPerFinishedUnit × (1 + scrap_factor_percent / 100).
            BigDecimal scrapMultiplier = BigDecimal.ONE.add(
                c.scrapFactorPercent().divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP)
            );
            BigDecimal effectiveQtyAtLevel = c.quantityPerFinishedUnit().multiply(scrapMultiplier);
            BigDecimal cumulativeQty = parentCumulativeQty.multiply(effectiveQtyAtLevel);

            out.add(new BomFlatComponentView(
                childId,
                c.componentSku(),
                c.componentName(),
                c.componentKind(),
                cumulativeQty,
                depth
            ));

            boolean canRecurse = visited.add(childId);
            if (!canRecurse) {
                continue;
            }
            Optional<BomLookup.ActiveBom> childBom = boms.findActiveByFinishedProductId(childId);
            childBom.ifPresent(b -> flattenComponents(b.components(), cumulativeQty, depth + 1, visited, out));
            visited.remove(childId);
        }
    }
}
