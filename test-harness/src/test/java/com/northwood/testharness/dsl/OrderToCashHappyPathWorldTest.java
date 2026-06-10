package com.northwood.testharness.dsl;

import static org.assertj.core.api.Assertions.assertThat;

import com.northwood.finance.domain.events.CustomerInvoiceCreated;
import com.northwood.finance.domain.events.CustomerPaymentReceived;
import com.northwood.inventory.domain.events.ShipmentPosted;
import com.northwood.inventory.domain.events.StockReserved;
import com.northwood.sales.domain.SalesOrder;
import com.northwood.sales.domain.events.SalesOrderPlaced;
import com.northwood.sales.domain.events.SalesOrderReadyToShip;
import com.northwood.sales.domain.events.SalesOrderShipped;
import com.northwood.sales.domain.events.StockReservationRequested;
import com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Slice-1 spike for the acceptance-test DSL (see {@code docs/test-harness-dsl.md} §9.1).
 *
 * <p>Re-derives {@code o2c/OrderToCashHappyPathTest}'s assertions through the
 * {@link World} fixture — same real services, saga worker, handlers, and serde
 * — but with <strong>zero</strong> hand-placed {@code advanceSagaWorker()} /
 * {@code bus.drain()} calls. The whole infrastructure-timing dance is folded
 * into {@link World#settle()}, which runs implicitly after each action.
 *
 * <p>No fluent {@code given/when/then} sugar yet: the point of this slice is to
 * prove {@code settle()} faithfully reproduces the saga progression. The fluent
 * builders land in slice 2 on top of this same World.
 */
class OrderToCashHappyPathWorldTest {

    @Test
    void place_order_to_completed_through_world_with_zero_hand_drains() {
        World world = new World();

        // ── guard: a priced product, in stock, for a known customer ──
        world.seedCustomer("CUST-001", "Acme Corp");
        world.seedProduct("FG-001", "Finished Good 1", new BigDecimal("100.00"));
        world.seedStock("FG-001", new BigDecimal("50"));

        // ── trigger: customer places an order for 3 units ──
        world.placeOrder("SO-9001", "CUST-001",
            List.of(new World.OrderLineSpec("FG-001", new BigDecimal("3"))));

        // ── outcome: full reservation shortcuts straight to ready_to_ship ──
        assertThat(world.sagaState("SO-9001")).isEqualTo(SalesOrderFulfilmentSaga.READY_TO_SHIP);
        assertThat(world.orderStatus("SO-9001")).contains(SalesOrder.Status.RESERVED);

        // ── trigger: the warehouse ships all 3 units ──
        world.ship("SHIP-001", "SO-9001",
            List.of(new World.ShipLineSpec("FG-001", new BigDecimal("3"), new BigDecimal("60.00"))));

        // ── outcome: a commercial invoice for 3 × 100 is raised; saga at invoice_created ──
        assertThat(world.sagaState("SO-9001")).isEqualTo(SalesOrderFulfilmentSaga.INVOICE_CREATED);
        assertThat(world.commercialInvoice("SO-9001")).isPresent();
        assertThat(world.commercialInvoice("SO-9001").orElseThrow().totalAmount())
            .isEqualByComparingTo("300.00");

        // ── trigger: customer settles the invoice in full ──
        world.pay("PAY-001", "SO-9001", new BigDecimal("300.00"));

        // ── outcome: order completes; the expected events crossed services ──
        assertThat(world.sagaState("SO-9001")).isEqualTo(SalesOrderFulfilmentSaga.COMPLETED);
        assertThat(world.orderStatus("SO-9001")).contains(SalesOrder.Status.COMPLETED);

        assertThat(world.publishedEventTypes()).contains(
            SalesOrderPlaced.EVENT_TYPE,
            StockReservationRequested.EVENT_TYPE,
            StockReserved.EVENT_TYPE,
            SalesOrderReadyToShip.EVENT_TYPE,
            ShipmentPosted.EVENT_TYPE,
            SalesOrderShipped.EVENT_TYPE,
            CustomerInvoiceCreated.EVENT_TYPE,
            CustomerPaymentReceived.EVENT_TYPE);
    }
}
