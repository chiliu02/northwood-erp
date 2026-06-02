package com.northwood.inventory.infrastructure.persistence;

import com.northwood.inventory.application.WipBalanceWriter;
import java.math.BigDecimal;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * JDBC adapter for {@link WipBalanceWriter}.
 *
 * <p>The current showcase doesn't carry warehouse on the consume event — the
 * parent produces FG into the same warehouse the children produced their
 * sub-assemblies into — so {@link #decrement(UUID, BigDecimal)} touches every
 * WIP row for the product (typically one). Multi-warehouse sub-assembly is
 * out of scope.
 *
 * <p>PK passed explicitly per CLAUDE.md "Reference data and seed UUIDs".
 */
@Repository
public class JdbcWipBalanceWriter implements WipBalanceWriter {

    private static final Logger log = LoggerFactory.getLogger(JdbcWipBalanceWriter.class);

    private final JdbcTemplate jdbc;

    public JdbcWipBalanceWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void bump(UUID warehouseId, UUID productId, BigDecimal quantity) {
        if (quantity == null || quantity.signum() <= 0) {
            return;
        }
        int rows = jdbc.update("""
            UPDATE inventory.wip_balance
               SET on_hand_quantity = on_hand_quantity + ?,
                   version = version + 1
             WHERE warehouse_id = ? AND product_id = ?
            """,
            quantity, warehouseId, productId
        );
        if (rows == 0) {
            jdbc.update("""
                INSERT INTO inventory.wip_balance (
                    wip_balance_id, warehouse_id, product_id,
                    on_hand_quantity, average_cost
                ) VALUES (?, ?, ?, ?, 0)
                """,
                UUID.randomUUID(), warehouseId, productId, quantity
            );
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void decrement(UUID productId, BigDecimal quantity) {
        if (quantity == null || quantity.signum() <= 0) {
            return;
        }
        // Guard the subtraction so a duplicate / over-consume can't drive WIP
        // on_hand_quantity below 0 (CHECK on_hand_quantity >= 0) and wedge the
        // consumer with a 23514. A breach matches 0 rows and is logged.
        int rows = jdbc.update("""
            UPDATE inventory.wip_balance
               SET on_hand_quantity = on_hand_quantity - ?,
                   version = version + 1
             WHERE product_id = ? AND on_hand_quantity >= ?
            """,
            quantity, productId, quantity
        );
        if (rows == 0) {
            log.warn("wip decrement no-op: consuming {} but WIP on_hand_quantity is lower (or no row) "
                + "for product={}", quantity, productId);
        }
    }
}
