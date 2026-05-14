package com.northwood.inventory.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.northwood.inventory.application.GoodsReceiptService.GoodsReceiptLineProductMismatchException;
import com.northwood.inventory.application.dto.GoodsReceiptLineRequest;
import com.northwood.inventory.application.dto.PostGoodsReceiptCommand;
import com.northwood.inventory.application.inbox.PurchaseOrderLineFactsProjection;
import com.northwood.inventory.domain.GoodsReceiptRepository;
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
class GoodsReceiptServiceTest {

    @Mock GoodsReceiptRepository receipts;
    @Mock StockBalanceWriter stockBalances;
    @Mock StockMovementWriter movements;
    @Mock WarehouseLookup warehouses;
    @Mock PurchaseOrderLineFactsProjection purchaseOrderLineFacts;

    private GoodsReceiptService service;

    private static final UUID WAREHOUSE = UUID.randomUUID();
    private static final UUID PO = UUID.randomUUID();
    private static final UUID SUPPLIER = UUID.randomUUID();
    private static final UUID PRODUCT_1 = UUID.randomUUID();
    private static final UUID PRODUCT_2 = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new GoodsReceiptService(receipts, stockBalances, movements, warehouses, purchaseOrderLineFacts);
    }

    private PostGoodsReceiptCommand cmd(String warehouseCode, List<GoodsReceiptLineRequest> lines) {
        return new PostGoodsReceiptCommand("GR-001", PO, SUPPLIER, "Acme Supplies", warehouseCode, lines);
    }

    @Test void single_line_bumps_stock_balance_and_records_movement() {
        when(warehouses.findIdByCode("MAIN")).thenReturn(WAREHOUSE);

        // Unlinked line (null purchaseOrderLineId) skips validation — focus is on stock side effects.
        service.post(cmd("MAIN", List.of(
            new GoodsReceiptLineRequest(null, PRODUCT_1, "RM-1", "Raw 1",
                new BigDecimal("100"), new BigDecimal("2.50"))
        )));

        verify(receipts).save(any());
        verify(stockBalances).bump(WAREHOUSE, PRODUCT_1, new BigDecimal("100"));
        verify(movements).record(
            eq(WAREHOUSE), eq(PRODUCT_1), eq("RM-1"), eq("Raw 1"),
            eq("purchase_receipt"), eq("in"),
            eq(new BigDecimal("100")), eq(new BigDecimal("2.50")),
            eq("goods_receipt"), any(), any()
        );
    }

    @Test void multiple_lines_each_bumped_independently() {
        when(warehouses.findIdByCode("MAIN")).thenReturn(WAREHOUSE);

        service.post(cmd("MAIN", List.of(
            new GoodsReceiptLineRequest(null, PRODUCT_1, "RM-1", "Raw 1",
                new BigDecimal("100"), new BigDecimal("2.50")),
            new GoodsReceiptLineRequest(null, PRODUCT_2, "RM-2", "Raw 2",
                new BigDecimal("50"), new BigDecimal("4.00"))
        )));

        verify(stockBalances).bump(WAREHOUSE, PRODUCT_1, new BigDecimal("100"));
        verify(stockBalances).bump(WAREHOUSE, PRODUCT_2, new BigDecimal("50"));
        verify(movements, times(2)).record(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test void null_warehouse_code_defaults_to_MAIN() {
        when(warehouses.findIdByCode("MAIN")).thenReturn(WAREHOUSE);

        service.post(cmd(null, List.of(
            new GoodsReceiptLineRequest(null, PRODUCT_1, "RM", "R", new BigDecimal("1"), null)
        )));

        verify(warehouses).findIdByCode("MAIN");
    }

    @Test void null_unit_cost_treated_as_zero() {
        when(warehouses.findIdByCode("MAIN")).thenReturn(WAREHOUSE);

        service.post(cmd("MAIN", List.of(
            new GoodsReceiptLineRequest(null, PRODUCT_1, "RM", "R", new BigDecimal("5"), null)
        )));

        verify(movements).record(
            any(), any(), any(), any(), any(), any(),
            eq(new BigDecimal("5")), eq(BigDecimal.ZERO),
            any(), any(), any()
        );
    }

    @Test void linked_line_with_matching_product_passes_validation() {
        when(warehouses.findIdByCode("MAIN")).thenReturn(WAREHOUSE);
        UUID poLineId = UUID.randomUUID();
        when(purchaseOrderLineFacts.findProductIdForLine(poLineId)).thenReturn(Optional.of(PRODUCT_1));

        service.post(cmd("MAIN", List.of(
            new GoodsReceiptLineRequest(poLineId, PRODUCT_1, "RM-1", "Raw 1",
                new BigDecimal("100"), new BigDecimal("2.50"))
        )));

        verify(receipts).save(any());
        verify(stockBalances).bump(WAREHOUSE, PRODUCT_1, new BigDecimal("100"));
    }

    @Test void linked_line_with_mismatched_product_rejects_with_400_exception() {
        when(warehouses.findIdByCode("MAIN")).thenReturn(WAREHOUSE);
        UUID poLineId = UUID.randomUUID();
        when(purchaseOrderLineFacts.findProductIdForLine(poLineId)).thenReturn(Optional.of(PRODUCT_1));

        assertThatThrownBy(() -> service.post(cmd("MAIN", List.of(
            new GoodsReceiptLineRequest(poLineId, PRODUCT_2, "RM-2", "Raw 2",
                new BigDecimal("100"), new BigDecimal("2.50"))
        ))))
            .isInstanceOf(GoodsReceiptLineProductMismatchException.class)
            .hasMessageContaining(poLineId.toString())
            .hasMessageContaining(PRODUCT_1.toString())
            .hasMessageContaining(PRODUCT_2.toString());

        verify(receipts, never()).save(any());
        verify(stockBalances, never()).bump(any(), any(), any());
    }

    @Test void linked_line_with_unknown_po_line_id_rejects_with_400_exception() {
        when(warehouses.findIdByCode("MAIN")).thenReturn(WAREHOUSE);
        UUID poLineId = UUID.randomUUID();
        when(purchaseOrderLineFacts.findProductIdForLine(poLineId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.post(cmd("MAIN", List.of(
            new GoodsReceiptLineRequest(poLineId, PRODUCT_1, "RM-1", "Raw 1",
                new BigDecimal("100"), new BigDecimal("2.50"))
        ))))
            .isInstanceOf(GoodsReceiptLineProductMismatchException.class)
            .hasMessageContaining("Unknown purchase_order_line_id")
            .hasMessageContaining(poLineId.toString());

        verify(receipts, never()).save(any());
        verify(stockBalances, never()).bump(any(), any(), any());
    }
}
