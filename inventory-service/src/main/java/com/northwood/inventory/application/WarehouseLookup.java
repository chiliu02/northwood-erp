package com.northwood.inventory.application;

import java.util.UUID;

/**
 * Resolves a warehouse code (e.g. {@code "MAIN"}) to its {@code warehouse_id}.
 * Tiny narrow lookup: one method, one row, one column. Per the project's
 * five-suffix convention, {@code *Lookup} signals "narrow operational value
 * resolution" — distinct from a {@code *QueryPort} (whole rows / lists) or a
 * {@code *Repository} (aggregate read+write).
 *
 * <p>Application-side port; JDBC implementation lives in
 * {@code infrastructure/persistence/JdbcWarehouseLookup}.
 */
public interface WarehouseLookup {

    UUID findIdByCode(String warehouseCode);
}
