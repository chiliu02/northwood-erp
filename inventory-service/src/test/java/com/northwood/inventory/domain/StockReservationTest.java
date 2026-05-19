package com.northwood.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northwood.inventory.domain.events.RawMaterialsReserved;
import com.northwood.inventory.domain.events.StockReserved;
import com.northwood.shared.domain.DomainEvent;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class StockReservationTest {

    private static final UUID SO = UUID.randomUUID();
    private static final UUID WO = UUID.randomUUID();
    private static final UUID WH = UUID.randomUUID();

    private static StockReservationLine line(BigDecimal requested, BigDecimal reserved, BigDecimal shortage) {
        StockReservation.Status status = shortage.signum() > 0
            ? (reserved.signum() > 0 ? StockReservation.Status.PARTIALLY_RESERVED : StockReservation.Status.FAILED)
            : StockReservation.Status.RESERVED;
        return new StockReservationLine(
            UUID.randomUUID(), UUID.randomUUID(),
            "FG-X", "Test Product",
            requested, reserved, shortage, status
        );
    }

    @Nested
    class ForSalesOrder {
        @Test void rejects_empty_lines() {
            assertThatThrownBy(() -> StockReservation.forSalesOrder(SO, WH, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test void rejects_null_sales_order_id() {
            assertThatThrownBy(() -> StockReservation.forSalesOrder(null, WH,
                List.of(line(BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ZERO))))
                .isInstanceOf(NullPointerException.class);
        }

        @Test void rejects_null_warehouse_id() {
            assertThatThrownBy(() -> StockReservation.forSalesOrder(SO, null,
                List.of(line(BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ZERO))))
                .isInstanceOf(NullPointerException.class);
        }

        @Test void full_reservation_status_is_reserved() {
            StockReservation res = StockReservation.forSalesOrder(SO, WH, List.of(
                line(BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ZERO)
            ));
            assertThat(res.status()).isEqualTo(StockReservation.Status.RESERVED);
        }

        @Test void any_shortage_yields_partially_reserved() {
            StockReservation res = StockReservation.forSalesOrder(SO, WH, List.of(
                line(BigDecimal.TEN, new BigDecimal("3"), new BigDecimal("7"))
            ));
            assertThat(res.status()).isEqualTo(StockReservation.Status.PARTIALLY_RESERVED);
        }

        @Test void zero_reserved_across_all_lines_yields_failed() {
            StockReservation res = StockReservation.forSalesOrder(SO, WH, List.of(
                line(BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN)
            ));
            assertThat(res.status()).isEqualTo(StockReservation.Status.FAILED);
        }

        @Test void emits_StockReserved_with_per_line_breakdown() {
            StockReservation res = StockReservation.forSalesOrder(SO, WH, List.of(
                line(BigDecimal.TEN, new BigDecimal("3"), new BigDecimal("7"))
            ));
            List<DomainEvent> events = res.pullPendingEvents();
            assertThat(events).hasSize(1).first().isInstanceOf(StockReserved.class);
            StockReserved e = (StockReserved) events.get(0);
            assertThat(e.salesOrderId()).isEqualTo(SO);
            assertThat(e.lines()).hasSize(1);
            assertThat(e.status()).isEqualTo("partially_reserved");
        }
    }

    @Nested
    class ForWorkOrder {
        @Test void rejects_null_work_order_id() {
            assertThatThrownBy(() -> StockReservation.forWorkOrder(null, WH,
                List.of(line(BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ZERO))))
                .isInstanceOf(NullPointerException.class);
        }

        @Test void rejects_null_warehouse_id() {
            assertThatThrownBy(() -> StockReservation.forWorkOrder(WO, null,
                List.of(line(BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ZERO))))
                .isInstanceOf(NullPointerException.class);
        }

        @Test void emits_RawMaterialsReserved_keyed_on_work_order() {
            StockReservation res = StockReservation.forWorkOrder(WO, WH, List.of(
                line(BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ZERO)
            ));
            List<DomainEvent> events = res.pullPendingEvents();
            assertThat(events).hasSize(1).first().isInstanceOf(RawMaterialsReserved.class);
            RawMaterialsReserved e = (RawMaterialsReserved) events.get(0);
            assertThat(e.workOrderId()).isEqualTo(WO);
        }

        @Test void multiple_components_reservation_status_logic() {
            // 1st line full, 2nd line short → partially_reserved
            StockReservation res = StockReservation.forWorkOrder(WO, WH, List.of(
                line(new BigDecimal("4"), new BigDecimal("4"), BigDecimal.ZERO),
                line(new BigDecimal("2"), new BigDecimal("1"), new BigDecimal("1"))
            ));
            assertThat(res.status()).isEqualTo(StockReservation.Status.PARTIALLY_RESERVED);
        }

        @Test void all_components_short_yields_failed() {
            StockReservation res = StockReservation.forWorkOrder(WO, WH, List.of(
                line(new BigDecimal("4"), BigDecimal.ZERO, new BigDecimal("4")),
                line(new BigDecimal("2"), BigDecimal.ZERO, new BigDecimal("2"))
            ));
            assertThat(res.status()).isEqualTo(StockReservation.Status.FAILED);
        }
    }

    @Nested
    class LineInvariants {
        @Test void rejects_zero_requested_quantity() {
            assertThatThrownBy(() -> new StockReservationLine(
                UUID.randomUUID(), UUID.randomUUID(), "FG-X", "X",
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, StockReservation.Status.RESERVED
            )).isInstanceOf(IllegalArgumentException.class);
        }

        @Test void allows_zero_reserved_with_full_shortage() {
            new StockReservationLine(
                UUID.randomUUID(), UUID.randomUUID(), "FG-X", "X",
                BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, StockReservation.Status.FAILED
            );
        }
    }
}
