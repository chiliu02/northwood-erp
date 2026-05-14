package com.northwood.inventory.application;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Upsert helper for {@code inventory.wip_balance.on_hand_quantity} —
 * sub-assembly production produces into WIP (not shippable FG stock); a
 * parent work-order consuming its children decrements WIP. Decrement is
 * bounded by the table's non-negative CHECK on {@code on_hand_quantity};
 * if a redelivery slips past the inbox dedupe and underflows, Postgres
 * rejects rather than silently going negative.
 *
 * <p>Application-side port; JDBC implementation lives in
 * {@code infrastructure/persistence/JdbcWipBalanceWriter}.
 */
public interface WipBalanceWriter {

    void bump(UUID warehouseId, UUID productId, BigDecimal quantity);

    void decrement(UUID productId, BigDecimal quantity);
}
