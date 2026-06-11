package com.northwood.testharness.o2c;

import static org.assertj.core.api.Assertions.assertThat;

import com.northwood.inventory.domain.events.SalesOrderLineReservationChanged;
import com.northwood.sales.application.dto.AddOrderLineCommand;
import com.northwood.sales.application.dto.PlaceOrderCommand;
import com.northwood.sales.application.dto.PlaceOrderCommand.OrderLine;
import com.northwood.sales.application.dto.RemoveOrderLineCommand;
import com.northwood.sales.domain.Customer;
import com.northwood.sales.domain.SalesOrderId;
import com.northwood.sales.domain.SalesOrderLine;
import com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga;
import com.northwood.shared.application.outbox.OutboxRow;
import com.northwood.shared.domain.Currencies;
import com.northwood.testharness.inmemory.SynchronousBus;
import com.northwood.testharness.kits.InventoryTestKit;
import com.northwood.testharness.kits.SalesTestKit;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Line amendment end-to-end through sales + inventory + the fulfilment saga.
 *
 * <p>Exercises the incremental reservation path: a line added to an
 * already-reserved order is reserved incrementally (the order stays
 * {@code SUPPLY_SECURED}); a short line removed from a shortage-parked order
 * releases its reservation, cancels its in-flight replenishment, and un-parks
 * the saga to {@code SUPPLY_SECURED}.
 */
class OrderToCashLineAmendmentTest {

    private static UUID lineIdForProduct(SalesTestKit sales, UUID orderId, UUID productId) {
        return sales.orders.findById(SalesOrderId.of(orderId)).orElseThrow().lines().stream()
            .filter(l -> l.productId().equals(productId) && !l.isCancelled())
            .map(SalesOrderLine::lineId)
            .findFirst().orElseThrow();
    }

    @Test
    void add_line_to_reserved_order_reserves_incrementally_and_stays_SUPPLY_SECURED() {
        ObjectMapper json = new ObjectMapper();
        SynchronousBus bus = new SynchronousBus();
        SalesTestKit sales = new SalesTestKit(bus, json);
        InventoryTestKit inventory = new InventoryTestKit(bus, json);

        sales.customers.put("CUST-001", "Acme Corp", Customer.Status.ACTIVE);
        UUID productA = UUID.randomUUID();
        UUID productB = UUID.randomUUID();
        sales.productCards.put(productA, new BigDecimal("100.00"), Currencies.AUD);
        sales.productCards.put(productB, new BigDecimal("40.00"), Currencies.AUD);
        inventory.seedStock(productA, new BigDecimal("50"));
        inventory.seedStock(productB, new BigDecimal("50"));

        UUID orderId = sales.placeOrder(new PlaceOrderCommand(
            "SO-AMEND-1", "CUST-001", LocalDate.of(2026, 6, 6), Currencies.AUD, null,
            List.of(new OrderLine(productA, "FG-A", "Good A", new BigDecimal("3"), null, BigDecimal.ZERO))
        ));
        sales.advanceSagaWorker();
        bus.drain();
        assertThat(sales.findSagaBySalesOrderId(orderId).orElseThrow().state())
            .isEqualTo(SalesOrderFulfilmentSaga.SUPPLY_SECURED);

        // Amend: add a second, fully-reservable line.
        sales.service.addLine(new AddOrderLineCommand(
            orderId, null, productB, "FG-B", "Good B", new BigDecimal("2"), null, BigDecimal.ZERO));
        bus.drain();

        // Inventory reserved the added line incrementally; the saga stays SUPPLY_SECURED.
        assertThat(inventory.outbox.all()).extracting(OutboxRow::getEventType)
            .contains(SalesOrderLineReservationChanged.EVENT_TYPE);
        assertThat(sales.findSagaBySalesOrderId(orderId).orElseThrow().state())
            .isEqualTo(SalesOrderFulfilmentSaga.SUPPLY_SECURED);
    }

    @Test
    void remove_short_line_cancels_replenishment_and_unparks_to_SUPPLY_SECURED() {
        ObjectMapper json = new ObjectMapper();
        SynchronousBus bus = new SynchronousBus();
        SalesTestKit sales = new SalesTestKit(bus, json);
        InventoryTestKit inventory = new InventoryTestKit(bus, json);

        sales.customers.put("CUST-001", "Acme Corp", Customer.Status.ACTIVE);
        UUID productA = UUID.randomUUID();   // covered
        UUID productB = UUID.randomUUID();   // short → routed replenishment
        sales.productCards.put(productA, new BigDecimal("100.00"), Currencies.AUD);
        sales.productCards.put(productB, new BigDecimal("40.00"), Currencies.AUD);
        inventory.seedStock(productA, new BigDecimal("50"));
        // No stock for B; classify it as purchased so the shortage raises a
        // routed replenishment (not an unsourceable cancel that would reject).
        inventory.productReplenishment.put(productB, true, false);

        UUID orderId = sales.placeOrder(new PlaceOrderCommand(
            "SO-AMEND-2", "CUST-001", LocalDate.of(2026, 6, 6), Currencies.AUD, null,
            List.of(
                new OrderLine(productA, "FG-A", "Good A", new BigDecimal("3"), null, BigDecimal.ZERO),
                new OrderLine(productB, "FG-B", "Good B", new BigDecimal("2"), null, BigDecimal.ZERO)
            )
        ));
        sales.advanceSagaWorker();
        bus.drain();

        // B is short → saga parked at stock_reservation_incomplete with B's
        // replenishment open.
        assertThat(sales.findSagaBySalesOrderId(orderId).orElseThrow().state())
            .isEqualTo(SalesOrderFulfilmentSaga.STOCK_RESERVATION_INCOMPLETE);
        UUID lineB = lineIdForProduct(sales, orderId, productB);
        assertThat(inventory.replenishmentRequests.findOpenForSalesOrderLine(orderId, lineB)).hasSize(1);

        // Amend: remove the short line.
        sales.service.removeLine(new RemoveOrderLineCommand(orderId, lineB, null));
        bus.drain();

        // Its in-flight replenishment is cancelled and the saga un-parks.
        assertThat(inventory.replenishmentRequests.findOpenForSalesOrderLine(orderId, lineB)).isEmpty();
        assertThat(sales.findSagaBySalesOrderId(orderId).orElseThrow().state())
            .isEqualTo(SalesOrderFulfilmentSaga.SUPPLY_SECURED);
    }
}
