package com.northwood.inventory.application.inbox;

import com.northwood.product.domain.ProductType;
import java.util.Optional;
import java.util.UUID;

/**
 * Maintains and reads the {@code inventory.product_card} projection — the
 * consumer-side denormalized cache of product-master make-vs-buy facts inventory
 * owns locally (the {@code _card} convention, {@code docs/conventions.md}).
 * Mirrors {@link com.northwood.manufacturing.application.inbox.ProductReplenishmentProjection}
 * — duplicate projection across services is the accepted cost of cross-schema
 * isolation. Inventory needs its own local snapshot of make-vs-buy so the
 * §2.35 reorder-point detection service can decide whether to route a
 * replenishment to manufacturing or purchasing without a cross-service call.
 *
 * <p>Pure upsert on write; the inbox dedupes redeliveries and partition keys
 * preserve per-product event order on the bus, so latest-wins is naturally
 * correct (no version column on the projection).
 *
 * <p>Read path: {@link #findByProductId(UUID)} returns the row's flags or
 * {@link Optional#empty()} when the product has never been seen by inventory
 * — the §2.35 detection service treats {@link Optional#empty()} as
 * "unsourceable" and logs a warning rather than guessing a routing.
 *
 * <p>Application-side port; JDBC implementation lives in
 * {@code infrastructure/persistence/JdbcProductCardProjection}.
 */
public interface ProductCardProjection {

    /**
     * Seed a default row at product registration so the §2.35 detection
     * service has non-empty make-vs-buy flags for day-zero SKUs before any
     * {@code MakeVsBuyChanged} event arrives. Mapping computed by
     * {@link #defaultsFor}.
     *
     * <p>Insert-only — an out-of-order {@code MakeVsBuyChanged} that arrived
     * first wins; the seed is a default, not an authority. JDBC uses
     * {@code ON CONFLICT DO NOTHING}.
     */
    void seedDefaultsFromProductType(UUID productId, String productType);

    void applyMakeVsBuy(UUID productId, boolean isPurchased, boolean isManufactured);

    /**
     * Retire the product from inventory's replenishment routing. Flips both
     * flags to {@code false} so the §2.35 detection service classifies the
     * SKU as unsourceable (logs + skips) rather than dispatching a
     * replenishment. Stamps {@code discontinued_at} as the authoritative
     * signal — flags-pair {@code (false, false)} is ambiguous with a
     * never-classified row, so consumers that need to distinguish
     * "discontinued" from "unclassified" must read this column.
     */
    void applyDiscontinued(UUID productId);

    Optional<Replenishment> findByProductId(UUID productId);

    record Replenishment(boolean isPurchased, boolean isManufactured) {}

    /**
     * Default make-vs-buy flags derived from a product type:
     * <ul>
     *   <li>{@link ProductType#RAW_MATERIAL}, {@link ProductType#SERVICE}
     *       — buy-only ({@code purchased=true}, {@code manufactured=false}).</li>
     *   <li>{@link ProductType#FINISHED_GOOD},
     *       {@link ProductType#SEMI_FINISHED_GOOD} — make-only
     *       ({@code purchased=false}, {@code manufactured=true}).</li>
     *   <li>Null / unknown — both flags true (sourceable either way; user
     *       can refine via {@link #applyMakeVsBuy}).</li>
     * </ul>
     *
     * <p>Same mapping as
     * {@link com.northwood.manufacturing.application.inbox.ProductReplenishmentProjection#defaultsFor}.
     * Duplicated rather than shared because cross-service domain imports are
     * forbidden (schema-per-service rule).
     */
    static Replenishment defaultsFor(String productType) {
        if (ProductType.RAW_MATERIAL.dbValue().equals(productType)
            || ProductType.SERVICE.dbValue().equals(productType)) {
            return new Replenishment(true, false);
        }
        if (ProductType.FINISHED_GOOD.dbValue().equals(productType)
            || ProductType.SEMI_FINISHED_GOOD.dbValue().equals(productType)) {
            return new Replenishment(false, true);
        }
        return new Replenishment(true, true);
    }
}
