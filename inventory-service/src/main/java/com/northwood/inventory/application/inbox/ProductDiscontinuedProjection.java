package com.northwood.inventory.application.inbox;

import java.time.Instant;
import java.util.UUID;

/**
 * Stamps {@code inventory.stock_item.discontinued_at} so future reorder-alert
 * logic can suppress alerts for retired SKUs. Update-only — if no
 * {@code stock_item} row exists yet for the product (inventory has no
 * {@code ProductCreated} consumer today; see §1F.2), the projection silently
 * no-ops. Mirrors {@link StockItemProjection#applyReorderPolicy}'s no-op
 * behaviour on the same race; when inventory grows a {@code ProductCreated}
 * consumer, redeliveries of the discontinue via the inbox would catch the
 * row up.
 *
 * <p>Separate from {@link StockItemProjection} because the existing class
 * writes through the aggregate repository and the discontinue write is a
 * one-column update that doesn't justify an aggregate mutation method;
 * keeping the discontinue concern in its own projection also matches the
 * sales / purchasing / reporting siblings in §1F.1.
 *
 * <p>Application-side port; JDBC implementation lives in
 * {@code infrastructure/persistence/JdbcProductDiscontinuedProjection}.
 */
public interface ProductDiscontinuedProjection {

    void applyDiscontinued(UUID productId, Instant discontinuedAt);
}
