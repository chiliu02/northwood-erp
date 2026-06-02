package com.northwood.testharness.o2c;

import static org.assertj.core.api.Assertions.assertThat;

import com.northwood.inventory.domain.ReplenishmentRequest;
import com.northwood.inventory.domain.events.ReplenishmentFulfilled;
import com.northwood.inventory.domain.events.ReplenishmentRequested;
import com.northwood.manufacturing.domain.events.WorkOrderCreated;
import com.northwood.manufacturing.domain.events.WorkOrderManufacturingCompleted;
import com.northwood.sales.application.dto.PlaceOrderCommand;
import com.northwood.sales.application.dto.PlaceOrderCommand.OrderLine;
import com.northwood.sales.domain.Customer;
import com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga;
import com.northwood.shared.application.outbox.OutboxRow;
import com.northwood.shared.domain.Currencies;
import com.northwood.testharness.inmemory.SynchronousBus;
import com.northwood.testharness.inmemory.manufacturing.InMemoryBomLookup;
import com.northwood.testharness.kits.InventoryTestKit;
import com.northwood.testharness.kits.ManufacturingTestKit;
import com.northwood.testharness.kits.SalesTestKit;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end proof of the §2.43 <b>make-to-order</b> path: a sales order for a
 * {@code to_order} manufactured SKU raises dedicated order-pegged supply rather
 * than drawing from / building to the shared pool, and on work-order completion
 * the output is reserved (pegged) to the originating SO line atomically with the
 * stock credit — so the saga ships straight off the peg, with no re-reservation
 * retry, and the pegged stock never enters free ATP.
 *
 * <ol>
 *   <li>Reservation skips free stock entirely (the line is pegged). Inventory
 *       raises a {@code ReplenishmentRequest(order_pegged, target=manufacturing)}
 *       for the FULL line qty and emits {@code StockReserved [failed]}; the saga
 *       parks at {@code stock_reservation_incomplete}.</li>
 *   <li>Manufacturing's {@code ReplenishmentRequestedHandler} releases the WO;
 *       inventory flips the request to {@code dispatched}.</li>
 *   <li>WO completion (injected) → inventory credits FG on_hand AND reserves the
 *       same qty for the SO line (atomic peg), then {@code markFulfilled} emits
 *       {@code ReplenishmentFulfilled(pegged=true)}.</li>
 *   <li>Sales' fan-in sees {@code pegged} → saga {@code stock_reservation_incomplete
 *       → ready_to_ship} directly, WITHOUT re-emitting StockReservationRequested.
 *       ATP excludes the pegged stock (available = 0).</li>
 * </ol>
 */
class OrderToCashMakeToOrderPathTest {

