package com.northwood.sales.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northwood.sales.domain.events.SalesOrderCancellationRequested;
import com.northwood.sales.domain.events.SalesOrderLineAdded;
import com.northwood.sales.domain.events.SalesOrderLineQuantityChanged;
import com.northwood.sales.domain.events.SalesOrderLineRemoved;
import com.northwood.sales.domain.events.SalesOrderPlaced;
import com.northwood.sales.domain.events.SalesOrderShipped;
import com.northwood.shared.domain.Currencies;
import com.northwood.shared.domain.DomainEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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

        @Test void initial_status_is_open() {
            SalesOrder so = placeWithLines(List.of(line(BigDecimal.ONE, BigDecimal.TEN)));
            assertThat(so.status()).isEqualTo(SalesOrder.Status.OPEN);
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
            // Gates read the lines, so for a ship-state header the line
            // must actually be shipped — fake-header-only fixtures no longer drive
            // the cancel/amend guards.
            SalesOrderLine l = line(BigDecimal.ONE, new BigDecimal("100"));
            if (status == SalesOrder.Status.SHIPPED || status == SalesOrder.Status.PARTIALLY_SHIPPED) {
                l.recordShipment(BigDecimal.ONE);
            }
            return SalesOrder.reconstitute(
                SalesOrderId.of(UUID.randomUUID()),
                "SO-CXL-001",
                CUSTOMER, "CUST-001", "Test Customer",
                LocalDate.now(), null,
                status,
                Currencies.AUD, BigDecimal.ONE, PaymentTerms.ON_SHIPMENT, null,
                new BigDecimal("100"), BigDecimal.ZERO, new BigDecimal("100"),
                null,
                null,
                1L,
                List.of(l)
            );
        }

        @Test void requesting_cancellation_emits_event_but_does_not_yet_cancel() {
            // Phase 1: request emits the event but leaves the status unchanged —
            // the order is only cancelled once inventory confirms (phase 2).
            SalesOrder so = reconstituteWithStatus(SalesOrder.Status.OPEN);
            so.requestCancellation("customer changed mind");
            assertThat(so.status()).isEqualTo(SalesOrder.Status.OPEN);
            assertThat(so.cancelledAt()).isNull();
            assertThat(so.pullPendingEvents()).hasSize(1)
                .first().isInstanceOf(SalesOrderCancellationRequested.class);
        }

        @Test void requesting_cancellation_is_idempotent_emitting_one_event() {
            // No immediate status change during the two-phase window can tempt an
            // impatient user to click Cancel repeatedly; the aggregate stamps
            // cancellationRequestedAt once and emits exactly one event regardless.
            SalesOrder so = reconstituteWithStatus(SalesOrder.Status.OPEN);
            so.requestCancellation("first click");
            so.requestCancellation("second click");
            so.requestCancellation("third click");
            assertThat(so.cancellationRequestedAt()).isNotNull();
            assertThat(so.pullPendingEvents()).hasSize(1)
                .first().isInstanceOf(SalesOrderCancellationRequested.class);
        }

        @Test void cancellation_outcome_is_derived_from_request_and_status() {
            SalesOrder so = reconstituteWithStatus(SalesOrder.Status.OPEN);
            assertThat(so.cancellationOutcome()).isEqualTo(SalesOrder.CancellationOutcome.NONE);
            so.requestCancellation("changed mind");
            assertThat(so.cancellationOutcome()).isEqualTo(SalesOrder.CancellationOutcome.CANCELLING);
            so.confirmCancellation();
            assertThat(so.cancellationOutcome()).isEqualTo(SalesOrder.CancellationOutcome.CANCELLED);

            // Race-loss: a cancel was requested but a shipment won → the order is
            // shipped and the outcome reads as rejected, with no extra event.
            SalesOrder rejected = SalesOrder.reconstitute(
                SalesOrderId.of(UUID.randomUUID()), "SO-CXL-REJ",
                CUSTOMER, "CUST-001", "Test Customer", LocalDate.now(), null,
                SalesOrder.Status.SHIPPED, Currencies.AUD, BigDecimal.ONE, PaymentTerms.ON_SHIPMENT, null,
                new BigDecimal("100"), BigDecimal.ZERO, new BigDecimal("100"),
                null, Instant.now(), 1L, List.of(line(BigDecimal.ONE, new BigDecimal("100"))));
            assertThat(rejected.cancellationOutcome()).isEqualTo(SalesOrder.CancellationOutcome.CANCELLATION_REJECTED);
        }

        @Test void confirming_cancellation_cancels_the_lines_too() {
            SalesOrder so = reconstituteWithStatus(SalesOrder.Status.RESERVED);
            so.requestCancellation("changed mind");
            so.confirmCancellation();
            assertThat(so.status()).isEqualTo(SalesOrder.Status.CANCELLED);
            assertThat(so.lines()).isNotEmpty().allMatch(SalesOrderLine::isCancelled);
        }

        @Test void confirm_cancels_when_open() {
            SalesOrder so = reconstituteWithStatus(SalesOrder.Status.OPEN);
            so.requestCancellation("customer changed mind");
            so.confirmCancellation();
            assertThat(so.status()).isEqualTo(SalesOrder.Status.CANCELLED);
            assertThat(so.cancelledAt()).isNotNull();
        }

        @Test void confirm_cancels_when_reserved() {
            SalesOrder so = reconstituteWithStatus(SalesOrder.Status.RESERVED);
            so.requestCancellation("supply chain disruption");
            so.confirmCancellation();
            assertThat(so.status()).isEqualTo(SalesOrder.Status.CANCELLED);
        }

        @Test void confirm_is_a_no_op_when_a_line_has_shipped() {
            // The race-loser path: a shipment landed between the request and the
            // ack, so the order must stay shipped, never flip to cancelled.
            SalesOrder so = reconstituteWithStatus(SalesOrder.Status.SHIPPED);
            so.confirmCancellation();
            assertThat(so.status()).isEqualTo(SalesOrder.Status.SHIPPED);
        }

        @Test void rejected_when_already_shipped() {
            SalesOrder so = reconstituteWithStatus(SalesOrder.Status.SHIPPED);
            assertThatThrownBy(() -> so.requestCancellation("too late"))
                .isInstanceOf(SalesOrder.OrderNotCancellableException.class)
                .hasMessageContaining("'shipped'");
        }

        @Test void rejected_when_completed() {
            SalesOrder so = reconstituteWithStatus(SalesOrder.Status.COMPLETED);
            assertThatThrownBy(() -> so.requestCancellation("too late"))
                .isInstanceOf(SalesOrder.OrderNotCancellableException.class);
        }

        @Test void rejected_when_already_cancelled() {
            SalesOrder so = reconstituteWithStatus(SalesOrder.Status.CANCELLED);
            assertThatThrownBy(() -> so.requestCancellation("twice"))
                .isInstanceOf(SalesOrder.OrderNotCancellableException.class);
        }

        @Test void rejected_when_rejected() {
            SalesOrder so = reconstituteWithStatus(SalesOrder.Status.REJECTED);
            assertThatThrownBy(() -> so.requestCancellation("nope"))
                .isInstanceOf(SalesOrder.OrderNotCancellableException.class);
        }

        @Test void cancel_event_carries_reason_and_order_number() {
            SalesOrder so = reconstituteWithStatus(SalesOrder.Status.OPEN);
            so.requestCancellation("test reason");
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
                SalesOrder.Status.RESERVED,
                Currencies.AUD, BigDecimal.ONE, PaymentTerms.ON_SHIPMENT, null,
                new BigDecimal("100"), BigDecimal.ZERO, new BigDecimal("100"),
                null,
                null,
                3L,
                List.of(line(BigDecimal.ONE, new BigDecimal("100")))
            );
            assertThat(so.pullPendingEvents()).isEmpty();
            assertThat(so.status()).isEqualTo(SalesOrder.Status.RESERVED);
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

    @Nested
    class Amend {

        private static final UUID PRODUCT = UUID.fromString("00000000-0000-0000-0000-000000000001");

        private static SalesOrderLine lineWithId(UUID id, BigDecimal qty, BigDecimal price) {
            return new SalesOrderLine(
                id, 10, PRODUCT, "FG-TABLE-001", "Wooden Dining Table",
                qty, price, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                SalesOrder.LineStatus.OPEN
            );
        }

        private static SalesOrder amendable(SalesOrder.Status status, List<SalesOrderLine> lines) {
            return SalesOrder.reconstitute(
                SalesOrderId.of(UUID.randomUUID()),
                "SO-AMEND-001",
                CUSTOMER, "CUST-001", "Test Customer",
                LocalDate.now(), null,
                status,
                Currencies.AUD, BigDecimal.ONE, PaymentTerms.ON_SHIPMENT, null,
                new BigDecimal("100"), BigDecimal.ZERO, new BigDecimal("100"),
                null, null, 1L, lines
            );
        }

        @Test void add_line_appends_recomputes_and_emits() {
            SalesOrder so = amendable(SalesOrder.Status.OPEN,
                List.of(lineWithId(UUID.randomUUID(), new BigDecimal("1"), new BigDecimal("100.00"))));
            SalesOrderLine added = so.addLine(PRODUCT, "FG-CHAIR-001", "Chair",
                new BigDecimal("2"), new BigDecimal("25.00"), BigDecimal.ZERO);

            assertThat(so.lines()).hasSize(2);
            assertThat(added.lineNumber()).isGreaterThan(10);
            // 1*100 + 2*25 = 150
            assertThat(so.subtotalAmount()).isEqualByComparingTo(new BigDecimal("150.00"));
            List<DomainEvent> events = so.pullPendingEvents();
            assertThat(events).hasSize(1).first().isInstanceOf(SalesOrderLineAdded.class);
            // The event carries the recomputed order total for the 360.
            assertThat(((SalesOrderLineAdded) events.get(0)).newOrderTotal())
                .isEqualByComparingTo(new BigDecimal("150.00"));
        }

        @Test void change_line_updates_quantity_and_price_and_emits() {
            UUID lineId = UUID.randomUUID();
            SalesOrder so = amendable(SalesOrder.Status.OPEN,
                List.of(lineWithId(lineId, new BigDecimal("1"), new BigDecimal("100.00"))));
            so.changeLine(lineId, new BigDecimal("3"), new BigDecimal("90.00"));

            assertThat(so.subtotalAmount()).isEqualByComparingTo(new BigDecimal("270.00"));
            List<DomainEvent> events = so.pullPendingEvents();
            assertThat(events).hasSize(1).first().isInstanceOf(SalesOrderLineQuantityChanged.class);
            SalesOrderLineQuantityChanged e = (SalesOrderLineQuantityChanged) events.get(0);
            assertThat(e.previousQuantity()).isEqualByComparingTo(new BigDecimal("1"));
            assertThat(e.newQuantity()).isEqualByComparingTo(new BigDecimal("3"));
            // 3*90 = 270
            assertThat(e.newOrderTotal()).isEqualByComparingTo(new BigDecimal("270.00"));
        }

        @Test void remove_line_softcancels_excludes_from_totals_and_emits() {
            UUID keep = UUID.randomUUID();
            UUID drop = UUID.randomUUID();
            SalesOrder so = amendable(SalesOrder.Status.OPEN, List.of(
                lineWithId(keep, new BigDecimal("1"), new BigDecimal("100.00")),
                lineWithId(drop, new BigDecimal("2"), new BigDecimal("25.00"))
            ));
            so.removeLine(drop);

            assertThat(so.subtotalAmount()).isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(so.lines()).hasSize(2); // soft — row survives
            assertThat(so.lines().stream().filter(l -> l.lineId().equals(drop)).findFirst().orElseThrow().isCancelled())
                .isTrue();
            List<DomainEvent> events = so.pullPendingEvents();
            assertThat(events).hasSize(1).first().isInstanceOf(SalesOrderLineRemoved.class);
            // removed line (2*25) drops out → 1*100 = 100
            assertThat(((SalesOrderLineRemoved) events.get(0)).newOrderTotal())
                .isEqualByComparingTo(new BigDecimal("100.00"));
        }

        @Test void remove_last_live_line_throws() {
            UUID only = UUID.randomUUID();
            SalesOrder so = amendable(SalesOrder.Status.OPEN,
                List.of(lineWithId(only, new BigDecimal("1"), new BigDecimal("100.00"))));
            assertThatThrownBy(() -> so.removeLine(only))
                .isInstanceOf(IllegalStateException.class);
        }

        @Test void change_unknown_line_throws_line_not_found() {
            SalesOrder so = amendable(SalesOrder.Status.OPEN,
                List.of(lineWithId(UUID.randomUUID(), new BigDecimal("1"), new BigDecimal("100.00"))));
            assertThatThrownBy(() -> so.changeLine(UUID.randomUUID(), new BigDecimal("2"), new BigDecimal("10")))
                .isInstanceOf(SalesOrder.LineNotFoundException.class);
        }

        @Test void amend_rejected_once_shipped() {
            // The amend guard reads the lines, so the line must be shipped.
            SalesOrderLine shipped = lineWithId(UUID.randomUUID(), new BigDecimal("1"), new BigDecimal("100.00"));
            shipped.recordShipment(BigDecimal.ONE);
            SalesOrder so = amendable(SalesOrder.Status.SHIPPED, List.of(shipped));
            assertThatThrownBy(() -> so.addLine(PRODUCT, "X", "X", BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ZERO))
                .isInstanceOf(SalesOrder.OrderNotAmendableException.class);
        }

        @Test void amendable_when_reserved() {
            UUID lineId = UUID.randomUUID();
            SalesOrder so = amendable(SalesOrder.Status.RESERVED,
                List.of(lineWithId(lineId, new BigDecimal("1"), new BigDecimal("100.00"))));
            so.changeLine(lineId, new BigDecimal("5"), new BigDecimal("100.00"));
            assertThat(so.subtotalAmount()).isEqualByComparingTo(new BigDecimal("500.00"));
        }
    }

    @Nested
    class RecordShipped {
        private static SalesOrder twoLineOrder() {
            return placeWithLines(List.of(
                line(new BigDecimal("2"), new BigDecimal("110.00")),   // line 0: ordered 2
                line(new BigDecimal("5"), new BigDecimal("130.00"))    // line 1: ordered 5
            ));
        }

        private static SalesOrder.ShippedLineInput ship(SalesOrderLine l, BigDecimal qty) {
            return new SalesOrder.ShippedLineInput(
                l.lineId(), l.productId(), l.productSku(), l.productName(), qty, new BigDecimal("10.00"));
        }

        @Test void partial_shipment_marks_partially_shipped_and_reports_not_fully_shipped() {
            SalesOrder so = twoLineOrder();
            so.pullPendingEvents(); // drain SalesOrderPlaced
            SalesOrderLine line0 = so.lines().get(0);

            SalesOrder.ShipmentOutcome outcome = so.recordShipped(
                UUID.randomUUID(), "SHP-1", LocalDate.of(2026, 6, 2), List.of(ship(line0, new BigDecimal("2"))));

            assertThat(outcome.orderFullyShipped()).isFalse();
            assertThat(so.status()).isEqualTo(SalesOrder.Status.PARTIALLY_SHIPPED);
            assertThat(so.lines().get(0).lineStatus()).isEqualTo(SalesOrder.LineStatus.SHIPPED);
            assertThat(so.lines().get(0).shippedQuantity()).isEqualByComparingTo(new BigDecimal("2"));
            assertThat(so.lines().get(0).backorderedQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
            // line 1 untouched.
            assertThat(so.lines().get(1).lineStatus()).isEqualTo(SalesOrder.LineStatus.OPEN);
            assertThat(so.lines().get(1).shippedQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(so.lines().get(1).backorderedQuantity()).isEqualByComparingTo(new BigDecimal("5"));
            SalesOrderShipped e = (SalesOrderShipped) so.pullPendingEvents().get(0);
            assertThat(e.orderFullyShipped()).isFalse();
        }

        @Test void second_shipment_completes_the_order() {
            SalesOrder so = twoLineOrder();
            so.pullPendingEvents();
            so.recordShipped(UUID.randomUUID(), "SHP-1", LocalDate.now(), List.of(ship(so.lines().get(0), new BigDecimal("2"))));
            so.pullPendingEvents();

            SalesOrder.ShipmentOutcome outcome = so.recordShipped(
                UUID.randomUUID(), "SHP-2", LocalDate.now(), List.of(ship(so.lines().get(1), new BigDecimal("5"))));

            assertThat(outcome.orderFullyShipped()).isTrue();
            assertThat(so.status()).isEqualTo(SalesOrder.Status.SHIPPED);
            assertThat(so.lines()).allSatisfy(l ->
                assertThat(l.lineStatus()).isEqualTo(SalesOrder.LineStatus.SHIPPED));
            SalesOrderShipped e = (SalesOrderShipped) so.pullPendingEvents().get(0);
            assertThat(e.orderFullyShipped()).isTrue();
        }

        @Test void partial_quantity_on_a_line_marks_line_partially_shipped() {
            SalesOrder so = twoLineOrder();
            so.pullPendingEvents();
            SalesOrderLine line1 = so.lines().get(1); // ordered 5

            so.recordShipped(UUID.randomUUID(), "SHP-1", LocalDate.now(), List.of(ship(line1, new BigDecimal("2"))));

            assertThat(so.lines().get(1).lineStatus()).isEqualTo(SalesOrder.LineStatus.PARTIALLY_SHIPPED);
            assertThat(so.lines().get(1).shippedQuantity()).isEqualByComparingTo(new BigDecimal("2"));
            assertThat(so.lines().get(1).backorderedQuantity()).isEqualByComparingTo(new BigDecimal("3"));
            assertThat(so.status()).isEqualTo(SalesOrder.Status.PARTIALLY_SHIPPED);
        }

        @Test void cumulative_shipment_across_two_partials_completes_the_line() {
            SalesOrder so = twoLineOrder();
            SalesOrderLine line1 = so.lines().get(1); // ordered 5
            so.recordShipped(UUID.randomUUID(), "SHP-1", LocalDate.now(), List.of(ship(line1, new BigDecimal("2"))));
            so.recordShipped(UUID.randomUUID(), "SHP-2", LocalDate.now(), List.of(ship(line1, new BigDecimal("3"))));

            assertThat(so.lines().get(1).shippedQuantity()).isEqualByComparingTo(new BigDecimal("5"));
            assertThat(so.lines().get(1).lineStatus()).isEqualTo(SalesOrder.LineStatus.SHIPPED);
        }

        @Test void over_shipment_is_rejected() {
            SalesOrder so = twoLineOrder();
            SalesOrderLine line0 = so.lines().get(0); // ordered 2
            assertThatThrownBy(() -> so.recordShipped(
                UUID.randomUUID(), "SHP-1", LocalDate.now(), List.of(ship(line0, new BigDecimal("3")))))
                .isInstanceOf(IllegalStateException.class);
        }
    }

    /**
     * Properties of the status fold {@code ρ = classify(meet, join)}
     * (docs/composed-state-machines.html §3): the header status is a pure function of
     * the live-line multiset, invariant under order/insert/batch, neutral to
     * cancelled lines, and monotone through the forward flow.
     */
    @Nested
    class StatusFold {

        private static SalesOrderLine reservableLine(UUID id, int lineNumber, BigDecimal qty) {
            return new SalesOrderLine(
                id, lineNumber, UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "FG-TABLE-001", "Wooden Dining Table",
                qty, new BigDecimal("100.00"), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, SalesOrder.LineStatus.OPEN);
        }

        private static SalesOrderLine reservableLine(UUID id, BigDecimal qty) {
            return reservableLine(id, 10, qty);
        }

        /** Two open lines with distinct line numbers (10, 20) so per-line reservation keys are unambiguous. */
        private static SalesOrder twoOpenLines() {
            return placeWithLines(List.of(
                reservableLine(UUID.randomUUID(), 10, new BigDecimal("2")),
                reservableLine(UUID.randomUUID(), 20, new BigDecimal("5"))));
        }

        private static SalesOrder.ShippedLineInput ship(SalesOrderLine l, BigDecimal qty) {
            return new SalesOrder.ShippedLineInput(
                l.lineId(), l.productId(), l.productSku(), l.productName(), qty, new BigDecimal("10.00"));
        }

        @Test void all_open_is_open() {
            assertThat(twoOpenLines().status()).isEqualTo(SalesOrder.Status.OPEN);
        }

        @Test void one_fully_reserved_one_open_is_partially_reserved() {
            SalesOrder so = twoOpenLines();
            // reserve just line 0 (line_number 10) in full, leave line 1 (20) open:
            // meet=open, join=reserved → straddle → partially_reserved at the order.
            so.recordReservation(Map.of(10, new BigDecimal("2")));
            assertThat(so.lines().get(0).lineStatus()).isEqualTo(SalesOrder.LineStatus.RESERVED);
            assertThat(so.status()).isEqualTo(SalesOrder.Status.PARTIALLY_RESERVED);
        }

        @Test void all_lines_reserved_is_reserved() {
            SalesOrder so = twoOpenLines();
            // reserve both lines in full → meet=join=reserved → reserved.
            so.recordReservation(Map.of(10, new BigDecimal("2"), 20, new BigDecimal("5")));
            assertThat(so.status()).isEqualTo(SalesOrder.Status.RESERVED);
        }

        @Test void partial_reservation_is_partially_reserved() {
            SalesOrder so = twoOpenLines();
            so.recordReservation(Map.of(10, new BigDecimal("1"), 20, new BigDecimal("3")));
            assertThat(so.lines().get(0).lineStatus()).isEqualTo(SalesOrder.LineStatus.PARTIALLY_RESERVED);
            assertThat(so.status()).isEqualTo(SalesOrder.Status.PARTIALLY_RESERVED);
        }

        @Test void zero_reservation_leaves_line_open() {
            SalesOrder so = twoOpenLines();
            so.recordReservation(Map.of(10, BigDecimal.ZERO));
            assertThat(so.lines().get(0).lineStatus()).isEqualTo(SalesOrder.LineStatus.OPEN);
            assertThat(so.status()).isEqualTo(SalesOrder.Status.OPEN);
        }

        @Test void order_insensitivity_status_independent_of_line_order() {
            // Same multiset {SHIPPED, OPEN} reached two ways → same status.
            SalesOrder a = twoOpenLines();
            a.recordShipped(UUID.randomUUID(), "S", LocalDate.now(), List.of(ship(a.lines().get(0), new BigDecimal("2"))));
            SalesOrder b = twoOpenLines();
            b.recordShipped(UUID.randomUUID(), "S", LocalDate.now(), List.of(ship(b.lines().get(1), new BigDecimal("5"))));
            assertThat(a.status()).isEqualTo(SalesOrder.Status.PARTIALLY_SHIPPED);
            assertThat(b.status()).isEqualTo(a.status());
        }

        @Test void cancelled_line_is_neutral_to_the_fold() {
            UUID keep = UUID.randomUUID();
            UUID drop = UUID.randomUUID();
            SalesOrder so = SalesOrder.reconstitute(
                SalesOrderId.of(UUID.randomUUID()), "SO-FOLD-001",
                CUSTOMER, "C", "Cust", LocalDate.now(), null,
                SalesOrder.Status.OPEN, Currencies.AUD, BigDecimal.ONE, PaymentTerms.ON_SHIPMENT, null,
                new BigDecimal("700"), BigDecimal.ZERO, new BigDecimal("700"), null, null, 1L,
                List.of(reservableLine(keep, new BigDecimal("2")), reservableLine(drop, new BigDecimal("5"))));
            so.removeLine(drop);
            // Only the live line remains; it is still open → open (the
            // cancelled line contributes nothing to the rollup).
            assertThat(so.status()).isEqualTo(SalesOrder.Status.OPEN);
        }

        @Test void all_live_shipped_completes_ship_axis_even_with_a_removed_line() {
            UUID keep = UUID.randomUUID();
            UUID drop = UUID.randomUUID();
            SalesOrder so = SalesOrder.reconstitute(
                SalesOrderId.of(UUID.randomUUID()), "SO-FOLD-002",
                CUSTOMER, "C", "Cust", LocalDate.now(), null,
                SalesOrder.Status.OPEN, Currencies.AUD, BigDecimal.ONE, PaymentTerms.ON_SHIPMENT, null,
                new BigDecimal("700"), BigDecimal.ZERO, new BigDecimal("700"), null, null, 1L,
                List.of(reservableLine(keep, new BigDecimal("2")), reservableLine(drop, new BigDecimal("5"))));
            so.removeLine(drop);
            so.recordShipped(UUID.randomUUID(), "S", LocalDate.now(),
                List.of(ship(so.lines().get(0), new BigDecimal("2"))));
            // The removed line is filtered, so "every live line shipped" holds →
            // order shipped (cancelled-neutrality on the ship axis).
            assertThat(so.status()).isEqualTo(SalesOrder.Status.SHIPPED);
        }

        @Test void fold_does_not_undo_a_cancelled_terminal() {
            SalesOrder so = twoOpenLines();
            so.requestCancellation("changed mind");
            so.confirmCancellation();
            // recordReservation runs the fold, but the terminal is absorbing.
            so.recordReservation(Map.of(10, new BigDecimal("2"), 20, new BigDecimal("5")));
            assertThat(so.status()).isEqualTo(SalesOrder.Status.CANCELLED);
        }

        @Test void reject_sets_rejected_and_emits_cancellation_request_and_rejected() {
            SalesOrder so = twoOpenLines();
            so.pullPendingEvents(); // drain placed
            so.reject("unsourceable");
            assertThat(so.status()).isEqualTo(SalesOrder.Status.REJECTED);
            // SalesOrderCancellationRequested drives inventory's reservation release;
            // SalesOrderRejected is the confirmed non-shippable terminal finance refunds on.
            assertThat(so.pullPendingEvents())
                .hasSize(2)
                .anyMatch(SalesOrderCancellationRequested.class::isInstance)
                .anyMatch(com.northwood.sales.domain.events.SalesOrderRejected.class::isInstance);
        }

        @Test void complete_requires_fully_shipped() {
            SalesOrder so = twoOpenLines();
            assertThatThrownBy(so::complete).isInstanceOf(IllegalStateException.class);
        }

        @Test void complete_from_shipped_reaches_completed() {
            SalesOrder so = twoOpenLines();
            so.recordShipped(UUID.randomUUID(), "S", LocalDate.now(), List.of(
                ship(so.lines().get(0), new BigDecimal("2")),
                ship(so.lines().get(1), new BigDecimal("5"))));
            assertThat(so.status()).isEqualTo(SalesOrder.Status.SHIPPED);
            so.complete();
            assertThat(so.status()).isEqualTo(SalesOrder.Status.COMPLETED);
        }
    }
}
