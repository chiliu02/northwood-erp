package com.northwood.manufacturing.infrastructure.saga;

import com.northwood.manufacturing.application.saga.WorkOrderShortageRecoveryQueryPort;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * JDBC adapter for {@link WorkOrderShortageRecoveryQueryPort}. Joins
 * {@code work_order_saga} (parked in {@code raw_material_shortage}) to
 * {@code work_order_material} via the saga's {@code work_order_id} so the
 * receipt-side handler can decide which sagas to consider for un-parking.
 *
 * <p>Per-product loop rather than a single {@code IN (?, ?, …)} because
 * receipts typically carry one or two products and the per-query overhead
 * is negligible; the simpler shape avoids dynamic SQL building.
 */
@Component
public class JdbcWorkOrderShortageRecoveryQueryPort implements WorkOrderShortageRecoveryQueryPort {

    private final JdbcTemplate jdbc;

    public JdbcWorkOrderShortageRecoveryQueryPort(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<UUID> findShortageSagaIdsForReceivedProducts(Collection<UUID> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return List.of();
        }
        Set<UUID> deduped = new LinkedHashSet<>();
        for (UUID productId : productIds) {
            List<UUID> matched = jdbc.queryForList("""
                SELECT s.saga_id
                FROM manufacturing.work_order_saga s
                WHERE s.saga_state = 'raw_material_shortage'
                  AND EXISTS (
                      SELECT 1 FROM manufacturing.work_order_material m
                      WHERE m.work_order_id = s.work_order_id
                        AND m.component_product_id = ?
                  )
                """, UUID.class, productId);
            deduped.addAll(matched);
        }
        return new ArrayList<>(deduped);
    }
}
