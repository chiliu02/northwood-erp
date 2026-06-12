package com.northwood.manufacturing.application;

import com.northwood.manufacturing.domain.RoutingOperation;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Computes a product's per-unit conversion cost (labour + overhead) from its
 * active routing × work-centre rates (dev-todo §2.42): Σ over operations of
 * {@code (setup + run minutes) × (labour + overhead rate)} for the operation's
 * work centre.
 *
 * <p>Single source of truth so the two consumers stay in lockstep — the cost
 * rollup ({@link MaterialsCostRollupService}, which folds it into the standard
 * cost) and the WIP conversion charge ({@code WorkOrderOperationService}, which
 * charges it into WIP at completion). If the two ever diverged, WIP would not
 * net to zero, so they MUST compute it identically. Zero when the product has
 * no active routing (raws, purchased items) or its work centres carry no rates.
 */
@Service
public class ConversionCostCalculator {

    private final RoutingQueryPort routings;
    private final WorkCenterRateLookup workCenterRates;

    public ConversionCostCalculator(RoutingQueryPort routings, WorkCenterRateLookup workCenterRates) {
        this.routings = routings;
        this.workCenterRates = workCenterRates;
    }

    public BigDecimal perUnitConversionCost(UUID productId) {
        return routings.findActiveByFinishedProductId(productId)
            .map(routing -> {
                BigDecimal total = BigDecimal.ZERO;
                for (RoutingOperation op : routing.operations()) {
                    WorkCenterRateLookup.Rates rates = workCenterRates
                        .findByWorkCenterId(op.workCenterId())
                        .orElse(WorkCenterRateLookup.Rates.ZERO);
                    BigDecimal minutes = nullToZero(op.plannedSetupMinutes())
                        .add(nullToZero(op.plannedRunMinutes()));
                    total = total.add(minutes.multiply(rates.totalPerMinute()));
                }
                return total.setScale(6, RoundingMode.HALF_UP);
            })
            .orElse(BigDecimal.ZERO);
    }

    private static BigDecimal nullToZero(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
