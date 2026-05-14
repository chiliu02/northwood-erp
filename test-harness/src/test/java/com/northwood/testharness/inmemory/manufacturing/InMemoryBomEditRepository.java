package com.northwood.testharness.inmemory.manufacturing;

import com.northwood.manufacturing.domain.BomEditRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * In-memory {@link BomEditRepository}. Implemented for symmetry with the
 * production wiring; kits register {@code BomEditService} so tests can
 * exercise it. Lines stored in insertion order for deterministic
 * {@code nextLineNumber}.
 */
public final class InMemoryBomEditRepository implements BomEditRepository {

    private record Header(
        UUID id, UUID finishedProductId, String finishedProductSku, String finishedProductName,
        String version, String status
    ) {}

    private record Line(
        UUID bomLineId, UUID bomHeaderId, int lineNumber,
        UUID componentProductId, String componentSku, String componentName,
        String componentKind, BigDecimal quantityPerFinishedUnit, BigDecimal scrapFactorPercent
    ) {}

    private final Map<UUID, Header> headers = new HashMap<>();
    private final Map<UUID, Line> linesById = new LinkedHashMap<>();
    private final Map<UUID, List<UUID>> linesByHeader = new HashMap<>();

    @Override
    public void insertHeader(
        UUID bomHeaderId, UUID finishedProductId,
        String finishedProductSku, String finishedProductName, String version
    ) {
        headers.put(bomHeaderId, new Header(
            bomHeaderId, finishedProductId, finishedProductSku, finishedProductName, version, "draft"
        ));
        linesByHeader.computeIfAbsent(bomHeaderId, k -> new ArrayList<>());
    }

    @Override
    public Optional<HeaderRow> findHeader(UUID bomHeaderId) {
        Header h = headers.get(bomHeaderId);
        if (h == null) return Optional.empty();
        return Optional.of(new HeaderRow(h.id(), h.finishedProductId(), h.finishedProductSku(), h.status()));
    }

    @Override
    public int nextLineNumber(UUID bomHeaderId) {
        List<UUID> lineIds = linesByHeader.getOrDefault(bomHeaderId, List.of());
        int max = 0;
        for (UUID lineId : lineIds) {
            Line l = linesById.get(lineId);
            if (l != null && l.lineNumber() > max) max = l.lineNumber();
        }
        return max + 1;
    }

    @Override
    public void insertLine(
        UUID bomLineId, UUID bomHeaderId, int lineNumber,
        UUID componentProductId, String componentSku, String componentName,
        String componentKind, BigDecimal quantityPerFinishedUnit, BigDecimal scrapFactorPercent
    ) {
        linesById.put(bomLineId, new Line(
            bomLineId, bomHeaderId, lineNumber,
            componentProductId, componentSku, componentName,
            componentKind, quantityPerFinishedUnit, scrapFactorPercent
        ));
        linesByHeader.computeIfAbsent(bomHeaderId, k -> new ArrayList<>()).add(bomLineId);
    }

    @Override
    public Optional<UUID> findHeaderIdByLineId(UUID bomLineId) {
        Line l = linesById.get(bomLineId);
        return Optional.ofNullable(l == null ? null : l.bomHeaderId());
    }

    @Override
    public boolean deleteLine(UUID bomLineId) {
        Line l = linesById.remove(bomLineId);
        if (l == null) return false;
        List<UUID> bag = linesByHeader.get(l.bomHeaderId());
        if (bag != null) bag.remove(bomLineId);
        return true;
    }

    @Override
    public int countLines(UUID bomHeaderId) {
        return linesByHeader.getOrDefault(bomHeaderId, List.of()).size();
    }

    @Override
    public void markActive(UUID bomHeaderId) {
        Header h = headers.get(bomHeaderId);
        if (h == null) return;
        // Enforce uq_bom_active_per_product: any other active for this product fails.
        for (Header other : headers.values()) {
            if (!other.id().equals(bomHeaderId)
                && other.finishedProductId().equals(h.finishedProductId())
                && "active".equals(other.status())) {
                throw new IllegalStateException(
                    "another bom_header is already active for product " + h.finishedProductId()
                );
            }
        }
        headers.put(bomHeaderId, new Header(
            h.id(), h.finishedProductId(), h.finishedProductSku(), h.finishedProductName(),
            h.version(), "active"
        ));
    }

    @Override
    public List<UUID> findComponentProductIds(UUID bomHeaderId) {
        List<UUID> out = new ArrayList<>();
        for (UUID lineId : linesByHeader.getOrDefault(bomHeaderId, List.of())) {
            Line l = linesById.get(lineId);
            if (l != null) out.add(l.componentProductId());
        }
        return out;
    }
}
