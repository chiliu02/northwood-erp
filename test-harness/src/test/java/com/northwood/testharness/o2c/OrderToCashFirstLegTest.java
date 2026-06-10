package com.northwood.testharness.o2c;

import com.northwood.inventory.domain.events.StockReserved;
import com.northwood.sales.domain.events.SalesOrderPlaced;
import com.northwood.sales.domain.events.StockReservationRequested;

import static org.assertj.core.api.Assertions.assertThat;

import com.northwood.sales.application.dto.PlaceOrderCommand;
import com.northwood.sales.domain.Customer;
import com.northwood.sales.application.dto.PlaceOrderCommand.OrderLine;
import com.northwood.sales.domain.Customer;
import com.northwood.sales.domain.SalesOrder;
import com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga;
import com.northwood.shared.application.outbox.OutboxRow;
import com.northwood.testharness.inmemory.SynchronousBus;
import com.northwood.testharness.kits.InventoryTestKit;
import com.northwood.testharness.kits.SalesTestKit;
import com.northwood.shared.domain.Currencies;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Proof-of-pattern E2E test for the order-to-cash flow's
 * first leg, now driven by the authentic sales saga worker:
 * {@code placeOrder} → outbox emits {@code SalesOrderPlaced} → worker drains
 * {@code started → stock_reservation_requested} (emitting
 * {@code StockReservationRequested}) → bus delivers to inventory's handler
 * → inventory fully reserves and emits {@code StockReserved} → sales'
 * handler shortcuts the saga past the manufacturing leg directly to
 * {@code ready_to_ship} (full reservation) and projects the order header
 * status to {@code in_fulfilment}.
 */
class OrderToCashFirstLegTest {

    @Test
    void place_order_then_full_reservation_shortcuts_to_ready_to_ship() {
        ObjectMapper json = new ObjectMapper();
        SynchronousBus bus = new SynchronousBus();

        SalesTestKit sales = new SalesTestKit(bus, json);
        InventoryTestKit inventory = new InventoryTestKit(bus, json);

        // Seed: one active customer, one product with catalog pricing, enough stock.
        sales.customers.put("CUST-001", "Acme Corp", Customer.Status.ACTIVE);
        UUID productId = UUID.randomUUID();
        sales.productCards.put(productId, new BigDecimal("100.00"), Currencies.AUD);
        inventory.seedStock(productId, new BigDecimal("50"));

        // Step 1: place the order. Aggregate emits SalesOrderPlaced; saga
        // inserted at "started".
        UUID orderId = sales.placeOrder(new PlaceOrderCommand(
            "SO-0001", "CUST-001",
            LocalDate.of(2026, 5, 15),
            Currencies.AUD, null,
            List.of(new OrderLine(
                productId, "WIDGET-001", "Widget",
                new BigDecimal("3"), null, BigDecimal.ZERO
            ))
        ));

        Optional<SalesOrderFulfilmentSaga> sagaAfterPlace = sales.findSagaBySalesOrderId(orderId);
        assertThat(sagaAfterPlace).isPresent();
        assertThat(sagaAfterPlace.get().state()).isEqualTo(SalesOrderFulfilmentSaga.STARTED);

        // Step 2: drive the saga worker. started → stock_reservation_requested
        // (emits StockReservationRequested into sales' outbox).
        sales.advanceSagaWorker();

        SalesOrderFulfilmentSaga sagaAfterWorker = sales.findSagaBySalesOrderId(orderId).orElseThrow();
        assertThat(sagaAfterWorker.state()).isEqualTo(SalesOrderFulfilmentSaga.STOCK_RESERVATION_REQUESTED);

        // Step 3: drain the bus.
        //   sales.SalesOrderPlaced → no handler in this kit (sales-internal).
        //   sales.StockReservationRequested → inventory's handler reserves, emits inventory.StockReserved.
        //   inventory.StockReserved → sales' StockReservedHandler advances saga + projects status.
        bus.drain();

        // Step 4: assertions.
        SalesOrderFulfilmentSaga sagaAfterReserve = sales.findSagaBySalesOrderId(orderId).orElseThrow();
        assertThat(sagaAfterReserve.state()).isEqualTo(SalesOrderFulfilmentSaga.READY_TO_SHIP);

        assertThat(sales.orderStatus(orderId))
            .as("order header folds to reserved — every line fully reserved")
            .contains(SalesOrder.Status.RESERVED);

        assertThat(sales.outbox.all())
            .extracting(OutboxRow::getEventType)
            .contains(SalesOrderPlaced.EVENT_TYPE, StockReservationRequested.EVENT_TYPE);

        assertThat(inventory.outbox.all())
            .as("inventory emitted StockReserved")
            .extracting(OutboxRow::getEventType)
            .contains(StockReserved.EVENT_TYPE);

        // No pending rows remain anywhere.
        assertThat(sales.outbox.findPending(100)).isEmpty();
        assertThat(inventory.outbox.findPending(100)).isEmpty();
    }
}
