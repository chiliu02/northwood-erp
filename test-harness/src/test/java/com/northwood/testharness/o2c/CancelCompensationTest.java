package com.northwood.testharness.o2c;

import com.northwood.sales.domain.events.SalesOrderCancellationRequested;
import com.northwood.sales.domain.events.SalesOrderCompensated;

import static org.assertj.core.api.Assertions.assertThat;

import com.northwood.inventory.domain.events.InventorySalesOrderCancellationApplied;
import com.northwood.sales.application.dto.PlaceOrderCommand;
import com.northwood.sales.application.dto.PlaceOrderCommand.OrderLine;
import com.northwood.sales.domain.Customer;
import com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga;
import com.northwood.shared.application.outbox.OutboxRow;
import com.northwood.testharness.inmemory.SynchronousBus;
import com.northwood.testharness.kits.InventoryTestKit;
import com.northwood.testharness.kits.SalesTestKit;
import com.northwood.shared.domain.Currencies;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * §2.5.1 Phase D — O2C cancel-compensation E2E test.
 *
 * <p>Subsumes the §2.6 cancel-order smoke test: walks the saga from
 * {@code placeOrder → cancel → compensating → (inventory ack) → compensated}
 * entirely through the harness, asserting that {@code sales.SalesOrderCompensated}
 * fires once inventory acks the cancel.
 *
 * <p>§2.40 retired the manufacturing leg of the compensation gate (no work order
 * is bound to a sales order post-§2.37), so inventory is the sole compensation
 * ack — its arrival completes the compensation outright.
 */
class CancelCompensationTest {

    @Test
    void cancel_completes_compensation_on_inventory_ack() throws Exception {
        ObjectMapper json = new ObjectMapper();
        SynchronousBus bus = new SynchronousBus();

        SalesTestKit sales = new SalesTestKit(bus, json);
        InventoryTestKit inventory = new InventoryTestKit(bus, json);

        sales.customers.put("CUST-001", "Acme Corp", Customer.Status.ACTIVE);
        UUID productId = UUID.randomUUID();
        sales.productCards.put(productId, new BigDecimal("100.00"), Currencies.AUD);
        inventory.seedStock(productId, new BigDecimal("50"));

        // Step 1: place + cancel.
        UUID orderId = sales.placeOrder(new PlaceOrderCommand(
            "SO-CXL-001", "CUST-001",
            LocalDate.of(2026, 5, 15), Currencies.AUD, null,
            List.of(new OrderLine(productId, "WIDGET-001", "Widget",
                new BigDecimal("3"), null, BigDecimal.ZERO))
        ));

        sales.cancel(orderId, "customer changed mind");

        // Saga should be in compensating; sales outbox holds SalesOrderCancellationRequested.
        SalesOrderFulfilmentSaga saga = sales.findSagaBySalesOrderId(orderId).orElseThrow();
        assertThat(saga.state()).isEqualTo(SalesOrderFulfilmentSaga.COMPENSATING);

        // Step 2: drain the bus. Inventory's cancel handler picks up
        // sales.SalesOrderCancellationRequested → releases (no-op, no
        // reservation existed) → emits inventory.SalesOrderCancellationApplied.
        // Sales' InventoryCancellationAppliedHandler picks it up → records the
        // inventory ack on the saga → compensating → compensated → emits
        // sales.SalesOrderCompensated.
        bus.drain();

        // Step 3: assertions.
        SalesOrderFulfilmentSaga sagaFinal = sales.findSagaBySalesOrderId(orderId).orElseThrow();
        assertThat(sagaFinal.state())
            .as("inventory ack → compensated")
            .isEqualTo(SalesOrderFulfilmentSaga.COMPENSATED);

        assertThat(sales.outbox.all())
            .extracting(OutboxRow::getEventType)
            .contains(SalesOrderCancellationRequested.EVENT_TYPE, SalesOrderCompensated.EVENT_TYPE);

        assertThat(inventory.outbox.all())
            .extracting(OutboxRow::getEventType)
            .contains(InventorySalesOrderCancellationApplied.EVENT_TYPE);

        assertThat(sales.outbox.findPending(100)).isEmpty();
        assertThat(inventory.outbox.findPending(100)).isEmpty();
    }
}
