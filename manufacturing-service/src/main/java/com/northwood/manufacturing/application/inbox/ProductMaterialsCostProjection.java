package com.northwood.manufacturing.application.inbox;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Owns {@code manufacturing.product_materials_cost} — the data of record
 * for materialsCost. The rollup engine ({@code MaterialsCostRollupService})
 * is the only writer; the controller/UI is the only reader.
 *
 * <p>Despite the {@code Projection} suffix, this is not driven by an
 * inbound product/sales/etc. event — it's driven by manufacturing's own
 * inbox handler for {@code purchasing.SupplierProductPriceChanged}, which
 * invokes the rollup engine, which calls this projection. The Projection
 * suffix is correct because the table is a read model, not aggregate state
 * (no invariants live here; it's a denormalised snapshot of compute).
 *
 * <p>{@code reason} encodes why the latest write happened — values for
 * Slice C: {@code 'supplier_price_change'}, {@code 'inputs_missing'}.
 * Slice D will add {@code 'bom_activated'}, {@code 'bom_line_changed'},
 * {@code 'child_materials_cost_changed'}.
 */
public interface ProductMaterialsCostProjection {

    /**
     * Upsert the rollup output. {@code materialsCost} and
     * {@code currencyCode} are nullable together: when {@code reason} is
     * {@code 'inputs_missing'} both are null.
     */
    void apply(
        UUID productId,
        BigDecimal materialsCost,
        String currencyCode,
        String reason,
        Instant capturedAt
    );

    Optional<MaterialsCost> findByProductId(UUID productId);

    record MaterialsCost(
        UUID productId,
        BigDecimal materialsCost,
        String currencyCode,
        String reason,
        Instant capturedAt
    ) {}
}
