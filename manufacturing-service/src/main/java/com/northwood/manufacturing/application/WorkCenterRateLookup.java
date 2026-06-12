package com.northwood.manufacturing.application;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * Narrow lookup of a work centre's conversion-cost rates (labour + overhead,
 * per minute) from {@code manufacturing.work_center}. Used by
 * {@link MaterialsCostRollupService} to roll a product's own-routing conversion
 * cost into its standard cost (dev-todo §2.42): for each active-routing
 * operation, conversion = (setup + run minutes) × (labour + overhead rate) of
 * the operation's work centre.
 *
 * <p>{@code *Lookup} per the naming convention — a single-method read of a
 * reference-data value, not an aggregate read.
 */
public interface WorkCenterRateLookup {

    Optional<Rates> findByWorkCenterId(UUID workCenterId);

    /** Per-minute labour + overhead rates. */
    record Rates(BigDecimal labourRatePerMinute, BigDecimal overheadRatePerMinute) {

        public static final Rates ZERO = new Rates(BigDecimal.ZERO, BigDecimal.ZERO);

        /** Combined conversion rate per minute (labour + overhead). */
        public BigDecimal totalPerMinute() {
            return labourRatePerMinute.add(overheadRatePerMinute);
        }
    }
}
