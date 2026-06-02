package com.northwood.inventory.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.northwood.inventory.application.StockAdjustmentService.StockAdjustmentRejectedException;
import com.northwood.inventory.application.dto.AdjustStockCommand;
import com.northwood.inventory.application.dto.AdjustStockCommand.Mode;
import com.northwood.inventory.application.dto.StockBalanceView;
import com.northwood.inventory.domain.StockAdjustmentRepository;
import com.northwood.inventory.domain.StockMovementDirection;
import com.northwood.inventory.domain.StockMovementSourceTypes;
import com.northwood.inventory.domain.StockMovementType;
import com.northwood.inventory.domain.WarehouseCodes;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StockAdjustmentServiceTest {

    @Mock StockAdjustmentRepository stockAdjustments;
    @Mock StockBalanceWriter stockBalances;
    @Mock StockBalanceLookup balanceLookup;
    @Mock StockMovementWriter movements;
    @Mock WarehouseLookup warehouses;
    @Mock ReplenishmentDetectionService replenishmentDetection;

    private StockAdjustmentService service;

    private static final UUID WAREHOUSE = UUID.randomUUID();
    private static final UUID PRODUCT = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new StockAdjustmentService(stockAdjustments, stockBalances, balanceLookup, movements, warehouses, replenishmentDetection);
    }

    private AdjustStockCommand cmd(String warehouseCode, Mode mode, BigDecimal value) {
        return new AdjustStockCommand("ADJ-001", PRODUCT, "SKU", "Widget", warehouseCode, mode, value, "cycle count");
    }

    @Test void delta_up_bumps_and_records_movement_in() {
        when(warehouses.findIdByCode(WarehouseCodes.MAIN)).thenReturn(WAREHOUSE);

        service.adjust(cmd(WarehouseCodes.MAIN, Mode.DELTA, new BigDecimal("25")));

        verify(stockAdjustments).save(any());
        verify(stockBalances).bump(WAREHOUSE, PRODUCT, new BigDecimal("25"));
        verify(movements).record(
            eq(WAREHOUSE), eq(PRODUCT), eq("SKU"), eq("Widget"),
            eq(StockMovementType.STOCK_ADJUSTMENT_IN), eq(StockMovementDirection.IN),
            eq(new BigDecimal("25")), isNull(),
            eq(StockMovementSourceTypes.STOCK_ADJUSTMENT), any(), isNull()
        );
        // Upward adjustments don't breach the reorder point.
        verify(replenishmentDetection, never()).checkAfterOnHandDecrement(any(), any());
    }

    @Test void delta_down_decrements_and_records_movement_out() {
        when(warehouses.findIdByCode(WarehouseCodes.MAIN)).thenReturn(WAREHOUSE);
        when(stockBalances.decrementOnHand(WAREHOUSE, PRODUCT, new BigDecimal("10"))).thenReturn(true);

        service.adjust(cmd(WarehouseCodes.MAIN, Mode.DELTA, new BigDecimal("-10")));

        verify(stockBalances).decrementOnHand(WAREHOUSE, PRODUCT, new BigDecimal("10"));
        verify(movements).record(
            eq(WAREHOUSE), eq(PRODUCT), eq("SKU"), eq("Widget"),
            eq(StockMovementType.STOCK_ADJUSTMENT_OUT), eq(StockMovementDirection.OUT),
            eq(new BigDecimal("10")), isNull(),
            eq(StockMovementSourceTypes.STOCK_ADJUSTMENT), any(), isNull()
        );
        // Downward adjustment must trigger the detection check.
        verify(replenishmentDetection).checkAfterOnHandDecrement(WAREHOUSE, PRODUCT);
    }

    @Test void delta_down_insufficient_on_hand_rejects_and_records_no_movement() {
        when(warehouses.findIdByCode(WarehouseCodes.MAIN)).thenReturn(WAREHOUSE);
        when(stockBalances.decrementOnHand(WAREHOUSE, PRODUCT, new BigDecimal("10"))).thenReturn(false);

        assertThatThrownBy(() -> service.adjust(cmd(WarehouseCodes.MAIN, Mode.DELTA, new BigDecimal("-10"))))
            .isInstanceOf(StockAdjustmentRejectedException.class);

        verifyNoInteractions(movements);
    }

    @Test void set_mode_adjusts_by_difference_from_current_on_hand() {
        when(warehouses.findIdByCode(WarehouseCodes.MAIN)).thenReturn(WAREHOUSE);
        when(balanceLookup.findBalance(WAREHOUSE, PRODUCT)).thenReturn(Optional.of(
            new StockBalanceView(WAREHOUSE, PRODUCT, new BigDecimal("30"), BigDecimal.ZERO, new BigDecimal("30"))));

        service.adjust(cmd(WarehouseCodes.MAIN, Mode.SET, new BigDecimal("100")));

        // target 100, current 30 → +70 (an upward adjustment)
        verify(stockBalances).bump(WAREHOUSE, PRODUCT, new BigDecimal("70"));
    }

    @Test void set_to_current_value_is_rejected_as_no_op() {
        when(warehouses.findIdByCode(WarehouseCodes.MAIN)).thenReturn(WAREHOUSE);
        when(balanceLookup.findBalance(WAREHOUSE, PRODUCT)).thenReturn(Optional.of(
            new StockBalanceView(WAREHOUSE, PRODUCT, new BigDecimal("30"), BigDecimal.ZERO, new BigDecimal("30"))));

        assertThatThrownBy(() -> service.adjust(cmd(WarehouseCodes.MAIN, Mode.SET, new BigDecimal("30"))))
            .isInstanceOf(StockAdjustmentRejectedException.class);

        verify(stockAdjustments, never()).save(any());
        verifyNoInteractions(movements);
    }

    @Test void set_negative_target_is_rejected() {
        when(warehouses.findIdByCode(WarehouseCodes.MAIN)).thenReturn(WAREHOUSE);

        assertThatThrownBy(() -> service.adjust(cmd(WarehouseCodes.MAIN, Mode.SET, new BigDecimal("-5"))))
            .isInstanceOf(StockAdjustmentRejectedException.class);

        verify(stockAdjustments, never()).save(any());
    }

    @Test void null_warehouse_code_defaults_to_MAIN() {
        when(warehouses.findIdByCode(WarehouseCodes.MAIN)).thenReturn(WAREHOUSE);

        service.adjust(cmd(null, Mode.DELTA, new BigDecimal("5")));

        verify(warehouses).findIdByCode(WarehouseCodes.MAIN);
    }
}
