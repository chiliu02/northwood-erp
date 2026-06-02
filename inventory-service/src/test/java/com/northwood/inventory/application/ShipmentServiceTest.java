package com.northwood.inventory.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.northwood.inventory.application.ShipmentService.ShipmentLineProductMismatchException;
import com.northwood.inventory.application.ShipmentService.UnpaidUpfrontOrderException;
import com.northwood.inventory.application.dto.PostShipmentCommand;
import com.northwood.inventory.application.dto.ShipmentLineRequest;
import com.northwood.inventory.application.inbox.SalesOrderLineFactsProjection;
import com.northwood.inventory.application.inbox.SalesOrderLineFactsProjection.UpfrontPaymentGate;
import com.northwood.inventory.domain.ShipmentRepository;
import com.northwood.inventory.domain.StockMovementDirection;
import com.northwood.inventory.domain.StockMovementSourceTypes;
import com.northwood.inventory.domain.StockMovementType;
import com.northwood.inventory.domain.WarehouseCodes;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ShipmentServiceTest {

    @Mock ShipmentRepository shipments;
    @Mock StockBalanceWriter stockBalances;
    @Mock StockMovementWriter movements;
    @Mock WarehouseLookup warehouses;
    @Mock SalesOrderLineFactsProjection salesOrderLineFacts;
    @Mock ReplenishmentDetectionService replenishmentDetection;

    private ShipmentService service;

    private static final UUID WAREHOUSE = UUID.randomUUID();
    private static final UUID SO = UUID.randomUUID();
    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final UUID PRODUCT_1 = UUID.randomUUID();
    private static final UUID PRODUCT_2 = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ShipmentService(shipments, stockBalances, movements, warehouses, salesOrderLineFacts, replenishmentDetection);
    }

    private PostShipmentCommand cmd(String warehouseCode, List<ShipmentLineRequest> lines) {
        return new PostShipmentCommand("SHP-001", SO, CUSTOMER, "Acme", warehouseCode, lines);
    }

    @Test void single_line_decrements_stock_and_records_movement() {
        when(warehouses.findIdByCode(WarehouseCodes.MAIN)).thenReturn(WAREHOUSE);

        // Unlinked line (null salesOrderLineId) skips validation — focus is on stock side effects.
        service.post(cmd(WarehouseCodes.MAIN, List.of(
            new ShipmentLineRequest(null, PRODUCT_1, "SKU-1", "Product 1",
                new BigDecimal("3"), new BigDecimal("10.00"))
        )));

        verify(shipments).save(any());
        verify(stockBalances).decrementOnHandAndReleaseReserved(WAREHOUSE, PRODUCT_1, new BigDecimal("3"));
        verify(movements).record(
            eq(WAREHOUSE), eq(PRODUCT_1), eq("SKU-1"), eq("Product 1"),
            eq(StockMovementType.SALES_SHIPMENT), eq(StockMovementDirection.OUT),
            eq(new BigDecimal("3")), eq(new BigDecimal("10.00")),
            eq(StockMovementSourceTypes.SHIPMENT), any(), any()
        );
    }

    @Test void multiple_lines_decrement_each_independently() {
        when(warehouses.findIdByCode(WarehouseCodes.MAIN)).thenReturn(WAREHOUSE);

        service.post(cmd(WarehouseCodes.MAIN, List.of(
            new ShipmentLineRequest(null, PRODUCT_1, "SKU-1", "P1",
                new BigDecimal("3"), new BigDecimal("10.00")),
            new ShipmentLineRequest(null, PRODUCT_2, "SKU-2", "P2",
                new BigDecimal("5"), new BigDecimal("20.00"))
        )));

        verify(stockBalances).decrementOnHandAndReleaseReserved(WAREHOUSE, PRODUCT_1, new BigDecimal("3"));
        verify(stockBalances).decrementOnHandAndReleaseReserved(WAREHOUSE, PRODUCT_2, new BigDecimal("5"));
        verify(movements, times(2)).record(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test void slice_2_35_replenishment_detection_fires_once_per_shipped_line() {
        when(warehouses.findIdByCode(WarehouseCodes.MAIN)).thenReturn(WAREHOUSE);

        service.post(cmd(WarehouseCodes.MAIN, List.of(
            new ShipmentLineRequest(null, PRODUCT_1, "SKU-1", "P1",
                new BigDecimal("3"), new BigDecimal("10.00")),
            new ShipmentLineRequest(null, PRODUCT_2, "SKU-2", "P2",
                new BigDecimal("5"), new BigDecimal("20.00"))
        )));

        verify(replenishmentDetection).checkAfterOnHandDecrement(WAREHOUSE, PRODUCT_1);
        verify(replenishmentDetection).checkAfterOnHandDecrement(WAREHOUSE, PRODUCT_2);
    }

    @Test void null_warehouse_code_defaults_to_MAIN() {
        when(warehouses.findIdByCode(WarehouseCodes.MAIN)).thenReturn(WAREHOUSE);

        service.post(cmd(null, List.of(
            new ShipmentLineRequest(null, PRODUCT_1, "SKU", "P",
                new BigDecimal("1"), null)
        )));

        verify(warehouses).findIdByCode(WarehouseCodes.MAIN);
    }

    @Test void null_unit_cost_treated_as_zero() {
        when(warehouses.findIdByCode(WarehouseCodes.MAIN)).thenReturn(WAREHOUSE);

        service.post(cmd(WarehouseCodes.MAIN, List.of(
            new ShipmentLineRequest(null, PRODUCT_1, "SKU", "P",
                new BigDecimal("2"), null)
        )));

        verify(movements).record(
            any(), any(), any(), any(), any(), any(),
            eq(new BigDecimal("2")), eq(BigDecimal.ZERO),
            any(), any(), any()
        );
    }

    @Test void linked_line_with_matching_product_passes_validation() {
        when(warehouses.findIdByCode(WarehouseCodes.MAIN)).thenReturn(WAREHOUSE);
        UUID soLineId = UUID.randomUUID();
        when(salesOrderLineFacts.findProductIdForLine(soLineId)).thenReturn(Optional.of(PRODUCT_1));

        service.post(cmd(WarehouseCodes.MAIN, List.of(
            new ShipmentLineRequest(soLineId, PRODUCT_1, "SKU-1", "P1",
                new BigDecimal("3"), new BigDecimal("10.00"))
        )));

        verify(shipments).save(any());
        verify(stockBalances).decrementOnHandAndReleaseReserved(WAREHOUSE, PRODUCT_1, new BigDecimal("3"));
    }

    @Test void linked_line_with_mismatched_product_rejects_with_400_exception() {
        when(warehouses.findIdByCode(WarehouseCodes.MAIN)).thenReturn(WAREHOUSE);
        UUID soLineId = UUID.randomUUID();
        when(salesOrderLineFacts.findProductIdForLine(soLineId)).thenReturn(Optional.of(PRODUCT_1));

        assertThatThrownBy(() -> service.post(cmd(WarehouseCodes.MAIN, List.of(
            new ShipmentLineRequest(soLineId, PRODUCT_2, "SKU-2", "P2",
                new BigDecimal("3"), new BigDecimal("10.00"))
        ))))
            .isInstanceOf(ShipmentLineProductMismatchException.class)
            .hasMessageContaining(soLineId.toString())
            .hasMessageContaining(PRODUCT_1.toString())
            .hasMessageContaining(PRODUCT_2.toString());

        verify(shipments, never()).save(any());
        verify(stockBalances, never()).decrementOnHandAndReleaseReserved(any(), any(), any());
    }

    @Test void linked_line_with_unknown_so_line_id_rejects_with_400_exception() {
        when(warehouses.findIdByCode(WarehouseCodes.MAIN)).thenReturn(WAREHOUSE);
        UUID soLineId = UUID.randomUUID();
        when(salesOrderLineFacts.findProductIdForLine(soLineId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.post(cmd(WarehouseCodes.MAIN, List.of(
            new ShipmentLineRequest(soLineId, PRODUCT_1, "SKU-1", "P1",
                new BigDecimal("3"), new BigDecimal("10.00"))
        ))))
            .isInstanceOf(ShipmentLineProductMismatchException.class)
            .hasMessageContaining("Unknown sales_order_line_id")
            .hasMessageContaining(soLineId.toString());

        verify(shipments, never()).save(any());
        verify(stockBalances, never()).decrementOnHandAndReleaseReserved(any(), any(), any());
    }

    // Prepayment shipment gate.

    @Test void prepayment_order_not_yet_settled_rejects_with_409_exception() {
        when(salesOrderLineFacts.findUpfrontPaymentGate(SO))
            .thenReturn(Optional.of(new UpfrontPaymentGate("prepayment", false)));

        assertThatThrownBy(() -> service.post(cmd(WarehouseCodes.MAIN, List.of(
            new ShipmentLineRequest(null, PRODUCT_1, "SKU", "P",
                new BigDecimal("1"), new BigDecimal("10.00"))
        ))))
            .isInstanceOf(UnpaidUpfrontOrderException.class)
            .hasMessageContaining(SO.toString());

        verify(shipments, never()).save(any());
        verify(stockBalances, never()).decrementOnHandAndReleaseReserved(any(), any(), any());
    }

    @Test void prepayment_order_settled_proceeds_normally() {
        when(warehouses.findIdByCode(WarehouseCodes.MAIN)).thenReturn(WAREHOUSE);
        when(salesOrderLineFacts.findUpfrontPaymentGate(SO))
            .thenReturn(Optional.of(new UpfrontPaymentGate("prepayment", true)));

        service.post(cmd(WarehouseCodes.MAIN, List.of(
            new ShipmentLineRequest(null, PRODUCT_1, "SKU", "P",
                new BigDecimal("1"), new BigDecimal("10.00"))
        )));

        verify(shipments).save(any());
        verify(stockBalances).decrementOnHandAndReleaseReserved(WAREHOUSE, PRODUCT_1, new BigDecimal("1"));
    }

    @Test void on_shipment_order_passes_gate_regardless_of_settled_flag() {
        when(warehouses.findIdByCode(WarehouseCodes.MAIN)).thenReturn(WAREHOUSE);
        when(salesOrderLineFacts.findUpfrontPaymentGate(SO))
            .thenReturn(Optional.of(new UpfrontPaymentGate("on_shipment", false)));

        service.post(cmd(WarehouseCodes.MAIN, List.of(
            new ShipmentLineRequest(null, PRODUCT_1, "SKU", "P",
                new BigDecimal("1"), new BigDecimal("10.00"))
        )));

        verify(shipments).save(any());
    }

    @Test void mixed_lines_first_invalid_rejects_entire_command() {
        when(warehouses.findIdByCode(WarehouseCodes.MAIN)).thenReturn(WAREHOUSE);
        UUID soLineGood = UUID.randomUUID();
        UUID soLineBad = UUID.randomUUID();
        when(salesOrderLineFacts.findProductIdForLine(soLineGood)).thenReturn(Optional.of(PRODUCT_1));
        when(salesOrderLineFacts.findProductIdForLine(soLineBad)).thenReturn(Optional.of(PRODUCT_2));

        assertThatThrownBy(() -> service.post(cmd(WarehouseCodes.MAIN, List.of(
            new ShipmentLineRequest(soLineGood, PRODUCT_1, "SKU-1", "P1",
                new BigDecimal("3"), new BigDecimal("10.00")),
            new ShipmentLineRequest(soLineBad, PRODUCT_1, "SKU-1", "P1",  // claims PRODUCT_1, actually PRODUCT_2
                new BigDecimal("3"), new BigDecimal("10.00"))
        ))))
            .isInstanceOf(ShipmentLineProductMismatchException.class);

        verify(shipments, never()).save(any());
        verify(stockBalances, never()).decrementOnHandAndReleaseReserved(any(), any(), any());
    }
}
