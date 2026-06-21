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
        // Single-statement upsert: add to the existing balance, or seed the row
        // if this is the product's first touch in this warehouse. The earlier
        // UPDATE-then-INSERT-on-zero-rows seeded the row in a second statement,
        // which races under multi-partition consumption — two `bump`s for the
        // same (warehouse, product) on different partition threads could both
        // see zero rows and both INSERT, the second hitting
        // UNIQUE (warehouse_id, product_id) → DataIntegrityViolation → DLT. The
        // ON CONFLICT collapses seed + add into one atomic write, so concurrent
        // first-touches converge instead of one dead-lettering.
        jdbc.update("""
            INSERT INTO inventory.stock_balance (
                stock_balance_id, warehouse_id, product_id,
                on_hand_quantity, reserved_quantity, average_cost
            ) VALUES (?, ?, ?, ?, 0, 0)
            ON CONFLICT (warehouse_id, product_id) DO UPDATE
                SET on_hand_quantity = inventory.stock_balance.on_hand_quantity + EXCLUDED.on_hand_quantity,
                    version = inventory.stock_balance.version + 1
            """,
            UUID.randomUUID(), warehouseId, productId, quantity
        );
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean decrementOnHand(UUID warehouseId, UUID productId, BigDecimal quantity) {
        if (quantity == null || quantity.signum() <= 0) {
            return false;
        }
        // Guard: on_hand - q must stay >= reserved (which, since reserved >= 0,
        // also keeps on_hand >= 0). Both stock_balance CHECKs are respected, so
        // the UPDATE can never raise a constraint violation — a would-be breach
        // simply affects zero rows and we return false. reserved is left alone.
        int rows = jdbc.update("""
            UPDATE inventory.stock_balance
               SET on_hand_quantity = on_hand_quantity - ?,
                   version = version + 1
             WHERE warehouse_id = ? AND product_id = ?
               AND on_hand_quantity - ? >= reserved_quantity
            """,
            quantity, warehouseId, productId, quantity
        );
        return rows > 0;
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
        // Guard the subtraction so a duplicate / over-release can't drive
        // reserved_quantity below 0 (CHECK reserved_quantity >= 0) — which would
        // surface as a 23514 at write time and wedge the consumer. A breach
        // matches 0 rows and is logged, mirroring the predicate-guarded
        // decrementOnHand / tryReserveOnHand above (no-op rather than throw).
        int rows = jdbc.update("""
            UPDATE inventory.stock_balance
               SET reserved_quantity = reserved_quantity - ?,
                   version = version + 1
             WHERE warehouse_id = ? AND product_id = ? AND reserved_quantity >= ?
            """,
            quantity, warehouseId, productId, quantity
        );
        if (rows == 0) {
            log.warn("releaseReserved no-op: releasing {} but reserved_quantity is lower (or no balance row) "
                + "for warehouse={} product={}", quantity, warehouseId, productId);
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean decrementOnHandAndReleaseReserved(UUID warehouseId, UUID productId, BigDecimal shippedQty) {
        if (shippedQty == null || shippedQty.signum() <= 0) {
            return false;
        }
        // Single-statement update: drop on_hand and release reserved (capped at
        // current reserved so it can't go negative). Guarded so on_hand can
        // never go negative: the `on_hand_quantity >= ?` predicate makes a
        // would-be breach affect zero rows (return false) instead of raising
        // stock_balance_check (23514) — which previously aborted the tx and, on
        // the ship REST path, surfaced as a 500. Reserved is 0 for the
        // make-to-order-with-failed-reservation path, so the release becomes a
        // no-op there; a legitimately-built FG has on_hand >= shipped and passes.
        int rows = jdbc.update("""
            UPDATE inventory.stock_balance
               SET on_hand_quantity  = on_hand_quantity - ?,
                   reserved_quantity = reserved_quantity - LEAST(reserved_quantity, ?),
                   version = version + 1
             WHERE warehouse_id = ? AND product_id = ?
               AND on_hand_quantity >= ?
            """,
            shippedQty, shippedQty, warehouseId, productId, shippedQty
        );
        if (rows == 0) {
            log.warn("ship-side decrement no-op for warehouse={} product={} qty={}: on_hand can't cover the shipment "
                + "(no balance row, or goods not actually on hand)", warehouseId, productId, shippedQty);
        }
        return rows > 0;
    }
}
