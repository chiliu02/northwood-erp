package com.northwood.testharness.o2c;

import static org.assertj.core.api.Assertions.assertThat;

import com.northwood.inventory.domain.ReplenishmentRequest;
import com.northwood.inventory.domain.events.ReplenishmentFulfilled;
import com.northwood.inventory.domain.events.ReplenishmentRequested;
import com.northwood.sales.application.dto.PlaceOrderCommand;
import com.northwood.sales.application.dto.PlaceOrderCommand.OrderLine;
import com.northwood.sales.domain.Customer;
import com.northwood.sales.domain.SalesOrder;
import com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga;
import com.northwood.shared.application.outbox.OutboxRow;
import com.northwood.shared.domain.Currencies;
import com.northwood.testharness.inmemory.SynchronousBus;
import com.northwood.testharness.kits.InventoryTestKit;
import com.northwood.testharness.kits.ManufacturingTestKit;
import com.northwood.testharness.kits.SalesTestKit;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end proof that a sales order short on a
 * purchased-only SKU re-enters the reservation cycle once the replenishment
 * lands, with <b>inventory</b> the single make-vs-buy decision point — sales
 * never touches manufacturing.
 *
 * <ol>
 *   <li>Reservation comes back {@code failed} (zero stock). Inventory raises a
 *       {@code ReplenishmentRequest(sales_order_shortage, target=purchasing)} in
 *       the SAME transaction (it owns the make-vs-buy classification) and emits
 *       {@code StockReserved [failed]}.</li>
 *   <li>Sales' StockReservedHandler parks the saga at
 *       {@code stock_reservation_incomplete} with the short sales-order-line on
 *       {@code outstandingReplenishmentLineIds}. No manufacturing leg, no
 *       {@code ManufacturingRequested} / {@code ManufacturingDispatched}.</li>
 *   <li>Simulated PR-dispatch + goods-receipt drives {@code markDispatched} +
 *       {@code markFulfilled} → {@code inventory.ReplenishmentFulfilled} with
 *       both back-references. Sales' ReplenishmentFulfilledHandler empties the
 *       outstanding set → saga {@code stock_reservation_incomplete →
 *       stock_reservation_requested}, re-emits {@code StockReservationRequested}.
 *       Inventory re-attempts (cancels prior partial, claims the now-restocked
 *       balance) and emits {@code StockReserved [reserved]}, advancing the saga
 *       to {@code ready_to_ship}.</li>
 * </ol>
 *
 * <p>The PR → PO → goods-receipt machinery is exercised by
 * {@code StockReplenishmentPurchasedPathTest}; this test shortcuts it by
 * directly calling {@code markDispatched} + {@code markFulfilled} on the
 * replenishment-request aggregate to keep the fan-in under test.
 */
class OrderToCashPurchasedShortagePathTest {

