package com.northwood.manufacturing.application;

import com.northwood.manufacturing.domain.Bom;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Read port for the active BOM of a finished product. Returns enough detail
 * to snapshot {@code work_order_material} rows at release time. Deliberately
 * not a full BOM aggregate yet — that lands when manufacturing grows BOM-edit
 * commands and the cycle-prevention DFS (see dev-todo).
 */
public interface BomLookup {

    Optional<ActiveBom> findActiveByFinishedProductId(UUID finishedProductId);

    /**
     * §2.8 Slice D: products whose <em>active</em> BoM lists {@code componentProductId}
     * as a line. Used by {@code MaterialsCostRollupService} to walk up from a
     * component whose materials cost just changed and recompute every parent.
     *
     * <p>"Active" means the row has a non-null
     * {@code manufacturing.product_card.active_bom_header_id} (the canonical
     * pointer). The legacy {@code bom_header.status='active'}
     * fallback isn't consulted here — by the time Slice D's rollup is exercised
     * the projection is the authority. Returns an empty list when the component
     * is in no active BoM (typical for raw materials that don't appear as a
     * line on anyone, or for purchased items whose active-BoM membership is
     * itself empty).
     */
    List<UUID> findParentProductIdsByComponent(UUID componentProductId);

    /**
     * Returns identity (SKU + name) of the finished product owning the active
     * BoM, sourced from {@code manufacturing.bom_header}. Used by the read-side
     * tree viewer to label the root node. Default impl returns empty so
     * in-memory test stubs don't have to override.
     */
    default Optional<BomHeaderIdentity> findActiveBomIdentity(UUID finishedProductId) {
        return Optional.empty();
    }

    /**
     * §2.24.3: returns every component in the active BOM hierarchy rooted at
     * {@code rootProductId} in one read. Each row carries its position in the
     * tree (depth + holding BOM + the component's own active BOM if it has
     * one) plus the cumulative per-finished-unit quantity multiplied through
     * the ancestor chain. {@link BomViewService} uses this for both tree and
     * flat presentations.
     *
     * <p>Default implementation walks the hierarchy via repeated
     * {@link #findActiveByFinishedProductId} calls — the legacy N+1 path,
     * kept so in-memory test stubs don't have to override. Production
     * {@code JdbcBomLookup} overrides with a single recursive-CTE query.
     *
     * <p>Rows are returned in DFS order (depth-first descent matching tree
     * traversal). Empty list when the root has no active BOM.
     */
    default List<ComponentTreeRow> findActiveBomTreeRows(UUID rootProductId) {
        Optional<ActiveBom> root = findActiveByFinishedProductId(rootProductId);
        if (root.isEmpty()) {
            return List.of();
        }
        List<ComponentTreeRow> out = new ArrayList<>();
        Set<UUID> visited = new HashSet<>();
        visited.add(rootProductId);
        walkComponents(root.get().bomHeaderId(), root.get().components(), 1, BigDecimal.ONE, visited, out);
        return out;
    }

    private void walkComponents(
        UUID holderBomHeaderId,
        List<Component> components,
        int depth,
        BigDecimal parentCumulative,
        Set<UUID> visited,
        List<ComponentTreeRow> out
    ) {
        for (Component c : components) {
            BigDecimal scrapMultiplier = BigDecimal.ONE.add(
                c.scrapFactorPercent().divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP)
            );
            BigDecimal cumulativeQty = parentCumulative
                .multiply(c.quantityPerFinishedUnit())
                .multiply(scrapMultiplier);
            Optional<ActiveBom> childBom = findActiveByFinishedProductId(c.componentProductId());
            UUID childActiveBomHeaderId = childBom.map(ActiveBom::bomHeaderId).orElse(null);

            out.add(new ComponentTreeRow(
                depth,
                holderBomHeaderId,
                c.componentProductId(),
                c.componentSku(),
                c.componentName(),
                c.componentKind(),
                c.quantityPerFinishedUnit(),
                c.scrapFactorPercent(),
                childActiveBomHeaderId,
                cumulativeQty
            ));

            boolean canRecurse = visited.add(c.componentProductId());
            if (canRecurse && childBom.isPresent()) {
                walkComponents(childActiveBomHeaderId, childBom.get().components(), depth + 1, cumulativeQty, visited, out);
            }
            visited.remove(c.componentProductId());
        }
    }

    record ActiveBom(UUID bomHeaderId, List<Component> components) {}

    record Component(
        UUID componentProductId,
        String componentSku,
        String componentName,
        BigDecimal quantityPerFinishedUnit,
        BigDecimal scrapFactorPercent,
        Bom.ComponentKind componentKind
    ) {}

    record BomHeaderIdentity(String productSku, String productName) {}

    /**
     * One row per BOM-line in the recursive hierarchy walk, carrying
     * everything {@link BomViewService} needs to assemble either the tree
     * or the flat presentation in a single pass.
     *
     * @param depth 1 for direct children of the root, 2 for grandchildren, etc.
     * @param holderBomHeaderId the BOM header that contains this row as a
     *     line — i.e. the parent component's own BOM. Tree-assembly uses
     *     this to attach a row to its parent.
     * @param childActiveBomHeaderId this component's own active BOM, when it
     *     has one (signals a sub-assembly). Null for raw materials.
     * @param cumulativeQuantityPerFinishedUnit running product of
     *     {@code quantityPerFinishedUnit × (1 + scrapFactorPercent/100)}
     *     along the path from root, so the flat view doesn't have to
     *     remultiply.
     */
    record ComponentTreeRow(
        int depth,
        UUID holderBomHeaderId,
        UUID componentProductId,
        String componentSku,
        String componentName,
        Bom.ComponentKind componentKind,
        BigDecimal quantityPerFinishedUnit,
        BigDecimal scrapFactorPercent,
        UUID childActiveBomHeaderId,
        BigDecimal cumulativeQuantityPerFinishedUnit
    ) {}
}
