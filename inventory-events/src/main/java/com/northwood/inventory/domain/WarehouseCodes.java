package com.northwood.inventory.domain;

/**
 * Wire-format constants for the {@code inventory.warehouse.warehouse_code}
 * business key. One row per code; consumers resolve code → {@code warehouse_id}
 * via {@code WarehouseLookup.findIdByCode}.
 *
 * <p>Same reference-data-identifier shape as {@code FinanceAccountCodes} and
 * {@code StockMovementSourceTypes}: names a specific row by business key.
 * Hosted in {@code inventory-events} because warehouse is inventory's
 * aggregate, and consumers in other services (sales, manufacturing, finance
 * via test harness) need to compile against the same codes.
 *
 * <p><b>Phase-1 simplification.</b> {@link #MAIN} is the demo's single
 * warehouse. Saga workers, inbox handlers, and service-layer default-fallback
 * sites pin to it because the schema doesn't yet carry "which warehouse"
 * metadata on orders / routings. When real warehouse selection lands
 * (per-order ship-from, per-routing build-at), the saga-worker /
 * inbox-handler references become bugs to clean up; Find Usages on
 * {@code WarehouseCodes.MAIN} surfaces every site that needs revisiting.
 */
public final class WarehouseCodes {

    /**
     * Demo's default (and currently only) warehouse. Phase-1 default —
     * see class-level Javadoc for the cleanup path.
     */
    public static final String MAIN = "MAIN";

    private WarehouseCodes() {}
}
