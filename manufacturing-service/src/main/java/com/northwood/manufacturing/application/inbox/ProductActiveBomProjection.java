package com.northwood.manufacturing.application.inbox;

import java.util.Optional;
import java.util.UUID;

/**
 * Maintains {@code manufacturing.product_card} from
 * {@code product.ActiveBomChanged} events. Co-exists with manufacturing's own
 * {@code bom_header.is_active} column during the migration period.
 *
 * <p>Read path: {@link #findActiveBomId(UUID)} returns the active BoM id
 * for a product, or {@link Optional#empty()} when no BoM has been activated
 * (typical for purchased-only items). The rollup engine routes
 * purchased-vs-manufactured on the presence/absence of this row.
 *
 * <p>Application-side port; JDBC implementation lives in
 * {@code infrastructure/persistence/JdbcProductActiveBomProjection}.
 */
public interface ProductActiveBomProjection {

    void apply(UUID productId, UUID newActiveBomId);

    Optional<UUID> findActiveBomId(UUID productId);
}
