package com.northwood.purchasing.application;

import com.northwood.purchasing.domain.Supplier;
import com.northwood.purchasing.domain.SupplierId;
import java.util.List;
import java.util.Optional;

/**
 * Read-side query port for suppliers. Edit commands (create / update / status
 * changes) land in a later slice when supplier onboarding becomes a user story.
 *
 * <p>Lives in {@code application/} per the {@code *QueryPort} convention —
 * {@link Supplier} has no mutators, no {@code pendingEvents}, and no events
 * today, so it is not a DDD aggregate root and the surrounding port is not a
 * DDD {@code *Repository}. Promote back to {@code *Repository} if and when
 * mutators arrive.
 */
public interface SupplierQueryPort {

    Optional<Supplier> findById(SupplierId id);

    Optional<Supplier> findByCode(String supplierCode);

    /** All suppliers, ordered by {@code supplier_code}. */
    List<Supplier> findAll();

    /**
     * Default supplier used by shortage-driven requisitions when no specific
     * supplier is suggested. Returns the first active supplier ordered by
     * {@code supplier_code}; throws if none exist (every install seeds
     * SUP-001 from northwood_erp.sql).
     */
    Supplier defaultSupplier();
}
