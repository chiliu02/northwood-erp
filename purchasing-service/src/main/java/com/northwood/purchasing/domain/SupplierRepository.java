package com.northwood.purchasing.domain;

import java.util.List;
import java.util.Optional;

/**
 * DDD repository for the {@link Supplier} aggregate — load + save, draining
 * {@code pendingEvents} to the outbox on {@link #save}. Promoted from the former
 * read-only {@code SupplierQueryPort} on 2026-06-03 when supplier onboarding /
 * editing arrived (per that port's own "promote back to *Repository when
 * mutators arrive" note). Keeps the lookup finders the PO-creation supplier
 * selection relies on.
 */
public interface SupplierRepository {

    Optional<Supplier> findById(SupplierId id);

    Optional<Supplier> findByCode(String supplierCode);

    /** True if a supplier with this code already exists (uniqueness pre-check for onboarding). */
    boolean existsByCode(String supplierCode);

    /** All suppliers, ordered by {@code supplier_code}. */
    List<Supplier> findAll();

    /**
     * Default supplier for shortage-driven requisitions when no specific
     * supplier is suggested. First {@code active} supplier by {@code supplier_code};
     * throws if none exist (every install seeds SUP-001).
     */
    Supplier defaultSupplier();

    /** Insert (version 0) or version-guarded update; drains pending events to the outbox. */
    void save(Supplier supplier);
}
