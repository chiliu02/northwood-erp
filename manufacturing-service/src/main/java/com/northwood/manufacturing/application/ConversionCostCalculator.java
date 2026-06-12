package com.northwood.manufacturing.application;

import com.northwood.manufacturing.domain.RoutingOperation;
import com.northwood.manufacturing.domain.WorkOrderOperation;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Computes a product's per-unit conversion cost (labour + overhead) from its
 * active routing × work-centre rates: Σ over operations of
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

    /**
     * Per-unit ACTUAL conversion cost from a work order's (completed)
     * operations: Σ {@code op.actualMinutes × (labour + overhead rate)} for the
     * operation's work centre. Interpreted per-unit —
     * the same basis as {@link #perUnitConversionCost} — so the caller
     * multiplies by completed quantity. The efficiency variance is the
     * difference between this and the standard (planned-minutes) conversion;
     * Northwood logs actual minutes but not an actual wage rate, so this is an
     * efficiency variance only (minutes), not a rate/spending variance.
     */
    public BigDecimal actualConversionPerUnit(List<WorkOrderOperation> operations) {
        BigDecimal total = BigDecimal.ZERO;
        for (WorkOrderOperation op : operations) {
            WorkCenterRateLookup.Rates rates = workCenterRates
                .findByWorkCenterId(op.workCenterId())
                .orElse(WorkCenterRateLookup.Rates.ZERO);
            total = total.add(nullToZero(op.actualMinutes()).multiply(rates.totalPerMinute()));
        }
        return total.setScale(6, RoundingMode.HALF_UP);
    }

    private static BigDecimal nullToZero(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
