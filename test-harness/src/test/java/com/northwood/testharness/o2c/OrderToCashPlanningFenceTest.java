package com.northwood.testharness.o2c;

import static org.assertj.core.api.Assertions.assertThat;

import com.northwood.inventory.domain.events.InventorySalesOrderCancellationApplied;
import com.northwood.sales.application.dto.PlaceOrderCommand;
import com.northwood.sales.application.dto.PlaceOrderCommand.OrderLine;
import com.northwood.sales.domain.Customer;
import com.northwood.sales.domain.events.SalesOrderCancellationRequested;
import com.northwood.sales.domain.events.SalesOrderCompensated;
import com.northwood.sales.domain.events.StockReservationRequested;
import com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga;
import com.northwood.shared.application.outbox.OutboxRow;
import com.northwood.shared.domain.Currencies;
import com.northwood.testharness.inmemory.SynchronousBus;
import com.northwood.testharness.kits.InventoryTestKit;
import com.northwood.testharness.kits.SalesTestKit;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Planning-time-fence gate (3.14) at the fulfilment-saga worker. The gate is
 * evaluated once on entry to the reservation leg against the worker's injected
 * clock ({@link SalesTestKit#setClock}); the wake out of {@code awaiting_release}
 * is driven by {@code next_retry_at <= now()} and does NOT re-evaluate the fence
 * (decide-once — see {@code docs/sagas.md} → Timed releases). No real time
 * passes in any of these tests.
 */
class OrderToCashPlanningFenceTest {

    private static final UUID PRODUCT = UUID.randomUUID();
    private static final Instant CLOCK = Instant.parse("2026-06-01T00:00:00Z");

    private SalesTestKit fencedKit(int fenceDays) {
        SalesTestKit sales = new SalesTestKit(new SynchronousBus(), new ObjectMapper());
        sales.setClock(CLOCK);
        sales.customers.put("CUST-001", "Acme Corp", Customer.Status.ACTIVE);
        sales.productCards.put(PRODUCT, new BigDecimal("100.00"), Currencies.AUD);
        sales.lineSnapshots.withFence(PRODUCT, fenceDays);
        return sales;
    }

    private UUID place(SalesTestKit sales, LocalDate needBy) {
        return sales.placeOrder(new PlaceOrderCommand(
            "SO-0001", "CUST-001", needBy, Currencies.AUD, null,
            List.of(new OrderLine(PRODUCT, "WIDGET-001", "Widget",
                new BigDecimal("3"), null, BigDecimal.ZERO))));
    }

    @Test
    void far_future_fenced_order_parks_at_awaiting_release() {
        SalesTestKit sales = fencedKit(7);
        UUID orderId = place(sales, LocalDate.of(2026, 7, 1));   // need-by 30d after the clock

        sales.advanceSagaWorker();

        SalesOrderFulfilmentSaga saga = sales.findSagaBySalesOrderId(orderId).orElseThrow();
        assertThat(saga.state()).isEqualTo(SalesOrderFulfilmentSaga.AWAITING_RELEASE);
        // releaseAt = need-by (2026-07-01) − 7d = 2026-06-24, UTC start-of-day.
        assertThat(saga.nextRetryAt()).isEqualTo(Instant.parse("2026-06-24T00:00:00Z"));
        assertThat(sales.outbox.all())
            .as("no reservation emitted while parked")
            .extracting(OutboxRow::getEventType)
            .doesNotContain(StockReservationRequested.EVENT_TYPE);
    }

    @Test
    void zero_fence_reserves_immediately() {
        SalesTestKit sales = fencedKit(0);
        UUID orderId = place(sales, LocalDate.of(2026, 7, 1));

        sales.advanceSagaWorker();

        assertThat(sales.findSagaBySalesOrderId(orderId).orElseThrow().state())
            .isEqualTo(SalesOrderFulfilmentSaga.STOCK_RESERVATION_REQUESTED);
        assertThat(sales.outbox.all())
            .extracting(OutboxRow::getEventType)
            .contains(StockReservationRequested.EVENT_TYPE);
    }

    @Test
    void elapsed_fence_reserves_immediately() {
        SalesTestKit sales = fencedKit(7);
        // need-by − 7d = 2026-05-29, already before the clock (2026-06-01).
        UUID orderId = place(sales, LocalDate.of(2026, 6, 5));

        sales.advanceSagaWorker();

        assertThat(sales.findSagaBySalesOrderId(orderId).orElseThrow().state())
            .isEqualTo(SalesOrderFulfilmentSaga.STOCK_RESERVATION_REQUESTED);
    }

    @Test
    void wake_from_awaiting_release_emits_without_regating() {
        SalesTestKit sales = fencedKit(7);
        UUID orderId = place(sales, LocalDate.of(2026, 7, 1));
        sales.advanceSagaWorker();   // parks at awaiting_release (releaseAt = 2026-06-24)

        SalesOrderFulfilmentSaga parked = sales.findSagaBySalesOrderId(orderId).orElseThrow();
        assertThat(parked.state()).isEqualTo(SalesOrderFulfilmentSaga.AWAITING_RELEASE);

        // Simulate the release date arriving: fast-forward next_retry_at into the
        // (real) past so the in-memory poll re-claims it. Crucially, the worker
        // clock stays at 2026-06-01 — still before releaseAt — so a passing test
        // proves the wake does NOT re-evaluate the fence (decide-once).
        parked.parkUntil(Instant.now().minusSeconds(1));
        sales.sagas.update(parked);

        sales.advanceSagaWorker();   // wakes

        SalesOrderFulfilmentSaga woken = sales.findSagaBySalesOrderId(orderId).orElseThrow();
        assertThat(woken.state()).isEqualTo(SalesOrderFulfilmentSaga.STOCK_RESERVATION_REQUESTED);
        assertThat(sales.outbox.all())
            .extracting(OutboxRow::getEventType)
            .contains(StockReservationRequested.EVENT_TYPE);
    }

    /**
     * Cancelling a still-parked order has nothing reserved. The compensation
     * gate stays uniformly strict (sales waits for inventory's ack); inventory
     * answers with {@code reservationsReleased = 0} so the saga still reaches
     * {@code compensated}. This is the inventory-side tolerance documented on
     * {@code InventorySalesOrderCancellationApplied} — confirmed here for the new
     * {@code awaiting_release} entry point (which also exercises the
     * {@code awaiting_release → compensating} transition).
     */
    @Test
    void cancel_from_awaiting_release_completes_compensation_with_nothing_reserved() throws Exception {
        ObjectMapper json = new ObjectMapper();
        SynchronousBus bus = new SynchronousBus();
        SalesTestKit sales = new SalesTestKit(bus, json);
        InventoryTestKit inventory = new InventoryTestKit(bus, json);

        sales.setClock(CLOCK);
        sales.customers.put("CUST-001", "Acme Corp", Customer.Status.ACTIVE);
        sales.productCards.put(PRODUCT, new BigDecimal("100.00"), Currencies.AUD);
        sales.lineSnapshots.withFence(PRODUCT, 7);
        inventory.seedStock(PRODUCT, new BigDecimal("50"));

        UUID orderId = sales.placeOrder(new PlaceOrderCommand(
            "SO-CXL-FENCE-001", "CUST-001", LocalDate.of(2026, 7, 1), Currencies.AUD, null,
            List.of(new OrderLine(PRODUCT, "WIDGET-001", "Widget",
                new BigDecimal("3"), null, BigDecimal.ZERO))));

        sales.advanceSagaWorker();   // parks at awaiting_release
        assertThat(sales.findSagaBySalesOrderId(orderId).orElseThrow().state())
            .isEqualTo(SalesOrderFulfilmentSaga.AWAITING_RELEASE);

        sales.cancel(orderId, "customer changed mind before release");
        assertThat(sales.findSagaBySalesOrderId(orderId).orElseThrow().state())
            .as("cancel only requests — the saga is untouched until inventory confirms")
            .isEqualTo(SalesOrderFulfilmentSaga.AWAITING_RELEASE);

        bus.drain();

        assertThat(sales.findSagaBySalesOrderId(orderId).orElseThrow().state())
            .as("inventory ack (nothing to release) → compensated")
            .isEqualTo(SalesOrderFulfilmentSaga.COMPENSATED);

        OutboxRow ack = inventory.outbox.all().stream()
            .filter(r -> InventorySalesOrderCancellationApplied.EVENT_TYPE.equals(r.getEventType()))
            .findFirst().orElseThrow();
        InventorySalesOrderCancellationApplied payload =
            json.readValue(ack.getPayload(), InventorySalesOrderCancellationApplied.class);
        assertThat(payload.reservationsReleased())
            .as("nothing was reserved while parked — released count is 0")
            .isZero();

        assertThat(sales.outbox.all())
            .extracting(OutboxRow::getEventType)
            .contains(SalesOrderCancellationRequested.EVENT_TYPE, SalesOrderCompensated.EVENT_TYPE)
            .doesNotContain(StockReservationRequested.EVENT_TYPE);

        assertThat(sales.outbox.findPending(100)).isEmpty();
        assertThat(inventory.outbox.findPending(100)).isEmpty();
    }
}
