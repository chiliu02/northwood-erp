package com.northwood.manufacturing.application.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Flat-view entry for a single component in a Bill of Materials hierarchy.
 * Each entry carries the cumulative per-finished-unit quantity needed —
 * multiplied through every ancestor's {@code quantityPerFinishedUnit ×
 * scrap-factor-multiplier} along the path from the root finished product
 * to this component.
 *
 * <p>Wire format for {@code GET /api/boms/by-product/{id}/flat}. Companion
 * to {@link BomTreeView} which carries the same data in hierarchical shape.
 *
 * @param depth 1 for direct children of the root, 2 for grandchildren, etc.
 *     Useful for UIs that want to indent or sort by depth without rebuilding
 *     the tree.
 * @param cumulativeQuantityPerFinishedUnit total amount of this component
 *     needed to manufacture one unit of the root finished product, with all
 *     intermediate scrap factors applied.
 */
public record BomFlatComponentView(
    UUID componentProductId,
    String componentSku,
    String componentName,
    String componentKind,
    BigDecimal cumulativeQuantityPerFinishedUnit,
    int depth
) {}
