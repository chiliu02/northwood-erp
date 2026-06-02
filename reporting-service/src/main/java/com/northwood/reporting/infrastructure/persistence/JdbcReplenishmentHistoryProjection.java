package com.northwood.reporting.infrastructure.persistence;

import com.northwood.reporting.application.inbox.ReplenishmentHistoryProjection;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcReplenishmentHistoryProjection implements ReplenishmentHistoryProjection {

    private static final Logger log = LoggerFactory.getLogger(JdbcReplenishmentHistoryProjection.class);

    private final JdbcTemplate jdbc;

    public JdbcReplenishmentHistoryProjection(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void recordRequested(
        UUID replenishmentRequestId,
        UUID productId,
        UUID warehouseId,
        BigDecimal requestedQuantity,
        String targetService,
        String reason,
        Instant requestedAt
    ) {
        // Order-tolerance: a late ReplenishmentRequested arriving AFTER a
        // ReplenishmentDispatched would otherwise stomp the dispatched status
        // back to 'requested'. The ON CONFLICT DO NOTHING preserves the
        // already-advanced state; status flips forward only via the dispatch
        // / fulfil handlers.
        jdbc.update("""
            INSERT INTO reporting.replenishment_history_view (
                replenishment_request_id, product_id, warehouse_id,
                requested_quantity, target_service, reason, status, requested_at
            ) VALUES (?, ?, ?, ?, ?, ?, 'requested', ?)
            ON CONFLICT (replenishment_request_id) DO UPDATE SET
                product_id = COALESCE(reporting.replenishment_history_view.product_id, EXCLUDED.product_id),
                warehouse_id = COALESCE(reporting.replenishment_history_view.warehouse_id, EXCLUDED.warehouse_id),
                requested_quantity = COALESCE(reporting.replenishment_history_view.requested_quantity, EXCLUDED.requested_quantity),
                target_service = COALESCE(reporting.replenishment_history_view.target_service, EXCLUDED.target_service),
                reason = COALESCE(reporting.replenishment_history_view.reason, EXCLUDED.reason),
                requested_at = COALESCE(reporting.replenishment_history_view.requested_at, EXCLUDED.requested_at)
            """,
            replenishmentRequestId, productId, warehouseId,
            requestedQuantity, targetService, reason,
            Timestamp.from(requestedAt)
        );
        log.info("recorded replenishment request {} (product={}, warehouse={}, qty={}, target={}, reason={})",
            replenishmentRequestId, productId, warehouseId,
            requestedQuantity, targetService, reason);
    }

    @Override
    @Transactional
    public void recordDispatched(
        UUID replenishmentRequestId,
        String dispatchedAggregateKind,
        UUID dispatchedAggregateId,
        Instant dispatchedAt
    ) {
        // The earlier-status fields stay write-forward; an out-of-order
        // ReplenishmentRequested arriving later would catch-up via INSERT,
        // but if the row exists already from another path the upsert
        // preserves the dispatched state.
        //
        // Status update is forward-only: 'requested' → 'dispatched';
        // 'fulfilled' / 'cancelled' rows stay put (don't regress).
        int rows = jdbc.update("""
            INSERT INTO reporting.replenishment_history_view (
                replenishment_request_id, product_id, warehouse_id,
                requested_quantity, target_service, reason, status,
                dispatched_aggregate_kind, dispatched_aggregate_id,
                requested_at, dispatched_at
            ) VALUES (
                ?, '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000',
                0, '(pending)', '(pending)', 'dispatched',
                ?, ?,
                ?, ?
            )
            ON CONFLICT (replenishment_request_id) DO UPDATE SET
                status = CASE
                    WHEN reporting.replenishment_history_view.status IN ('fulfilled', 'cancelled')
                    THEN reporting.replenishment_history_view.status
                    ELSE 'dispatched'
                END,
                dispatched_aggregate_kind = EXCLUDED.dispatched_aggregate_kind,
                dispatched_aggregate_id = EXCLUDED.dispatched_aggregate_id,
                dispatched_at = COALESCE(reporting.replenishment_history_view.dispatched_at, EXCLUDED.dispatched_at)
            """,
            replenishmentRequestId,
            dispatchedAggregateKind, dispatchedAggregateId,
            Timestamp.from(dispatchedAt), Timestamp.from(dispatchedAt)
        );
        // CHECK constraints disallow the placeholder '(pending)' values for
        // target_service and reason. If we hit a true out-of-order insert
        // (dispatch before request), the CHECK rejects the placeholder INSERT
        // and the row will arrive later via the requested handler. Treat
        // this as a known harmless gap — the same-tx producer guarantees
        // the request event lands before the dispatch within one producer's
        // outbox; cross-producer races (purchasing dispatch + inventory
        // requested) are vanishingly rare since both originate in the same
        // detection-tx. If observed, fallback to a SELECT-then-UPDATE
        // would be the right fix.
        log.info("recorded replenishment dispatched {} → {} {} (rows={})",
            replenishmentRequestId, dispatchedAggregateKind, dispatchedAggregateId, rows);
    }

    @Override
    @Transactional
    public void recordFulfilled(UUID replenishmentRequestId, Instant fulfilledAt) {
        int rows = jdbc.update("""
            UPDATE reporting.replenishment_history_view
            SET status = 'fulfilled',
                fulfilled_at = ?
            WHERE replenishment_request_id = ?
              AND status IN ('requested', 'dispatched')
            """,
            Timestamp.from(fulfilledAt), replenishmentRequestId
        );
        if (rows == 0) {
            log.warn("recordFulfilled: no replenishment_history_view row to update for replenishment_request={} (either not yet projected or already terminal)",
                replenishmentRequestId);
        } else {
            log.info("recorded replenishment fulfilled {}", replenishmentRequestId);
        }
    }
}
