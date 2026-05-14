package com.northwood.testharness.inmemory.manufacturing;

import com.northwood.manufacturing.application.BomLookup;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * In-memory {@link BomLookup}. Seedable with whole BOMs; reverse-walk
 * ({@link #findParentProductIdsByComponent}) is computed on demand from the
 * current seed.
 */
public final class InMemoryBomLookup implements BomLookup {

    private final Map<UUID, ActiveBom> byProductId = new LinkedHashMap<>();

    /**
     * Seed an active BoM. {@code finishedProductId} is the BoM's owning product;
     * {@code components} are the lines.
     */
    public InMemoryBomLookup put(UUID finishedProductId, ActiveBom bom) {
        byProductId.put(finishedProductId, bom);
        return this;
    }

    /** Convenience: build an ActiveBom with a list of components. */
    public InMemoryBomLookup put(UUID finishedProductId, UUID bomHeaderId, Component... components) {
        return put(finishedProductId, new ActiveBom(bomHeaderId, List.of(components)));
    }

    public static Component rawLine(
        UUID componentProductId, String sku, String name,
        BigDecimal qtyPerUnit, BigDecimal scrapPct
    ) {
        return new Component(componentProductId, sku, name, qtyPerUnit, scrapPct, "raw");
    }

    public static Component subAssemblyLine(
        UUID componentProductId, String sku, String name,
        BigDecimal qtyPerUnit, BigDecimal scrapPct
    ) {
        return new Component(componentProductId, sku, name, qtyPerUnit, scrapPct, "sub_assembly");
    }

    @Override
    public Optional<ActiveBom> findActiveByFinishedProductId(UUID finishedProductId) {
        return Optional.ofNullable(byProductId.get(finishedProductId));
    }

    @Override
    public List<UUID> findParentProductIdsByComponent(UUID componentProductId) {
        Map<UUID, Boolean> parents = new HashMap<>();
        for (Map.Entry<UUID, ActiveBom> e : byProductId.entrySet()) {
            for (Component c : e.getValue().components()) {
                if (componentProductId.equals(c.componentProductId())) {
                    parents.put(e.getKey(), true);
                    break;
                }
            }
        }
        return new ArrayList<>(parents.keySet());
    }
}
