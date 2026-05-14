package com.northwood.manufacturing.application;

import com.northwood.manufacturing.application.dto.BomTreeView;
import com.northwood.manufacturing.application.dto.BomTreeView.BomNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Walks the active BOM hierarchy from a finished-good or semi-finished-good
 * root, returning a recursive {@link BomTreeView}. Sub-assembly components
 * (those that themselves have an active BOM) are expanded inline; raw
 * materials terminate the recursion.
 *
 * <p>Cycle protection: {@code BomCycleDetector} guarantees no cycles can be
 * saved, so the recursion is bounded by the depth of the legitimately-saved
 * graph. The visited set is a defensive belt-and-suspenders in case the
 * detector ever misses a path.
 */
@Service
public class BomTreeService {

    private final BomLookup boms;

    public BomTreeService(BomLookup boms) {
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
        List<BomNode> children = expandComponents(rootBom.get().components(), visited);
        return Optional.of(new BomTreeView(
            rootBom.get().bomHeaderId(),
            rootProductId,
            identity.productSku(),
            identity.productName(),
            children
        ));
    }

    private List<BomNode> expandComponents(List<BomLookup.Component> components, Set<UUID> visited) {
        List<BomNode> out = new ArrayList<>(components.size());
        for (BomLookup.Component c : components) {
            UUID childId = c.componentProductId();
            boolean canRecurse = visited.add(childId);
            Optional<BomLookup.ActiveBom> childBom = canRecurse
                ? boms.findActiveByFinishedProductId(childId)
                : Optional.empty();
            UUID subBomId = childBom.map(BomLookup.ActiveBom::bomHeaderId).orElse(null);
            List<BomNode> grandchildren = childBom
                .map(b -> expandComponents(b.components(), visited))
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
}
