package com.northwood.manufacturing.application.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Recursive read-side view of a product's active Bill of Materials. Each node
 * carries its own component identity + per-finished-unit quantity + scrap
 * factor, plus a children list that's non-empty only for sub-assemblies whose
 * own active BOM exists.
 *
 * <p>Wire format for {@code GET /api/boms/by-product/{id}}.
 */
public record BomTreeView(
    UUID bomHeaderId,
    UUID productId,
    String productSku,
    String productName,
    List<BomNode> components
) {

    public record BomNode(
        UUID componentProductId,
        String componentSku,
        String componentName,
        String componentKind,
        BigDecimal quantityPerFinishedUnit,
        BigDecimal scrapFactorPercent,
        UUID subBomHeaderId,
        List<BomNode> children
    ) {}
}
