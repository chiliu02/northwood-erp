package com.northwood.inventory.infrastructure.persistence;

import com.northwood.inventory.application.StockMovementWriter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * JDBC adapter for {@link StockMovementWriter}.
 *
 * <p>PK passed explicitly per CLAUDE.md — {@code DEFAULT shared.uuid_generate_v7()}
 * fails at runtime under per-service search_path (which doesn't include
 * {@code public} where pgcrypto lives).
 */
@Repository
public class JdbcStockMovementWriter implements StockMovementWriter {

    private final JdbcTemplate jdbc;

    public JdbcStockMovementWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(
        UUID warehouseId,
        UUID productId,
        String productSku,
        String productName,
        String movementType,
        String direction,
        BigDecimal quantity,
        BigDecimal unitCost,
        String sourceType,
        UUID sourceId,
        UUID sourceLineId
    ) {
        if (quantity == null || quantity.signum() <= 0) {
            return;
        }
        BigDecimal cost = unitCost == null ? BigDecimal.ZERO : unitCost;
        BigDecimal totalCost = cost.multiply(quantity).setScale(2, RoundingMode.HALF_UP);
        jdbc.update("""
            INSERT INTO inventory.stock_movement (
                stock_movement_id, warehouse_id, product_id,
                product_sku, product_name,
                movement_type, direction, quantity,
                unit_cost, total_cost,
                source_type, source_id, source_line_id,
                movement_date
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            UUID.randomUUID(), warehouseId, productId,
            productSku, productName,
            movementType, direction, quantity,
            cost, totalCost,
            sourceType, sourceId, sourceLineId,
            Timestamp.from(Instant.now())
        );
    }
}
