package com.northwood.manufacturing.application;

import com.northwood.manufacturing.application.BomLookup.ComponentTreeRow;
import com.northwood.manufacturing.application.dto.BomFlatComponentView;
import com.northwood.manufacturing.application.dto.BomTreeView;
import com.northwood.manufacturing.application.dto.BomTreeView.BomNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Read-side service for BOM views. Two presentations of the same one-query
 * recursive walk via {@link BomLookup#findActiveBomTreeRows}:
 *
 * <ul>
 *   <li>{@link #findActiveTreeByProductId} — hierarchical view: sub-assembly
 *       components (those that themselves have an active BOM) are expanded
 *       inline as nested {@link BomNode} children; raw materials terminate.
 *       Wire format for {@code GET /api/boms/by-product/{id}}.</li>
 *   <li>{@link #findFlatComponentsByProductId} — flattened view: every
 *       component in the hierarchy as a single list entry, each carrying
 *       its cumulative per-finished-unit quantity (already multiplied
 *       through the ancestor chain by the SQL). Wire format for
 *       {@code GET /api/boms/by-product/{id}/flat}.</li>
 * </ul>
 *
 * <p>Both methods make exactly one read via {@link BomLookup} (a recursive
 * CTE in production; the default interface walk for in-memory test stubs).
 * Tree mode rehydrates the hierarchy from the flat row set by grouping rows
 * by holding {@code bom_header_id} and walking from root downward. Flat mode
 * passes the rows through with a field-name remap.
 *
 * <p>Cycle protection: {@code BomCycleDetector} guarantees no cycles can be
 * saved; the recursive CTE additionally caps depth at 20 as a defensive
 * belt-and-suspenders, and tree-mode rehydration is structurally bounded by
 * each row carrying only one parent ({@code holderBomHeaderId}).
 */
@Service
public class BomViewService {

    private final BomLookup boms;

    public BomViewService(BomLookup boms) {
        this.boms = boms;
    }

    public Optional<BomTreeView> findActiveTreeByProductId(UUID rootProductId) {
        List<ComponentTreeRow> rows = boms.findActiveBomTreeRows(rootProductId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        BomLookup.BomHeaderIdentity identity = boms.findActiveBomIdentity(rootProductId)
            .orElse(new BomLookup.BomHeaderIdentity("(unknown)", "(unknown)"));

        // The root BOM's bom_header_id is the holderBomHeaderId of any
        // depth-1 row (they all share it — they're all lines on the root BOM).
        UUID rootBomHeaderId = rows.get(0).holderBomHeaderId();

        // Group rows by holderBomHeaderId so nested-children lookup is O(1)
        // during the recursive node-build. LinkedHashMap preserves insertion
        // order, which respects the SQL ORDER BY (depth, bom_header_id,
        // line_number) — so sibling line ordering is preserved through the
        // tree assembly.
        Map<UUID, List<ComponentTreeRow>> byHolder = new LinkedHashMap<>();
        for (ComponentTreeRow r : rows) {
            byHolder.computeIfAbsent(r.holderBomHeaderId(), k -> new ArrayList<>()).add(r);
        }

        List<BomNode> rootChildren = buildNodes(byHolder.getOrDefault(rootBomHeaderId, List.of()), byHolder);
        return Optional.of(new BomTreeView(
            rootBomHeaderId,
            rootProductId,
            identity.productSku(),
            identity.productName(),
            rootChildren
        ));
    }

    /**
     * Walk the active BOM hierarchy from {@code rootProductId} and return
     * each unique component as a single flat-list entry. When the same
     * component appears at multiple positions in the tree, its rows are
     * collapsed and {@code cumulativeQuantityPerFinishedUnit} is summed
     * across all paths (each path's quantity × scrap-factor chain is
     * already multiplied through by the underlying SQL).
     *
     * <p>Returns an empty list when no active BOM exists for the root.
     * Order: first occurrence (DFS) wins — same order as the tree-view
     * walk, with duplicates removed.
     */
    public List<BomFlatComponentView> findFlatComponentsByProductId(UUID rootProductId) {
        // LinkedHashMap so first-occurrence DFS order is preserved across
        // the dedup pass. Same component at multiple positions collapses
        // into one entry with quantities summed.
        Map<UUID, BomFlatComponentView> byProductId = new LinkedHashMap<>();
        for (ComponentTreeRow r : boms.findActiveBomTreeRows(rootProductId)) {
            BomFlatComponentView existing = byProductId.get(r.componentProductId());
            BigDecimal total = existing == null
                ? r.cumulativeQuantityPerFinishedUnit()
                : existing.cumulativeQuantityPerFinishedUnit().add(r.cumulativeQuantityPerFinishedUnit());
            byProductId.put(r.componentProductId(), new BomFlatComponentView(
                r.componentProductId(),
                r.componentSku(),
                r.componentName(),
                r.componentKind(),
                total
            ));
        }
        return new ArrayList<>(byProductId.values());
    }

    private List<BomNode> buildNodes(
        List<ComponentTreeRow> rows,
        Map<UUID, List<ComponentTreeRow>> byHolder
    ) {
        List<BomNode> out = new ArrayList<>(rows.size());
        for (ComponentTreeRow r : rows) {
            List<BomNode> grandchildren = r.childActiveBomHeaderId() == null
                ? List.of()
                : buildNodes(byHolder.getOrDefault(r.childActiveBomHeaderId(), List.of()), byHolder);
            out.add(new BomNode(
                r.componentProductId(),
                r.componentSku(),
                r.componentName(),
                r.componentKind(),
                r.quantityPerFinishedUnit(),
                r.scrapFactorPercent(),
                r.childActiveBomHeaderId(),
                grandchildren
            ));
        }
        return out;
    }
}
