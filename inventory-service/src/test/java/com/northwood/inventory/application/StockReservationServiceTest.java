package com.northwood.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.northwood.manufacturing.domain.events.RawMaterialReservationRequested;
import com.northwood.sales.domain.events.StockReservationRequested;
import com.northwood.inventory.domain.StockReservation;
import com.northwood.inventory.domain.StockReservationRepository;
import com.northwood.inventory.domain.StockReservationRepository.ReservedLineSnapshot;
import com.northwood.inventory.domain.WarehouseCodes;
import com.northwood.inventory.domain.events.InventorySalesOrderCancellationApplied;
import com.northwood.shared.application.outbox.OutboxAppender;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StockReservationServiceTest {

    private static final UUID WAREHOUSE = UUID.randomUUID();
    private static final UUID PRODUCT_1 = UUID.randomUUID();
    private static final UUID PRODUCT_2 = UUID.randomUUID();
    private static final UUID SO = UUID.randomUUID();
    private static final UUID WO = UUID.randomUUID();
    private static final UUID WO_MATERIAL = UUID.randomUUID();
    private static final UUID PRIOR_RES_ID = UUID.randomUUID();

    @Mock StockReservationRepository reservations;
    @Mock StockBalanceWriter stockBalances;
    @Mock StockBalanceLookup balanceLookup;
    @Mock WarehouseLookup warehouses;
    @Mock ReplenishmentDetectionService replenishmentDetection;
    @Mock OutboxAppender outbox;

    private static final UUID SO_LINE_1 = UUID.randomUUID();

    private StockReservationService service;

    @BeforeEach
    void setUp() {
        service = new StockReservationService(
            reservations, stockBalances, balanceLookup, warehouses, replenishmentDetection, outbox
        );
    }

    private StockReservationRequested salesPayload(String warehouseCode, BigDecimal requested) {
        return salesPayload(warehouseCode, requested, false);
    }

    private StockReservationRequested salesPayload(String warehouseCode, BigDecimal requested, boolean pegged) {
        return new StockReservationRequested(
            UUID.randomUUID(), SO, SO, warehouseCode,
            List.of(new StockReservationRequested.RequestedLine(
                SO_LINE_1, 10, PRODUCT_1, "SKU-1", "Product 1", requested, pegged
            )),
            Instant.now()
        );
    }

    private RawMaterialReservationRequested workOrderPayload(BigDecimal requested) {
        return new RawMaterialReservationRequested(
            UUID.randomUUID(), WO, WO, SO, UUID.randomUUID(), WarehouseCodes.MAIN,
            List.of(new RawMaterialReservationRequested.RequestedComponent(
                WO_MATERIAL, PRODUCT_1, "RM-001", "Raw Material 001", requested
            )),
            Instant.now()
        );
    }

    @Nested
    class ReserveForSalesOrder {

        @Test void full_reservation_marks_reserved_and_bumps_reserved_quantity() {
            when(warehouses.findIdByCode(WarehouseCodes.MAIN)).thenReturn(WAREHOUSE);
            when(balanceLookup.findAvailableQuantity(WAREHOUSE, PRODUCT_1)).thenReturn(new BigDecimal("10"));
            when(stockBalances.tryReserveOnHand(WAREHOUSE, PRODUCT_1, new BigDecimal("10"))).thenReturn(true);

            service.reserveForSalesOrder(salesPayload(WarehouseCodes.MAIN, new BigDecimal("10")));

            ArgumentCaptor<StockReservation> cap = ArgumentCaptor.forClass(StockReservation.class);
            verify(reservations).save(cap.capture());
            StockReservation saved = cap.getValue();
            assertThat(saved.status()).isEqualTo(StockReservation.Status.RESERVED);
            assertThat(saved.lines()).hasSize(1);
            assertThat(saved.lines().get(0).reservedQuantity()).isEqualByComparingTo("10");
            assertThat(saved.lines().get(0).shortageQuantity()).isEqualByComparingTo("0");
            assertThat(saved.lines().get(0).status()).isEqualTo(StockReservation.Status.RESERVED);
        }

        @Test void partial_reservation_when_available_less_than_requested() {
            when(warehouses.findIdByCode(WarehouseCodes.MAIN)).thenReturn(WAREHOUSE);
            when(balanceLookup.findAvailableQuantity(WAREHOUSE, PRODUCT_1)).thenReturn(new BigDecimal("4"));
            when(stockBalances.tryReserveOnHand(WAREHOUSE, PRODUCT_1, new BigDecimal("4"))).thenReturn(true);

            service.reserveForSalesOrder(salesPayload(WarehouseCodes.MAIN, new BigDecimal("10")));

            ArgumentCaptor<StockReservation> cap = ArgumentCaptor.forClass(StockReservation.class);
            verify(reservations).save(cap.capture());
            StockReservation saved = cap.getValue();
            assertThat(saved.status()).isEqualTo(StockReservation.Status.PARTIALLY_RESERVED);
            assertThat(saved.lines().get(0).reservedQuantity()).isEqualByComparingTo("4");
            assertThat(saved.lines().get(0).shortageQuantity()).isEqualByComparingTo("6");
            assertThat(saved.lines().get(0).status()).isEqualTo(StockReservation.Status.PARTIALLY_RESERVED);

            // Inventory raises the replenishment in the same transaction for the
            // short line, with the sales-order back-reference.
            verify(replenishmentDetection).raiseForSalesOrderShortage(
                eq(PRODUCT_1), eq(WAREHOUSE), eq(new BigDecimal("6")), eq(SO), eq(SO_LINE_1));
        }

        @Test void failed_when_no_stock_skips_try_reserve_call() {
            when(warehouses.findIdByCode(WarehouseCodes.MAIN)).thenReturn(WAREHOUSE);
            when(balanceLookup.findAvailableQuantity(WAREHOUSE, PRODUCT_1)).thenReturn(BigDecimal.ZERO);

            service.reserveForSalesOrder(salesPayload(WarehouseCodes.MAIN, new BigDecimal("10")));

            verify(stockBalances, never()).tryReserveOnHand(any(), any(), any());
            ArgumentCaptor<StockReservation> cap = ArgumentCaptor.forClass(StockReservation.class);
            verify(reservations).save(cap.capture());
            StockReservation saved = cap.getValue();
            assertThat(saved.status()).isEqualTo(StockReservation.Status.FAILED);
            assertThat(saved.lines().get(0).reservedQuantity()).isEqualByComparingTo("0");
            assertThat(saved.lines().get(0).shortageQuantity()).isEqualByComparingTo("10");
            assertThat(saved.lines().get(0).status()).isEqualTo(StockReservation.Status.FAILED);
        }

        @Test void pegged_line_skips_free_stock_and_raises_order_pegged_replenishment() {
            // to_order (§2.43): no reserve attempt at all; raise dedicated supply
            // for the FULL line qty via raiseForOrderPegged.
            when(warehouses.findIdByCode(WarehouseCodes.MAIN)).thenReturn(WAREHOUSE);

            service.reserveForSalesOrder(salesPayload(WarehouseCodes.MAIN, new BigDecimal("10"), true));

            // Never touches the shared pool.
            verifyNoInteractions(balanceLookup);
            verify(stockBalances, never()).tryReserveOnHand(any(), any(), any());

            // Raises an order-pegged replenishment for the FULL qty (not a shortage).
            verify(replenishmentDetection).raiseForOrderPegged(
                eq(PRODUCT_1), eq(WAREHOUSE), eq(new BigDecimal("10")), eq(SO), eq(SO_LINE_1));
            verify(replenishmentDetection, never()).raiseForSalesOrderShortage(
                any(), any(), any(), any(), any());

            // The reservation line is recorded as a full shortage (zero reserved)
            // so sales parks the saga at stock_reservation_incomplete.
            ArgumentCaptor<StockReservation> cap = ArgumentCaptor.forClass(StockReservation.class);
            verify(reservations).save(cap.capture());
            StockReservation saved = cap.getValue();
            assertThat(saved.lines().get(0).reservedQuantity()).isEqualByComparingTo("0");
            assertThat(saved.lines().get(0).shortageQuantity()).isEqualByComparingTo("10");
            assertThat(saved.lines().get(0).status()).isEqualTo(StockReservation.Status.FAILED);
        }

        @Test void try_reserve_race_loss_exhausts_retries_then_falls_back_to_failed() {
            // tryReserveOnHand always loses the race; bounded retry exhausts
            // after RESERVE_MAX_ATTEMPTS and falls back to FAILED.
            when(warehouses.findIdByCode(WarehouseCodes.MAIN)).thenReturn(WAREHOUSE);
            when(balanceLookup.findAvailableQuantity(WAREHOUSE, PRODUCT_1)).thenReturn(new BigDecimal("10"));
            when(stockBalances.tryReserveOnHand(WAREHOUSE, PRODUCT_1, new BigDecimal("10"))).thenReturn(false);

            service.reserveForSalesOrder(salesPayload(WarehouseCodes.MAIN, new BigDecimal("10")));

            ArgumentCaptor<StockReservation> cap = ArgumentCaptor.forClass(StockReservation.class);
            verify(reservations).save(cap.capture());
            StockReservation saved = cap.getValue();
            assertThat(saved.status()).isEqualTo(StockReservation.Status.FAILED);
            assertThat(saved.lines().get(0).reservedQuantity()).isEqualByComparingTo("0");
            assertThat(saved.lines().get(0).shortageQuantity()).isEqualByComparingTo("10");
            verify(stockBalances, times(StockReservationService.RESERVE_MAX_ATTEMPTS))
                .tryReserveOnHand(WAREHOUSE, PRODUCT_1, new BigDecimal("10"));
        }

        @Test void try_reserve_recovers_on_retry_after_one_race_loss() {
            // First attempt loses the race; second attempt succeeds at the
            // original quantity (the winner released some stock back, or a
            // tight transient race that resolved in our favour on retry).
            when(warehouses.findIdByCode(WarehouseCodes.MAIN)).thenReturn(WAREHOUSE);
            when(balanceLookup.findAvailableQuantity(WAREHOUSE, PRODUCT_1)).thenReturn(new BigDecimal("10"));
            when(stockBalances.tryReserveOnHand(WAREHOUSE, PRODUCT_1, new BigDecimal("10")))
                .thenReturn(false, true);

            service.reserveForSalesOrder(salesPayload(WarehouseCodes.MAIN, new BigDecimal("10")));

            ArgumentCaptor<StockReservation> cap = ArgumentCaptor.forClass(StockReservation.class);
            verify(reservations).save(cap.capture());
            StockReservation saved = cap.getValue();
            assertThat(saved.status()).isEqualTo(StockReservation.Status.RESERVED);
            assertThat(saved.lines().get(0).reservedQuantity()).isEqualByComparingTo("10");
            assertThat(saved.lines().get(0).shortageQuantity()).isEqualByComparingTo("0");
            verify(stockBalances, times(2))
                .tryReserveOnHand(WAREHOUSE, PRODUCT_1, new BigDecimal("10"));
        }

        @Test void try_reserve_retry_clamps_to_shrunk_availability_partial_reserved() {
            // First attempt loses the race; on re-read the winner has consumed
            // 3 units so only 7 are available — retry succeeds at the clamped
            // 7, lands PARTIALLY_RESERVED with shortage 3.
            when(warehouses.findIdByCode(WarehouseCodes.MAIN)).thenReturn(WAREHOUSE);
            when(balanceLookup.findAvailableQuantity(WAREHOUSE, PRODUCT_1))
                .thenReturn(new BigDecimal("10"), new BigDecimal("7"));
            when(stockBalances.tryReserveOnHand(WAREHOUSE, PRODUCT_1, new BigDecimal("10"))).thenReturn(false);
            when(stockBalances.tryReserveOnHand(WAREHOUSE, PRODUCT_1, new BigDecimal("7"))).thenReturn(true);

            service.reserveForSalesOrder(salesPayload(WarehouseCodes.MAIN, new BigDecimal("10")));

            ArgumentCaptor<StockReservation> cap = ArgumentCaptor.forClass(StockReservation.class);
            verify(reservations).save(cap.capture());
            StockReservation saved = cap.getValue();
            assertThat(saved.status()).isEqualTo(StockReservation.Status.PARTIALLY_RESERVED);
            assertThat(saved.lines().get(0).reservedQuantity()).isEqualByComparingTo("7");
            assertThat(saved.lines().get(0).shortageQuantity()).isEqualByComparingTo("3");
        }

        @Test void try_reserve_retry_aborts_early_when_availability_drops_to_zero() {
            // First attempt loses the race; on re-read the winner has consumed
            // everything — no point retrying further. Loop exits with
            // reserved=0, status FAILED. Only ONE tryReserveOnHand call (the
            // first), because the re-read short-circuits before attempt 2.
            when(warehouses.findIdByCode(WarehouseCodes.MAIN)).thenReturn(WAREHOUSE);
            when(balanceLookup.findAvailableQuantity(WAREHOUSE, PRODUCT_1))
                .thenReturn(new BigDecimal("10"), BigDecimal.ZERO);
            when(stockBalances.tryReserveOnHand(WAREHOUSE, PRODUCT_1, new BigDecimal("10"))).thenReturn(false);

            service.reserveForSalesOrder(salesPayload(WarehouseCodes.MAIN, new BigDecimal("10")));

            ArgumentCaptor<StockReservation> cap = ArgumentCaptor.forClass(StockReservation.class);
            verify(reservations).save(cap.capture());
            StockReservation saved = cap.getValue();
            assertThat(saved.status()).isEqualTo(StockReservation.Status.FAILED);
            assertThat(saved.lines().get(0).reservedQuantity()).isEqualByComparingTo("0");
            verify(stockBalances, times(1))
                .tryReserveOnHand(WAREHOUSE, PRODUCT_1, new BigDecimal("10"));
        }

        @Test void null_warehouse_code_defaults_to_MAIN() {
            when(warehouses.findIdByCode(WarehouseCodes.MAIN)).thenReturn(WAREHOUSE);
            when(balanceLookup.findAvailableQuantity(WAREHOUSE, PRODUCT_1)).thenReturn(new BigDecimal("10"));
            when(stockBalances.tryReserveOnHand(WAREHOUSE, PRODUCT_1, new BigDecimal("10"))).thenReturn(true);

            service.reserveForSalesOrder(salesPayload(null, new BigDecimal("10")));

            verify(warehouses).findIdByCode(WarehouseCodes.MAIN);
        }
    }

    @Nested
    class ReserveForWorkOrder {

        @Test void no_prior_reservation_skips_cancel_and_saves() {
            when(warehouses.findIdByCode(WarehouseCodes.MAIN)).thenReturn(WAREHOUSE);
            when(reservations.findAnyHeaderIdForWorkOrder(WO)).thenReturn(Optional.empty());
            when(balanceLookup.findAvailableQuantity(WAREHOUSE, PRODUCT_1)).thenReturn(new BigDecimal("5"));
            when(stockBalances.tryReserveOnHand(WAREHOUSE, PRODUCT_1, new BigDecimal("5"))).thenReturn(true);

            service.reserveForWorkOrder(workOrderPayload(new BigDecimal("5")));

            verify(reservations, never()).deleteHeaderAndLines(any());
            verify(reservations).save(any());
        }

        @Test void retry_path_cancels_prior_releases_reserved_then_inserts_fresh() {
            when(warehouses.findIdByCode(WarehouseCodes.MAIN)).thenReturn(WAREHOUSE);
            when(reservations.findAnyHeaderIdForWorkOrder(WO)).thenReturn(Optional.of(PRIOR_RES_ID));
            when(reservations.findReservedLines(PRIOR_RES_ID)).thenReturn(List.of(
                new ReservedLineSnapshot(PRODUCT_1, new BigDecimal("3"))
            ));
            when(balanceLookup.findAvailableQuantity(WAREHOUSE, PRODUCT_1)).thenReturn(new BigDecimal("5"));
            when(stockBalances.tryReserveOnHand(WAREHOUSE, PRODUCT_1, new BigDecimal("5"))).thenReturn(true);

            service.reserveForWorkOrder(workOrderPayload(new BigDecimal("5")));

            verify(stockBalances).releaseReserved(WAREHOUSE, PRODUCT_1, new BigDecimal("3"));
            verify(reservations).deleteHeaderAndLines(PRIOR_RES_ID);
            ArgumentCaptor<StockReservation> cap = ArgumentCaptor.forClass(StockReservation.class);
            verify(reservations).save(cap.capture());
            assertThat(cap.getValue().workOrderId()).isEqualTo(WO);
            assertThat(cap.getValue().salesOrderId()).isNull();
        }
    }

    @Nested
    class ReleaseForSalesOrder {

        @Test void live_reservation_unwinds_and_emits_ack_with_count_one() {
            when(reservations.findActiveHeaderIdForSalesOrder(SO)).thenReturn(Optional.of(PRIOR_RES_ID));
            when(reservations.findWarehouseIdForHeader(PRIOR_RES_ID)).thenReturn(Optional.of(WAREHOUSE));
            when(reservations.findReservedLines(PRIOR_RES_ID)).thenReturn(List.of(
                new ReservedLineSnapshot(PRODUCT_1, new BigDecimal("4")),
                new ReservedLineSnapshot(PRODUCT_2, new BigDecimal("2"))
            ));

            service.releaseForSalesOrder(SO);

            verify(stockBalances).releaseReserved(WAREHOUSE, PRODUCT_1, new BigDecimal("4"));
            verify(stockBalances).releaseReserved(WAREHOUSE, PRODUCT_2, new BigDecimal("2"));
            verify(reservations).markReleased(PRIOR_RES_ID);

            ArgumentCaptor<InventorySalesOrderCancellationApplied> cap =
                ArgumentCaptor.forClass(InventorySalesOrderCancellationApplied.class);
            verify(outbox).append(cap.capture(), eq(StockReservation.AGGREGATE_TYPE));
            InventorySalesOrderCancellationApplied event = cap.getValue();
            assertThat(event.eventType()).isEqualTo(InventorySalesOrderCancellationApplied.EVENT_TYPE);
            assertThat(event.aggregateId()).isEqualTo(SO);
            assertThat(event.reservationsReleased()).isEqualTo(1);
        }

        @Test void no_live_reservation_still_emits_ack_with_count_zero() {
            when(reservations.findActiveHeaderIdForSalesOrder(SO)).thenReturn(Optional.empty());

            service.releaseForSalesOrder(SO);

            verify(reservations, never()).markReleased(any());
            verifyNoInteractions(stockBalances);

            ArgumentCaptor<InventorySalesOrderCancellationApplied> cap =
                ArgumentCaptor.forClass(InventorySalesOrderCancellationApplied.class);
            verify(outbox).append(cap.capture(), eq(StockReservation.AGGREGATE_TYPE));
            assertThat(cap.getValue().reservationsReleased()).isEqualTo(0);
        }

        @Test void unwind_throws_when_warehouse_id_missing_for_header() {
            when(reservations.findActiveHeaderIdForSalesOrder(SO)).thenReturn(Optional.of(PRIOR_RES_ID));
            when(reservations.findWarehouseIdForHeader(PRIOR_RES_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.releaseForSalesOrder(SO))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("disappeared mid-release");
            verify(outbox, never()).append(any(), any());
        }
    }

}
