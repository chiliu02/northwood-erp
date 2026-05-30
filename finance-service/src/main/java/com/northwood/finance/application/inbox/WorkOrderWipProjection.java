package com.northwood.finance.application.inbox;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * §2.42 Perpetual-WIP sub-ledger projection over {@code finance.work_order_wip}.
 * Written only by the three WIP inbox handlers in this package; carries the
 * running standard-cost WIP value per work order plus the idempotency gates for
 * the two posting legs (charge raw materials once; complete once).
 *
 * <p>A running total of cost deltas, so a projection, not an aggregate
 * ({@code docs/conventions.md} → deltas get aggregates, totals get projections).
 * The JDBC implementation lives in
 * {@code infrastructure/persistence/JdbcWorkOrderWipProjection}.
 */
public interface WorkOrderWipProjection {

    /**
     * Charge raw materials into a work order's WIP at standard cost. Idempotent
     * gate: returns {@code true} the first time (stamps {@code materials_charged_at}
     * and adds {@code amount} to the running value), {@code false} on a repeat —
     * a re-reserved work order (shortage-recovery) must not charge WIP twice.
     */
    boolean chargeRawMaterials(UUID workOrderId, BigDecimal amount);

    /**
     * Roll consumed sub-assembly value into a parent work order's WIP (upsert;
     * adds {@code amount} to the running value). Idempotency is the inbox dedup
     * of the once-per-parent {@code SubAssembliesConsumed} event.
     */
    void rollInSubAssemblies(UUID workOrderId, BigDecimal amount);

    /**
     * Mark a work order completed and stamp the finished good. Idempotent gate:
     * returns {@code true} the first time (stamps {@code completed_at}),
     * {@code false} on a repeat. Upserts so it is robust to a completion event
     * that races ahead of the materials charge.
     */
    boolean markCompleted(UUID workOrderId, UUID finishedProductId);
}