    @Test
    void purchased_only_shortage_replenishes_in_tx_then_reserves_and_ships() {
        ObjectMapper json = new ObjectMapper();
        SynchronousBus bus = new SynchronousBus();

        SalesTestKit sales = new SalesTestKit(bus, json);
        InventoryTestKit inventory = new InventoryTestKit(bus, json);
        // Manufacturing is wired but must stay entirely uninvolved for a
        // purchased-only shortage — asserted via its empty outbox at the end.
        ManufacturingTestKit manufacturing = new ManufacturingTestKit(bus, json);

        // Seed: customer + purchased-only product on inventory's card + ZERO stock.
        sales.customers.put("CUST-001", "Acme Corp", Customer.Status.ACTIVE);
        UUID productId = UUID.randomUUID();
        sales.productCards.put(productId, new BigDecimal("12.50"), Currencies.AUD);
        // Inventory's make-vs-buy snapshot: purchased-only → SO-shortage
        // replenishment routes to PURCHASING.
        inventory.productReplenishment.put(productId, /*purchased=*/true, /*manufactured=*/false);
        // No stock at start — first reservation attempt fails.

        // Step 1: place a SO for 5 widgets.
        UUID orderId = sales.placeOrder(new PlaceOrderCommand(
            "SO-0001", "CUST-001",
            LocalDate.of(2026, 6, 1),
            Currencies.AUD, null,
            List.of(new OrderLine(
                productId, "WIDGET-001", "Widget",
                new BigDecimal("5"), null, BigDecimal.ZERO
            ))
        ));

        // Step 2: drive saga worker (started → stock_reservation_requested).
        sales.advanceSagaWorker();

        // Step 3: drain bus — inventory reserves (0 of 5), in the same tx raises
        // a ReplenishmentRequest(sales_order_shortage, purchasing) and emits
        // StockReserved [failed]; sales saga parks at stock_reservation_incomplete.
        bus.drain();
        assertThat(sales.findSagaBySalesOrderId(orderId).orElseThrow().state())
            .as("partial/failed reservation parks awaiting replenishment — no manufacturing leg")
            .isEqualTo(SalesOrderFulfilmentSaga.STOCK_RESERVATION_INCOMPLETE);
        assertThat(inventory.outbox.all())
            .extracting(OutboxRow::getEventType)
            .contains(ReplenishmentRequested.EVENT_TYPE);

        // The in-tx-raised RR carries the SO back-references and routes to purchasing.
        List<ReplenishmentRequest> rrs = inventory.replenishmentRequests.all();
        assertThat(rrs).hasSize(1);
        ReplenishmentRequest rr = rrs.get(0);
        assertThat(rr.reason()).isEqualTo(ReplenishmentRequest.Reason.SALES_ORDER_SHORTAGE);
        assertThat(rr.targetService()).isEqualTo(ReplenishmentRequest.TargetService.PURCHASING);
        assertThat(rr.sourceSalesOrderHeaderId()).isEqualTo(orderId);
        assertThat(rr.sourceSalesOrderLineId()).isNotNull();
        assertThat(rr.requestedQuantity()).isEqualByComparingTo(new BigDecimal("5"));
        assertThat(rr.status()).isEqualTo(ReplenishmentRequest.Status.REQUESTED);

        // Step 4: simulate PR-dispatch + goods-receipt by directly driving the
        // aggregate. (The full PR→PO→GR path is covered by
        // StockReplenishmentPurchasedPathTest; this test focuses on the fan-in.)
        UUID fakePurchaseRequisitionId = UUID.randomUUID();
        rr.markDispatched(ReplenishmentRequest.DispatchedAggregateKind.PURCHASE_REQUISITION,
            fakePurchaseRequisitionId);
        inventory.replenishmentRequests.save(rr);
        // Bump stock as if a PO + goods receipt had landed.
        inventory.seedStock(productId, new BigDecimal("5"));
        // Fulfil the request — emits ReplenishmentFulfilled with the back-references.
        rr.markFulfilled();
        inventory.replenishmentRequests.save(rr);

        // Step 5: drain the bus. ReplenishmentFulfilled propagates to sales:
        //   - the fan-in handler empties outstandingReplenishmentLineIds → saga
        //     → stock_reservation_requested + re-emits StockReservationRequested.
        //   - inventory re-reserves: cancelPriorSalesOrderReservation drops the
        //     failed reservation, the new one claims 5 from the bumped stock.
        //   - StockReserved [reserved] flows to sales → saga → ready_to_ship.
        bus.drain();

        SalesOrderFulfilmentSaga afterFulfilment =
            sales.findSagaBySalesOrderId(orderId).orElseThrow();
        assertThat(afterFulfilment.state())
            .as("post-fulfilment saga should be ready_to_ship — order survived")
            .isEqualTo(SalesOrderFulfilmentSaga.READY_TO_SHIP);
        assertThat(sales.orderStatus(orderId))
            .as("order header folds to reserved after retry — line fully reserved")
            .contains(SalesOrder.Status.RESERVED);
        assertThat(inventory.outbox.all())
            .extracting(OutboxRow::getEventType)
            .contains(ReplenishmentFulfilled.EVENT_TYPE);

        // No pending outbox rows remain anywhere — full close-the-loop, and
        // manufacturing was never involved.
        assertThat(sales.outbox.findPending(100)).isEmpty();
        assertThat(inventory.outbox.findPending(100)).isEmpty();
        assertThat(manufacturing.outbox.findPending(100)).isEmpty();
    }
}
