package com.northwood.testharness.inmemory.manufacturing;

import com.northwood.manufacturing.application.WorkCenterRateLookup;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * In-memory {@link WorkCenterRateLookup}. Seedable per work centre via
 * {@link #put(UUID, BigDecimal, BigDecimal)}; unseeded ids return empty (the
 * rollup treats that as zero conversion).
 */
public final class InMemoryWorkCenterRateLookup implements WorkCenterRateLookup {

    private final Map<UUID, Rates> byWorkCenterId = new HashMap<>();

    public InMemoryWorkCenterRateLookup put(
        UUID workCenterId,
        BigDecimal labourRatePerMinute,
        BigDecimal overheadRatePerMinute
    ) {
        byWorkCenterId.put(workCenterId, new Rates(labourRatePerMinute, overheadRatePerMinute));
        return this;
    }

    @Override
    public Optional<Rates> findByWorkCenterId(UUID workCenterId) {
        return Optional.ofNullable(byWorkCenterId.get(workCenterId));
    }
}
