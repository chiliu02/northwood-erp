package com.northwood.inventory.infrastructure.persistence;

import com.northwood.inventory.application.StockBalanceWriter;
import java.math.BigDecimal;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * JDBC adapter for {@link StockBalanceWriter}.
 *
 * <p>PK passed explicitly per CLAUDE.md "Reference data and seed UUIDs"
 * — the {@code DEFAULT shared.uuid_generate_v7()} on the table fails at
 * runtime under per-service search_path (which doesn't include {@code public}
 * where pgcrypto lives).
 */
@Repository
public class JdbcStockBalanceWriter implements StockBalanceWriter {

    private static final Logger log = LoggerFactory.getLogger(JdbcStockBalanceWriter.class);

    private final JdbcTemplate jdbc;

    public JdbcStockBalanceWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void bump(UUID warehouseId, UUID productId, BigDecimal quantity) {
        if (quantity == null || quantity.signum() <= 0) {
            return;
        }
        int rows = jdbc.update("""
            UPDATE inventory.stock_balance
               SET on_hand_quantity = on_hand_quantity + ?,
                   version = version + 1
             WHERE warehouse_id = ? AND product_id = ?
            """,
            quantity, warehouseId, productId
        );
        if (rows == 0) {
            jdbc.update("""
                INSERT INTO inventory.stock_balance (
                    stock_balance_id, warehouse_id, product_id,
                    on_hand_quantity, reserved_quantity, average_cost
                ) VALUES (?, ?, ?, ?, 0, 0)
                """,
                UUID.randomUUID(), warehouseId, productId, quantity
            );
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean tryReserveOnHand(UUID warehouseId, UUID productId, BigDecimal quantity) {
        if (quantity == null || quantity.signum() <= 0) {
            return false;
        }
        int rows = jdbc.update("""
            UPDATE inventory.stock_balance
               SET reserved_quantity = reserved_quantity + ?,
                   version = version + 1
             WHERE warehouse_id = ? AND product_id = ?
               AND on_hand_quantity - reserved_quantity >= ?
            """,
            quantity, warehouseId, productId, quantity
        );
        return rows > 0;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void releaseReserved(UUID warehouseId, UUID productId, BigDecimal quantity) {
        if (quantity == null || quantity.signum() <= 0) {
            return;
        }
        jdbc.update("""
            UPDATE inventory.stock_balance
               SET reserved_quantity = reserved_quantity - ?,
                   version = version + 1
             WHERE warehouse_id = ? AND product_id = ?
            """,
            quantity, warehouseId, productId
        );
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void decrementOnHandAndReleaseReserved(UUID warehouseId, UUID productId, BigDecimal shippedQty) {
        if (shippedQty == null || shippedQty.signum() <= 0) {
            return;
        }
        // Single-statement update: drop on_hand and release reserved (capped at
        // current reserved so it can't go negative). Reserved is 0 for the
        // make-to-order-with-failed-reservation path, so the release becomes a
        // no-op there; on_hand was bumped by manufacturing confirmation to a
        // non-negative value, so the decrement is safe.
        int rows = jdbc.update("""
            UPDATE inventory.stock_balance
               SET on_hand_quantity  = on_hand_quantity - ?,
                   reserved_quantity = reserved_quantity - LEAST(reserved_quantity, ?),
                   version = version + 1
             WHERE warehouse_id = ? AND product_id = ?
            """,
            shippedQty, shippedQty, warehouseId, productId
        );
        if (rows == 0) {
            log.warn("no stock_balance row for warehouse={} product={} on shipment-side decrement; goods may have been built without a confirmation event",
                warehouseId, productId);
        }
    }
}
