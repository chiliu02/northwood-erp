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
import com.northwood.shared.application.outbox.OutboxPort;
import com.northwood.shared.application.outbox.OutboxRow;
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
import tools.jackson.databind.ObjectMapper;

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
    @Mock OutboxPort outbox;

    private final ObjectMapper json = new ObjectMapper();
    private StockReservationService service;

    @BeforeEach
    void setUp() {
        service = new StockReservationService(
            reservations, stockBalances, balanceLookup, warehouses, outbox, json
        );
    }

    private StockReservationRequested salesPayload(String warehouseCode, BigDecimal requested) {
        return new StockReservationRequested(
            UUID.randomUUID(), SO, SO, warehouseCode,
            List.of(new StockReservationRequested.RequestedLine(
                10, PRODUCT_1, "SKU-1", "Product 1", requested
            )),
            Instant.now()
        );
    }

    private RawMaterialReservationRequested workOrderPayload(BigDecimal requested) {
        return new RawMaterialReservationRequested(
            UUID.randomUUID(), WO, WO, SO, UUID.randomUUID(), "MAIN",
            List.of(new RawMaterialReservationRequested.RequestedComponent(
                WO_MATERIAL, PRODUCT_1, "RM-001", "Raw Material 001", requested
            )),
            Instant.now()
        );
    }

    @Nested
    class ReserveForSalesOrder {

        @Test void full_reservation_marks_reserved_and_bumps_reserved_quantity() {
            when(warehouses.findIdByCode("MAIN")).thenReturn(WAREHOUSE);
            when(balanceLookup.findAvailableQuantity(WAREHOUSE, PRODUCT_1)).thenReturn(new BigDecimal("10"));
            when(stockBalances.tryReserveOnHand(WAREHOUSE, PRODUCT_1, new BigDecimal("10"))).thenReturn(true);

            service.reserveForSalesOrder(salesPayload("MAIN", new BigDecimal("10")));

            ArgumentCaptor<StockReservation> cap = ArgumentCaptor.forClass(StockReservation.class);
            verify(reservations).save(cap.capture());
            StockReservation saved = cap.getValue();
            assertThat(saved.status()).isEqualTo("reserved");
            assertThat(saved.lines()).hasSize(1);
            assertThat(saved.lines().get(0).reservedQuantity()).isEqualByComparingTo("10");
            assertThat(saved.lines().get(0).shortageQuantity()).isEqualByComparingTo("0");
            assertThat(saved.lines().get(0).status()).isEqualTo("reserved");
        }

        @Test void partial_reservation_when_available_less_than_requested() {
            when(warehouses.findIdByCode("MAIN")).thenReturn(WAREHOUSE);
            when(balanceLookup.findAvailableQuantity(WAREHOUSE, PRODUCT_1)).thenReturn(new BigDecimal("4"));
            when(stockBalances.tryReserveOnHand(WAREHOUSE, PRODUCT_1, new BigDecimal("4"))).thenReturn(true);

            service.reserveForSalesOrder(salesPayload("MAIN", new BigDecimal("10")));

            ArgumentCaptor<StockReservation> cap = ArgumentCaptor.forClass(StockReservation.class);
            verify(reservations).save(cap.capture());
            StockReservation saved = cap.getValue();
            assertThat(saved.status()).isEqualTo("partially_reserved");
            assertThat(saved.lines().get(0).reservedQuantity()).isEqualByComparingTo("4");
            assertThat(saved.lines().get(0).shortageQuantity()).isEqualByComparingTo("6");
            assertThat(saved.lines().get(0).status()).isEqualTo("partially_reserved");
        }

        @Test void failed_when_no_stock_skips_try_reserve_call() {
            when(warehouses.findIdByCode("MAIN")).thenReturn(WAREHOUSE);
            when(balanceLookup.findAvailableQuantity(WAREHOUSE, PRODUCT_1)).thenReturn(BigDecimal.ZERO);

            service.reserveForSalesOrder(salesPayload("MAIN", new BigDecimal("10")));

            verify(stockBalances, never()).tryReserveOnHand(any(), any(), any());
            ArgumentCaptor<StockReservation> cap = ArgumentCaptor.forClass(StockReservation.class);
            verify(reservations).save(cap.capture());
            StockReservation saved = cap.getValue();
            assertThat(saved.status()).isEqualTo("failed");
            assertThat(saved.lines().get(0).reservedQuantity()).isEqualByComparingTo("0");
            assertThat(saved.lines().get(0).shortageQuantity()).isEqualByComparingTo("10");
            assertThat(saved.lines().get(0).status()).isEqualTo("failed");
        }

        @Test void try_reserve_race_loss_falls_back_to_failed() {
            when(warehouses.findIdByCode("MAIN")).thenReturn(WAREHOUSE);
            when(balanceLookup.findAvailableQuantity(WAREHOUSE, PRODUCT_1)).thenReturn(new BigDecimal("10"));
            when(stockBalances.tryReserveOnHand(WAREHOUSE, PRODUCT_1, new BigDecimal("10"))).thenReturn(false);

            service.reserveForSalesOrder(salesPayload("MAIN", new BigDecimal("10")));

            ArgumentCaptor<StockReservation> cap = ArgumentCaptor.forClass(StockReservation.class);
            verify(reservations).save(cap.capture());
            StockReservation saved = cap.getValue();
            assertThat(saved.status()).isEqualTo("failed");
            assertThat(saved.lines().get(0).reservedQuantity()).isEqualByComparingTo("0");
            assertThat(saved.lines().get(0).shortageQuantity()).isEqualByComparingTo("10");
        }

        @Test void null_warehouse_code_defaults_to_MAIN() {
            when(warehouses.findIdByCode("MAIN")).thenReturn(WAREHOUSE);
            when(balanceLookup.findAvailableQuantity(WAREHOUSE, PRODUCT_1)).thenReturn(new BigDecimal("10"));
            when(stockBalances.tryReserveOnHand(WAREHOUSE, PRODUCT_1, new BigDecimal("10"))).thenReturn(true);

            service.reserveForSalesOrder(salesPayload(null, new BigDecimal("10")));

            verify(warehouses).findIdByCode("MAIN");
        }
    }

    @Nested
    class ReserveForWorkOrder {

        @Test void no_prior_reservation_skips_cancel_and_saves() {
            when(warehouses.findIdByCode("MAIN")).thenReturn(WAREHOUSE);
            when(reservations.findAnyHeaderIdForWorkOrder(WO)).thenReturn(Optional.empty());
            when(balanceLookup.findAvailableQuantity(WAREHOUSE, PRODUCT_1)).thenReturn(new BigDecimal("5"));
            when(stockBalances.tryReserveOnHand(WAREHOUSE, PRODUCT_1, new BigDecimal("5"))).thenReturn(true);

            service.reserveForWorkOrder(workOrderPayload(new BigDecimal("5")));

            verify(reservations, never()).deleteHeaderAndLines(any());
            verify(reservations).save(any());
        }

        @Test void retry_path_cancels_prior_releases_reserved_then_inserts_fresh() {
            when(warehouses.findIdByCode("MAIN")).thenReturn(WAREHOUSE);
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

        @Test void live_reservation_unwinds_and_emits_ack_with_count_one() throws Exception {
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

            ArgumentCaptor<OutboxRow> cap = ArgumentCaptor.forClass(OutboxRow.class);
            verify(outbox).appendPending(cap.capture());
            OutboxRow row = cap.getValue();
            assertThat(row.getEventType()).isEqualTo(com.northwood.inventory.domain.events.SalesOrderCancellationApplied.EVENT_TYPE);
            assertThat(row.getAggregateId()).isEqualTo(SO);
            assertThat(row.getAggregateType()).isEqualTo("StockReservation");
            assertThat(json.readTree(row.getPayload()).get("reservationsReleased").asInt()).isEqualTo(1);
        }

        @Test void no_live_reservation_still_emits_ack_with_count_zero() throws Exception {
            when(reservations.findActiveHeaderIdForSalesOrder(SO)).thenReturn(Optional.empty());

            service.releaseForSalesOrder(SO);

            verify(reservations, never()).markReleased(any());
            verifyNoInteractions(stockBalances);

            ArgumentCaptor<OutboxRow> cap = ArgumentCaptor.forClass(OutboxRow.class);
            verify(outbox).appendPending(cap.capture());
            assertThat(json.readTree(cap.getValue().getPayload()).get("reservationsReleased").asInt()).isEqualTo(0);
        }

        @Test void unwind_throws_when_warehouse_id_missing_for_header() {
            when(reservations.findActiveHeaderIdForSalesOrder(SO)).thenReturn(Optional.of(PRIOR_RES_ID));
            when(reservations.findWarehouseIdForHeader(PRIOR_RES_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.releaseForSalesOrder(SO))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("disappeared mid-release");
            verify(outbox, never()).appendPending(any());
        }
    }

    @Nested
    class ReleaseForWorkOrder {

        @Test void live_reservation_unwinds_no_event_emitted() {
            when(reservations.findActiveHeaderIdForWorkOrder(WO)).thenReturn(Optional.of(PRIOR_RES_ID));
            when(reservations.findWarehouseIdForHeader(PRIOR_RES_ID)).thenReturn(Optional.of(WAREHOUSE));
            when(reservations.findReservedLines(PRIOR_RES_ID)).thenReturn(List.of(
                new ReservedLineSnapshot(PRODUCT_1, new BigDecimal("3"))
            ));

            service.releaseForWorkOrder(WO);

            verify(stockBalances).releaseReserved(WAREHOUSE, PRODUCT_1, new BigDecimal("3"));
            verify(reservations).markReleased(PRIOR_RES_ID);
            verifyNoInteractions(outbox);
        }

        @Test void no_reservation_is_noop() {
            when(reservations.findActiveHeaderIdForWorkOrder(WO)).thenReturn(Optional.empty());

            service.releaseForWorkOrder(WO);

            verify(reservations, never()).markReleased(any());
            verifyNoInteractions(stockBalances);
            verifyNoInteractions(outbox);
        }
    }
}
