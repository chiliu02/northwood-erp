package com.northwood.manufacturing.application.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Flat-view entry for a unique component in a Bill of Materials hierarchy.
 * Same component appearing at multiple positions in the tree (e.g. a raw
 * material used in two different sub-assemblies plus directly at the root)
 * is collapsed into a single entry; the
 * {@code cumulativeQuantityPerFinishedUnit} sums across every path from
 * root to that component, with each path's {@code quantityPerFinishedUnit ×
 * scrap-factor-multiplier} chain multiplied through first.
 *
 * <p>Wire format for {@code GET /api/boms/by-product/{id}/flat}. Companion
 * to {@link BomTreeView} which carries the same data in hierarchical shape.
 *
 * @param cumulativeQuantityPerFinishedUnit total amount of this component
 *     needed to manufacture one unit of the root finished product —
 *     summed across all paths the component appears on, each path's
 *     intermediate quantity-times-scrap chain applied.
 */
public record BomFlatComponentView(
    UUID componentProductId,
    String componentSku,
    String componentName,
    String componentKind,
    BigDecimal cumulativeQuantityPerFinishedUnit
) {}
