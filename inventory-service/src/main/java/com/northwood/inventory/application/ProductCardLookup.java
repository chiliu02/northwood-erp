package com.northwood.inventory.application;

import java.util.Optional;
import java.util.UUID;

/**
 * Read port for the make-vs-buy facet of the consolidated
 * {@code inventory.product_card} projection (§2.38). Split out from
 * {@link com.northwood.inventory.application.inbox.ProductCardProjection} per
 * the {@code *Projection}-vs-{@code *Lookup} rule ({@code docs/conventions.md})
 * — the projection is written only by inbox handlers; non-handler readers like
 * {@link com.northwood.inventory.application.replenishment.ReplenishmentDetectionService}
 * read through this lookup instead.
 *
 * <p>Returns {@link Optional#empty()} when the product has never been seen by
 * inventory — the §2.35 detection service treats {@link Optional#empty()} as
 * "unsourceable" and logs a warning rather than guessing a routing.
 *
 * <p>Application-side port; JDBC implementation lives in
 * {@code infrastructure/persistence/JdbcProductCardLookup}.
 */
public interface ProductCardLookup {

    Optional<Replenishment> findByProductId(UUID productId);

    record Replenishment(boolean isPurchased, boolean isManufactured) {}
}
