package com.northwood.testharness.inmemory.manufacturing;

import com.northwood.manufacturing.domain.BomCycleDetector;
import com.northwood.manufacturing.application.BomLookup;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * In-memory {@link BomCycleDetector}: walks the active-BoM graph (via
 * {@link InMemoryBomLookup}) and returns true if {@code targetProductId} is
 * reachable from {@code startProductId}. Mirrors the recursive-CTE in the
 * JDBC adapter at the API surface.
 */
public final class InMemoryBomCycleDetector implements BomCycleDetector {

    private final InMemoryBomLookup boms;

    public InMemoryBomCycleDetector(InMemoryBomLookup boms) {
        this.boms = boms;
    }

    @Override
    public boolean wouldCreateCycle(UUID startProductId, UUID targetProductId, UUID candidateActiveBomHeaderId) {
        if (startProductId.equals(targetProductId)) return true;
        Set<UUID> visited = new HashSet<>();
        return walk(startProductId, targetProductId, visited);
    }

    private boolean walk(UUID currentProductId, UUID targetProductId, Set<UUID> visited) {
        if (!visited.add(currentProductId)) return false;
        var bomOpt = boms.findActiveByFinishedProductId(currentProductId);
        if (bomOpt.isEmpty()) return false;
        for (BomLookup.Component c : bomOpt.get().components()) {
            if (c.componentProductId().equals(targetProductId)) return true;
            if (walk(c.componentProductId(), targetProductId, visited)) return true;
        }
        return false;
    }
}
