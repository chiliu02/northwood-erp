package com.northwood.manufacturing.domain;

import java.util.UUID;

/**
 * Read port that walks the active-BOM composition graph to decide whether a
 * proposed change would introduce a cycle. The walk follows
 * {@code finished_product_id ← bom_header (status=active) ← bom_line.component_product_id}
 * recursively from a starting product until either {@code targetProductId}
 * is reached (cycle) or the frontier exhausts (no cycle).
 *
 * <p>Implementations must run inside the same transaction as the proposed
 * mutation so that a concurrent insert can't slip a cycle in between detection
 * and commit.
 */
public interface BomCycleDetector {

    /**
     * @param startProductId  the product whose subtree is being explored — for
     *                        an addLine check, this is the proposed
     *                        {@code component_product_id}; for an activation
     *                        check, the candidate BOM's {@code finished_product_id}.
     * @param targetProductId the product whose appearance in the descendant set
     *                        constitutes a cycle — for both addLine and
     *                        activation, this is the BOM's
     *                        {@code finished_product_id}.
     * @param candidateActiveBomHeaderId an additional BOM header to treat as
     *                                   active during the walk (used by
     *                                   activation checks where the BOM in
     *                                   question is still {@code 'draft'}).
     *                                   {@code null} for addLine checks.
     */
    boolean wouldCreateCycle(
        UUID startProductId,
        UUID targetProductId,
        UUID candidateActiveBomHeaderId
    );
}
