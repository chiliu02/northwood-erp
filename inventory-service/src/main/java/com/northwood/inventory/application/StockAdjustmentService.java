package com.northwood.inventory.application;

import com.northwood.inventory.application.dto.AdjustStockCommand;
import com.northwood.inventory.application.dto.StockAdjustmentView;
import com.northwood.inventory.application.dto.StockBalanceView;
import com.northwood.inventory.domain.StockAdjustment;
import com.northwood.inventory.domain.StockAdjustmentId;
import com.northwood.inventory.domain.StockAdjustmentRepository;
import com.northwood.inventory.domain.StockMovementDirection;
import com.northwood.inventory.domain.StockMovementSourceTypes;
import com.northwood.inventory.domain.StockMovementType;
import com.northwood.inventory.domain.WarehouseCodes;
import com.northwood.shared.application.exception.BadRequestException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for manual stock adjustments (§2.29). Resolves the
 * requested change to a signed delta, persists a {@link StockAdjustment}
 * aggregate (which emits {@code inventory.StockAdjusted} for finance to post
 * the GL entry), moves {@code stock_balance.on_hand_quantity} via
 * {@link StockBalanceWriter}, and records the {@code stock_movement} audit row
 * — all in one transaction. The balance is always derived from the movement;
 * it is never set directly.
 *
 * <p>Public methods return {@link StockAdjustmentView} rather than the
 * aggregate.
 */
@Service
public class StockAdjustmentService {

    /**
     * Thrown when an adjustment can't be applied as requested — a no-op
     * (zero resulting delta), a negative {@code set} target, or a downward
     * adjustment that would push on-hand below the reserved quantity. Mapped
     * to HTTP 400 by the shared advice.
     */
    public static class StockAdjustmentRejectedException extends BadRequestException {
        public static final String CODE = "STOCK_ADJUSTMENT_REJECTED";
        public StockAdjustmentRejectedException(String message) {
            super(CODE, message);
        }
        @Override public Map<String, Object> params() {
            return Map.of();
        }
    }

    private static final Logger log = LoggerFactory.getLogger(StockAdjustmentService.class);

    private final StockAdjustmentRepository stockAdjustments;
    private final StockBalanceWriter stockBalances;
    private final StockBalanceLookup balanceLookup;
    private final StockMovementWriter movements;
    private final WarehouseLookup warehouses;
    private final ReplenishmentDetectionService replenishmentDetection;

    public StockAdjustmentService(
        StockAdjustmentRepository stockAdjustments,
        StockBalanceWriter stockBalances,
        StockBalanceLookup balanceLookup,
        StockMovementWriter movements,
        WarehouseLookup warehouses,
        ReplenishmentDetectionService replenishmentDetection
    ) {
        this.stockAdjustments = stockAdjustments;
        this.stockBalances = stockBalances;
        this.balanceLookup = balanceLookup;
        this.movements = movements;
        this.warehouses = warehouses;
        this.replenishmentDetection = replenishmentDetection;
    }

    @Transactional(readOnly = true)
    public Optional<StockAdjustmentView> findById(UUID id) {
        return stockAdjustments.findById(StockAdjustmentId.of(id)).map(StockAdjustmentView::from);
    }

    @Transactional(readOnly = true)
    public List<StockAdjustmentView> findAll() {
        return stockAdjustments.findAll().stream().map(StockAdjustmentView::from).toList();
    }

    /** Current on-hand / reserved / available for the screen's before→after preview. Zeros when no row exists. */
    @Transactional(readOnly = true)
    public StockBalanceView findBalance(UUID productId, String warehouseCode) {
        String code = warehouseCode == null ? WarehouseCodes.MAIN : warehouseCode;
        UUID warehouseId = warehouses.findIdByCode(code);
        return balanceLookup.findBalance(warehouseId, productId)
            .orElseGet(() -> StockBalanceView.empty(warehouseId, productId));
    }

    @Transactional
    public StockAdjustmentView adjust(AdjustStockCommand command) {
        String warehouseCode = command.warehouseCode() == null ? WarehouseCodes.MAIN : command.warehouseCode();
        UUID warehouseId = warehouses.findIdByCode(warehouseCode);

        BigDecimal signedDelta = resolveSignedDelta(command, warehouseId);
        if (signedDelta.signum() == 0) {
            throw new StockAdjustmentRejectedException(
                "Adjustment would not change on-hand stock (resulting delta is zero)");
        }

        StockMovementDirection direction =
            signedDelta.signum() > 0 ? StockMovementDirection.IN : StockMovementDirection.OUT;
        BigDecimal magnitude = signedDelta.abs();

        StockAdjustment adjustment = StockAdjustment.post(
            command.adjustmentNumber(),
            warehouseId, warehouseCode,
            command.productId(), command.productSku(), command.productName(),
            direction, magnitude, command.reason()
        );
        stockAdjustments.save(adjustment);

        if (direction == StockMovementDirection.IN) {
            stockBalances.bump(warehouseId, command.productId(), magnitude);
        } else if (!stockBalances.decrementOnHand(warehouseId, command.productId(), magnitude)) {
            throw new StockAdjustmentRejectedException(
                "Insufficient on-hand stock for a downward adjustment of %s — would leave less than the reserved quantity"
                    .formatted(magnitude.toPlainString()));
        }

        // §2.35 Slice B: if this decrement brings on_hand below reorder_point,
        // raise an inventory.ReplenishmentRequest. Only the OUT path can
        // breach the threshold — an upward adjustment never triggers
        // automatic replenishment.
        if (direction == StockMovementDirection.OUT) {
            replenishmentDetection.checkAfterOnHandDecrement(warehouseId, command.productId());
        }

        StockMovementType movementType = direction == StockMovementDirection.IN
            ? StockMovementType.STOCK_ADJUSTMENT_IN
            : StockMovementType.STOCK_ADJUSTMENT_OUT;
        movements.record(
            warehouseId, command.productId(), command.productSku(), command.productName(),
            movementType, direction, magnitude, null,
            StockMovementSourceTypes.STOCK_ADJUSTMENT, adjustment.id().value(), null
        );

        log.info("posted stock_adjustment {} for product={} at warehouse={}: {} {} ({})",
            adjustment.adjustmentNumber(), command.productId(), warehouseCode,
            direction.dbValue(), magnitude.toPlainString(), command.reason());
        return StockAdjustmentView.from(adjustment);
    }

    private BigDecimal resolveSignedDelta(AdjustStockCommand command, UUID warehouseId) {
        BigDecimal signedDelta;
        switch (command.mode()) {
            case DELTA -> signedDelta = command.value();
            case SET -> {
                if (command.value().signum() < 0) {
                    throw new StockAdjustmentRejectedException("Target on-hand quantity cannot be negative");
                }
                BigDecimal current = balanceLookup.findBalance(warehouseId, command.productId())
                    .map(StockBalanceView::onHand)
                    .orElse(BigDecimal.ZERO);
                signedDelta = command.value().subtract(current);
            }
            default -> throw new StockAdjustmentRejectedException("Unsupported adjustment mode: " + command.mode());
        }
        return signedDelta;
    }
}
