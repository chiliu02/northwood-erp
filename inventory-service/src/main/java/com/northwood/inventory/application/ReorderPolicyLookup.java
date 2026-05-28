package com.northwood.inventory.application;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * §2.35 Slice B: narrow read-side lookup for a SKU's reorder policy.
 * Per-product (not per-warehouse) — the policy is a catalogue parameter; the
 * trigger fires when ANY warehouse's on-hand drops below it.
 *
 * <p>Returns {@link Optional#empty()} when no {@code stock_item} row exists
 * for the product (a SKU never seen by inventory). The detection service
 * treats that as "no policy → no auto-replenishment", consistent with the
 * zero-default behaviour (a row with {@code reorder_point = 0} also
 * shouldn't trigger).
 *
 * <p>Application-side port; JDBC implementation lives in
 * {@code infrastructure/persistence/JdbcReorderPolicyLookup}.
 */
public interface ReorderPolicyLookup {

    Optional<ReorderPolicy> findByProductId(UUID productId);

    record ReorderPolicy(BigDecimal reorderPoint, BigDecimal reorderQuantity) {}
}
