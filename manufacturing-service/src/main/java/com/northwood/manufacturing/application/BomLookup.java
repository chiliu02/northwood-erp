package com.northwood.manufacturing.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
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
     * <p>"Active" means the row appears in {@code manufacturing.product_active_bom}
     * (the canonical pointer). The legacy {@code bom_header.status='active'}
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

    record ActiveBom(UUID bomHeaderId, List<Component> components) {}

    record Component(
        UUID componentProductId,
        String componentSku,
        String componentName,
        BigDecimal quantityPerFinishedUnit,
        BigDecimal scrapFactorPercent,
        String componentKind
    ) {}

    record BomHeaderIdentity(String productSku, String productName) {}
}
