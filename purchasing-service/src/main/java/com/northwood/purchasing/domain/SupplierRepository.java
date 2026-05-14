package com.northwood.purchasing.domain;

import java.util.List;
import java.util.Optional;

public interface SupplierRepository {

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
