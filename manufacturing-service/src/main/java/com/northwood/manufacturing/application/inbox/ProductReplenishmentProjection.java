package com.northwood.manufacturing.application.inbox;

import com.northwood.product.domain.ProductType;
import java.util.Optional;
import java.util.UUID;

/**
 * Maintains and reads the {@code manufacturing.product_card}
 * projection. Pure upsert on write; the inbox dedupes redeliveries and
 * partition keys preserve per-product event order on the bus, so latest-
 * wins is naturally correct (no version column on the projection).
 *
 * <p>Read path: {@link #findByProductId(UUID)} returns the row's flags or
 * {@link Optional#empty()} when the product has never been classified.
 * Callers (today: {@code ManufacturingRequestedHandler}) treat
 * {@code Optional.empty()} as "rejected" — newly registered products must
 * call {@code setMakeVsBuy} explicitly before manufacturing will accept them.
 *
 * <p>Application-side port; JDBC implementation lives in
 * {@code infrastructure/persistence/JdbcProductReplenishmentProjection}.
 */
public interface ProductReplenishmentProjection {

    /**
     * Seed a default row at product registration so the
     * {@code findByProductId.empty() == reject} default no longer rejects
     * newly registered products. Mapping computed by {@link #defaultsFor}.
     *
     * <p>Insert-only — an out-of-order {@code MakeVsBuyChanged} that arrived
     * first wins; the seed is a default, not an authority. JDBC uses
     * {@code ON CONFLICT DO NOTHING}; the in-memory stub uses
     * {@code putIfAbsent}.
     */
    void seedDefaultsFromProductType(UUID productId, String productType);

    void applyMakeVsBuy(UUID productId, boolean isPurchased, boolean isManufactured);

    /**
     * Retire the product from manufacturing. Flips both flags to
     * {@code false} so {@code ManufacturingRequestedHandler}'s
     * {@code !isManufactured()} guard rejects subsequent lines, and so the
     * cost-rollup engine treats the product as buy-blocked too. Equivalent
     * to {@code applyMakeVsBuy(productId, false, false)} — the dedicated
     * method exists so the call site reads "discontinue" rather than
     * "make-vs-buy with both off", which is a semantically different signal.
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
     */
    static Replenishment defaultsFor(String productType) {
        if (ProductType.RAW_MATERIAL.code().equals(productType)
            || ProductType.SERVICE.code().equals(productType)) {
            return new Replenishment(true, false);
        }
        if (ProductType.FINISHED_GOOD.code().equals(productType)
            || ProductType.SEMI_FINISHED_GOOD.code().equals(productType)) {
            return new Replenishment(false, true);
        }
        return new Replenishment(true, true);
    }
}
