package com.northwood.reporting.application;

import com.northwood.reporting.application.dto.ReplenishmentHistoryView;
import java.util.List;
import java.util.UUID;

/**
 * Read-side query port over
 * {@code reporting.replenishment_history_view}. SKU + product name are
 * joined at query time from {@code reporting.available_to_promise_view}
 * (the existing reporting projection that already carries those facts).
 *
 * <p>JDBC implementation lives in
 * {@code infrastructure/persistence/JdbcReplenishmentHistoryQueryPort}.
 */
public interface ReplenishmentHistoryQueryPort {

    /** Most-recent-first list across all SKUs and warehouses. */
    List<ReplenishmentHistoryView> findAll(int limit);

    /** Most-recent-first list for a single SKU. Used by the SPA per-SKU widget. */
    List<ReplenishmentHistoryView> findRecentForProduct(UUID productId, int limit);
}
