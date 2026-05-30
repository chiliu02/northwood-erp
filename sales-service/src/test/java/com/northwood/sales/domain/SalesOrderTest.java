package com.northwood.sales.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northwood.sales.domain.events.SalesOrderCancellationRequested;
import com.northwood.sales.domain.events.SalesOrderPlaced;
import com.northwood.shared.domain.Currencies;
import com.northwood.shared.domain.DomainEvent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class SalesOrderTest {

    private static final UUID CUSTOMER = UUID.fromString("00000000-0000-0000-0000-000000000010");

    private static SalesOrderLine line(BigDecimal qty, BigDecimal price) {
        return new SalesOrderLine(
            UUID.randomUUID(),
            10,
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            "FG-TABLE-001",
            "Wooden Dining Table",
            qty,
            price,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            SalesOrder.LineStatus.OPEN
        );
    }

    private static SalesOrder placeWithLines(List<SalesOrderLine> lines) {
        return SalesOrder.place(
            "SO-TEST-001",
            CUSTOMER,
            "CUST-001", "Test Customer",
            LocalDate.of(2026, 6, 1),
            Currencies.AUD,
            BigDecimal.ONE,
            PaymentTerms.ON_SHIPMENT, null,
            lines
        );
    }

    @Nested
    class Place {
        @Test void rejects_empty_lines() {
            assertThatThrownBy(() -> SalesOrder.place(
                "SO-X", CUSTOMER, "C", "Customer", null, Currencies.AUD, BigDecimal.ONE, PaymentTerms.ON_SHIPMENT, null, List.of()
            )).isInstanceOf(IllegalArgumentException.class);
        }

        @Test void rejects_null_lines() {
            assertThatThrownBy(() -> SalesOrder.place(
                "SO-X", CUSTOMER, "C", "Customer", null, Currencies.AUD, BigDecimal.ONE, PaymentTerms.ON_SHIPMENT, null, null
            )).isInstanceOf(IllegalArgumentException.class);
        }

        @Test void rejects_null_order_number() {
            assertThatThrownBy(() -> SalesOrder.place(
                null, CUSTOMER, "C", "Customer", null, Currencies.AUD, BigDecimal.ONE, PaymentTerms.ON_SHIPMENT, null,
                List.of(line(BigDecimal.ONE, BigDecimal.TEN))
            )).isInstanceOf(IllegalArgumentException.class);
        }

        @Test void rejects_null_customer_id() {
            assertThatThrownBy(() -> SalesOrder.place(
                "SO-X", null, "C", "Customer", null, Currencies.AUD, BigDecimal.ONE, PaymentTerms.ON_SHIPMENT, null,
                List.of(line(BigDecimal.ONE, BigDecimal.TEN))
            )).isInstanceOf(IllegalArgumentException.class);
        }

        @Test void rejects_null_customer_code() {
            assertThatThrownBy(() -> SalesOrder.place(
                "SO-X", CUSTOMER, null, "Customer", null, Currencies.AUD, BigDecimal.ONE, PaymentTerms.ON_SHIPMENT, null,
                List.of(line(BigDecimal.ONE, BigDecimal.TEN))
            )).isInstanceOf(IllegalArgumentException.class);
        }

        @Test void rejects_null_customer_name() {
            assertThatThrownBy(() -> SalesOrder.place(
                "SO-X", CUSTOMER, "C", null, null, Currencies.AUD, BigDecimal.ONE, PaymentTerms.ON_SHIPMENT, null,
                List.of(line(BigDecimal.ONE, BigDecimal.TEN))
            )).isInstanceOf(IllegalArgumentException.class);
        }

        @Test void rejects_null_currency_code() {
            assertThatThrownBy(() -> SalesOrder.place(
                "SO-X", CUSTOMER, "C", "Customer", null, null, BigDecimal.ONE, PaymentTerms.ON_SHIPMENT, null,
                List.of(line(BigDecimal.ONE, BigDecimal.TEN))
            )).isInstanceOf(IllegalArgumentException.class);
        }

        @Test void initial_status_is_submitted() {
            SalesOrder so = placeWithLines(List.of(line(BigDecimal.ONE, BigDecimal.TEN)));
            assertThat(so.status()).isEqualTo(SalesOrder.Status.SUBMITTED);
        }

        @Test void emits_SalesOrderPlaced_with_full_line_set() {
            SalesOrder so = placeWithLines(List.of(
                line(new BigDecimal("2"), new BigDecimal("100.00")),
                line(new BigDecimal("3"), new BigDecimal("50.00"))
            ));
            List<DomainEvent> events = so.pullPendingEvents();
            assertThat(events).hasSize(1).first().isInstanceOf(SalesOrderPlaced.class);
            SalesOrderPlaced e = (SalesOrderPlaced) events.get(0);
            assertThat(e.lines()).hasSize(2);
            assertThat(e.aggregateId()).isEqualTo(so.id().value());
        }

        @Test void recomputes_totals_on_place() {
            SalesOrder so = placeWithLines(List.of(
                line(new BigDecimal("2"), new BigDecimal("100.00")),
                line(new BigDecimal("3"), new BigDecimal("50.00"))
            ));
            // 2*100 + 3*50 = 350
            assertThat(so.subtotalAmount()).isEqualByComparingTo(new BigDecimal("350.00"));
            assertThat(so.totalAmount()).isEqualByComparingTo(new BigDecimal("350.00"));
        }

        @Test void totals_event_carries_total_amount() {
            SalesOrder so = placeWithLines(List.of(line(new BigDecimal("4"), new BigDecimal("25.00"))));
            SalesOrderPlaced e = (SalesOrderPlaced) so.pullPendingEvents().get(0);
            assertThat(e.totalAmount()).isEqualByComparingTo(new BigDecimal("100.00"));
        }
    }

    @Nested
    class LineInvariants {
        @Test void rejects_zero_quantity() {
            assertThatThrownBy(() -> line(BigDecimal.ZERO, BigDecimal.TEN))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test void rejects_negative_quantity() {
            assertThatThrownBy(() -> line(new BigDecimal("-1"), BigDecimal.TEN))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test void rejects_negative_unit_price() {
            assertThatThrownBy(() -> line(BigDecimal.ONE, new BigDecimal("-1")))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test void allows_zero_unit_price() {
            // Free-of-charge line — accepted (unit_price >= 0).
            SalesOrderLine l = line(BigDecimal.ONE, BigDecimal.ZERO);
            assertThat(l.lineSubtotal()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test void line_subtotal_qty_times_price() {
            SalesOrderLine l = line(new BigDecimal("3"), new BigDecimal("12.50"));
            assertThat(l.lineSubtotal()).isEqualByComparingTo(new BigDecimal("37.50"));
        }
    }

    @Nested
    class Cancel {

        private static SalesOrder reconstituteWithStatus(SalesOrder.Status status) {
            return SalesOrder.reconstitute(
                SalesOrderId.of(UUID.randomUUID()),
                "SO-CXL-001",
                CUSTOMER, "CUST-001", "Test Customer",
                LocalDate.now(), null,
                status,
                Currencies.AUD, BigDecimal.ONE, PaymentTerms.ON_SHIPMENT, null,
                new BigDecimal("100"), BigDecimal.ZERO, new BigDecimal("100"),
                null,
                1L,
                List.of(line(BigDecimal.ONE, new BigDecimal("100")))
            );
        }

        @Test void cancellable_when_submitted() {
            SalesOrder so = reconstituteWithStatus(SalesOrder.Status.SUBMITTED);
            so.cancel("customer changed mind");
            assertThat(so.status()).isEqualTo(SalesOrder.Status.CANCELLED);
            assertThat(so.cancelledAt()).isNotNull();
            assertThat(so.pullPendingEvents()).hasSize(1)
                .first().isInstanceOf(SalesOrderCancellationRequested.class);
        }

        @Test void cancellable_when_in_fulfilment() {
            SalesOrder so = reconstituteWithStatus(SalesOrder.Status.IN_FULFILMENT);
            so.cancel("supply chain disruption");
            assertThat(so.status()).isEqualTo(SalesOrder.Status.CANCELLED);
        }

        @Test void rejected_when_already_shipped() {
            SalesOrder so = reconstituteWithStatus(SalesOrder.Status.SHIPPED);
            assertThatThrownBy(() -> so.cancel("too late"))
                .isInstanceOf(SalesOrder.OrderNotCancellableException.class)
                .hasMessageContaining("'shipped'");
        }

        @Test void rejected_when_completed() {
            SalesOrder so = reconstituteWithStatus(SalesOrder.Status.COMPLETED);
            assertThatThrownBy(() -> so.cancel("too late"))
                .isInstanceOf(SalesOrder.OrderNotCancellableException.class);
        }

        @Test void rejected_when_already_cancelled() {
            SalesOrder so = reconstituteWithStatus(SalesOrder.Status.CANCELLED);
            assertThatThrownBy(() -> so.cancel("twice"))
                .isInstanceOf(SalesOrder.OrderNotCancellableException.class);
        }

        @Test void rejected_when_rejected() {
            SalesOrder so = reconstituteWithStatus(SalesOrder.Status.REJECTED);
            assertThatThrownBy(() -> so.cancel("nope"))
                .isInstanceOf(SalesOrder.OrderNotCancellableException.class);
        }

        @Test void cancel_event_carries_reason_and_order_number() {
            SalesOrder so = reconstituteWithStatus(SalesOrder.Status.SUBMITTED);
            so.cancel("test reason");
            DomainEvent event = so.pullPendingEvents().get(0);
            assertThat(event).isInstanceOf(SalesOrderCancellationRequested.class);
            SalesOrderCancellationRequested cancel = (SalesOrderCancellationRequested) event;
            assertThat(cancel.reason()).isEqualTo("test reason");
            assertThat(cancel.orderNumber()).isEqualTo("SO-CXL-001");
        }
    }

    @Nested
    class Reconstitute {
        @Test void hydrates_without_events() {
            UUID id = UUID.randomUUID();
            SalesOrder so = SalesOrder.reconstitute(
                SalesOrderId.of(id),
                "SO-X",
                CUSTOMER, "C", "Cust",
                LocalDate.now(), null,
                SalesOrder.Status.IN_FULFILMENT,
                Currencies.AUD, BigDecimal.ONE, PaymentTerms.ON_SHIPMENT, null,
                new BigDecimal("100"), BigDecimal.ZERO, new BigDecimal("100"),
                null,
                3L,
                List.of(line(BigDecimal.ONE, new BigDecimal("100")))
            );
            assertThat(so.pullPendingEvents()).isEmpty();
            assertThat(so.status()).isEqualTo(SalesOrder.Status.IN_FULFILMENT);
            assertThat(so.version()).isEqualTo(3L);
        }
    }

    @Nested
    class PullPendingEvents {
        @Test void drains_after_pulling() {
            SalesOrder so = placeWithLines(List.of(line(BigDecimal.ONE, BigDecimal.TEN)));
            assertThat(so.pullPendingEvents()).hasSize(1);
            assertThat(so.pullPendingEvents()).isEmpty();
        }
    }
}
