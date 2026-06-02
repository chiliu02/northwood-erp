package com.northwood.manufacturing.domain;

import java.util.Optional;

/**
 * DDD Repository for the {@link Bom} aggregate root. Promoted from a
 * row-level write port ({@code BomEditRepository}) 2026-05-16 —
 * see {@link Bom}'s class Javadoc for the rationale.
 *
 * <p>{@link #save} persists header + line diff in one transaction:
 * <ul>
 *   <li>For a newly-drafted aggregate ({@code aggregateVersion == 0}): INSERT
 *       header at {@code status='draft'}, INSERT every line currently in
 *       {@link Bom#pullAddedLines()}.</li>
 *   <li>For a reconstituted aggregate ({@code aggregateVersion > 0}): UPDATE
 *       header (status + bumped {@code row_version}) with optimistic-concurrency
 *       on the previous version, INSERT lines from {@link Bom#pullAddedLines()},
 *       DELETE lines from {@link Bom#pullRemovedLineIds()}.</li>
 * </ul>
 *
 * <p>Drains {@link Bom#pullPendingEvents()} to the outbox in the same
 * transaction.
 */
public interface BomRepository {

    Optional<Bom> findById(BomId id);

    /**
     * Resolve a {@link BomLineId} to its owning {@link BomId}. Used by the
     * application service's {@code removeLine} flow where the controller
     * passes a bare bom_line_id without the parent header id.
     */
    Optional<BomId> findBomIdByLineId(BomLineId bomLineId);

    void save(Bom bom);
}