    @Test
    void to_order_manufactured_sku_pegs_on_completion_and_ships_without_retry() {
        ObjectMapper json = new ObjectMapper();
        SynchronousBus bus = new SynchronousBus();

        SalesTestKit sales = new SalesTestKit(bus, json);
        InventoryTestKit inventory = new InventoryTestKit(bus, json);
        ManufacturingTestKit manufacturing = new ManufacturingTestKit(bus, json);

        // Seed: customer + a manufactured, order-pegged FG (1-raw-component BOM +
        // single-op routing). Raw material seeded so the WO's raw reservation is
        // clean and raises no extra replenishment.
        sales.customers.put("CUST-001", "Acme Corp", Customer.Status.ACTIVE);
        UUID fgProductId = UUID.randomUUID();
        UUID rawProductId = UUID.randomUUID();
        UUID bomHeaderId = UUID.randomUUID();

        sales.productCards.put(fgProductId, new BigDecimal("250.00"), Currencies.AUD);
        sales.lineSnapshots.markOrderPegged(fgProductId);   // §2.43 to_order

        inventory.productReplenishment.put(fgProductId, /*purchased=*/false, /*manufactured=*/true);
        manufacturing.replenishment.put(fgProductId, /*purchased=*/false, /*manufactured=*/true);
        manufacturing.bomLookup.put(fgProductId, bomHeaderId,
            InMemoryBomLookup.rawLine(rawProductId, "RM-001", "Raw Material 1",
                new BigDecimal("1"), BigDecimal.ZERO));
        manufacturing.bomLookup.putIdentity(fgProductId, "FG-MTO-001", "Made-to-order FG");
        manufacturing.routings.putSingleOp(fgProductId);
        inventory.seedStock(rawProductId, new BigDecimal("100"));   // raws on hand; NO FG stock

        // Step 1: place a SO for 3 units of the to_order FG.
        UUID orderId = sales.placeOrder(new PlaceOrderCommand(
            "SO-0001", "CUST-001",
            LocalDate.of(2026, 6, 1),
            Currencies.AUD, null,
            List.of(new OrderLine(
                fgProductId, "FG-MTO-001", "Made-to-order FG",
                new BigDecimal("3"), null, BigDecimal.ZERO
            ))
        ));

        // Step 2: worker advances started → stock_reservation_requested.
        sales.advanceSagaWorker();

        // Step 3: drain — inventory skips free stock (pegged), raises an
        // order-pegged replenishment for the full qty, routes to manufacturing,
        // which releases the WO; inventory flips the request to dispatched.
        bus.drain();

        assertThat(sales.findSagaBySalesOrderId(orderId).orElseThrow().state())
            .as("pegged line never draws from the pool → parks awaiting dedicated supply")
            .isEqualTo(SalesOrderFulfilmentSaga.STOCK_RESERVATION_INCOMPLETE);

        ReplenishmentRequest rr = inventory.replenishmentRequests.all().stream()
            .filter(r -> r.productId().equals(fgProductId))
            .findFirst().orElseThrow();
        assertThat(rr.reason()).isEqualTo(ReplenishmentRequest.Reason.ORDER_PEGGED);
        assertThat(rr.targetService()).isEqualTo(ReplenishmentRequest.TargetService.MANUFACTURING);
        assertThat(rr.requestedQuantity()).isEqualByComparingTo(new BigDecimal("3"));   // FULL line qty
        assertThat(rr.sourceSalesOrderHeaderId()).isEqualTo(orderId);
        assertThat(rr.status()).isEqualTo(ReplenishmentRequest.Status.DISPATCHED);

        // Step 4: simulate the WO completing (production confirmation).
        WorkOrderCreated woCreated = (WorkOrderCreated) manufacturing.outbox.all().stream()
            .filter(o -> WorkOrderCreated.EVENT_TYPE.equals(o.getEventType()))
            .findFirst()
            .map(o -> {
                try { return json.readValue(o.getPayload(), WorkOrderCreated.class); }
                catch (Exception e) { throw new RuntimeException(e); }
            })
            .orElseThrow();
        UUID workOrderId = woCreated.aggregateId();

        UUID completionEventId = UUID.randomUUID();
        WorkOrderManufacturingCompleted completion = new WorkOrderManufacturingCompleted(
            completionEventId, workOrderId, woCreated.workOrderNumber(),
            null, null, null,
            fgProductId, "FG-MTO-001", new BigDecimal("3"), Instant.now()
        );
        manufacturing.outbox.appendPending(OutboxRow.pending(
            completionEventId, "WorkOrder", workOrderId,
            WorkOrderManufacturingCompleted.EVENT_TYPE, 1,
            json.writeValueAsString(completion),
            null, null, null, null
        ));

        // Step 5: drain — inventory credits FG + atomically pegs (reserves) it for
        // the SO line, emits ReplenishmentFulfilled(pegged=true); sales ships off
        // the peg WITHOUT a re-reservation retry.
        bus.drain();

        assertThat(sales.findSagaBySalesOrderId(orderId).orElseThrow().state())
            .as("order-pegged completion → ready_to_ship straight off the peg")
            .isEqualTo(SalesOrderFulfilmentSaga.READY_TO_SHIP);

        // ATP excludes the pegged stock: 3 on hand, 3 reserved, 0 available.
        var fgBalance = inventory.stockBalances
            .findBalance(InventoryTestKit.DEFAULT_WAREHOUSE_ID, fgProductId).orElseThrow();
        assertThat(fgBalance.onHand()).isEqualByComparingTo(new BigDecimal("3"));
        assertThat(fgBalance.reserved()).isEqualByComparingTo(new BigDecimal("3"));
        assertThat(fgBalance.available()).isEqualByComparingTo(BigDecimal.ZERO);

        // ReplenishmentFulfilled carried pegged=true.
        ReplenishmentFulfilled fulfilled = (ReplenishmentFulfilled) inventory.outbox.all().stream()
            .filter(o -> ReplenishmentFulfilled.EVENT_TYPE.equals(o.getEventType()))
            .findFirst()
            .map(o -> {
                try { return json.readValue(o.getPayload(), ReplenishmentFulfilled.class); }
                catch (Exception e) { throw new RuntimeException(e); }
            })
            .orElseThrow();
        assertThat(fulfilled.pegged()).isTrue();
        assertThat(fulfilled.sourceSalesOrderHeaderId()).isEqualTo(orderId);

        // No re-reservation retry: sales emitted exactly ONE StockReservationRequested.
        long reservationRequests = sales.outbox.all().stream()
            .filter(o -> "sales.StockReservationRequested".equals(o.getEventType()))
            .count();
        assertThat(reservationRequests)
            .as("pegged completion ships off the peg — no second reservation attempt")
            .isEqualTo(1);
    }
}
