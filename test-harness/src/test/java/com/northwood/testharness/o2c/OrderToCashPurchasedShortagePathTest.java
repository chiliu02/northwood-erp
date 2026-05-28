package com.northwood.testharness.o2c;

import static org.assertj.core.api.Assertions.assertThat;

import com.northwood.inventory.domain.ReplenishmentRequest;
import com.northwood.inventory.domain.events.ReplenishmentFulfilled;
import com.northwood.inventory.domain.events.ReplenishmentRequested;
import com.northwood.sales.application.dto.PlaceOrderCommand;
import com.northwood.sales.application.dto.PlaceOrderCommand.OrderLine;
import com.northwood.sales.domain.Customer;
import com.northwood.sales.domain.SalesOrder;
import com.northwood.sales.domain.events.SalesOrderPurchasingRequested;
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
 * §2.36 Slice F — end-to-end proof that a sales order short on a purchased-
 * only SKU is no longer dead-end, and instead re-enters the reservation
 * cycle once the replenishment lands.
 *
 * <p>Exercises Slices C → E together:
 * <ol>
 *   <li><b>Slice C</b>: ManufacturingDispatchedHandler reroutes on
 *       all-rejected-not-manufactured → emits
 *       {@code sales.SalesOrderPurchasingRequested}, saga transitions
 *       {@code manufacturing_requested → purchasing_requested}.</li>
 *   <li><b>Slice D</b>: inventory's SalesOrderPurchasingRequestedHandler
 *       raises one {@code ReplenishmentRequest} per short line, back-
 *       referenced to the SO line.</li>
 *   <li><b>Slice E</b>: simulated PR-dispatch + goods-receipt drives
 *       {@code markDispatched} + {@code markFulfilled} → emits
 *       {@code inventory.ReplenishmentFulfilled} with both back-references.
 *       Sales' ReplenishmentFulfilledHandler fires, transitions
 *       {@code purchasing_requested → stock_reservation_requested}, re-emits
 *       {@code StockReservationRequested}. Inventory re-attempts (cancels
 *       prior partial, claims now-restocked balance) and emits
 *       {@code StockReserved [reserved]}, advancing the saga to
 *       {@code ready_to_ship}.</li>
 * </ol>
 *
 * <p>The §2.35 PR → PO → goods-receipt machinery is exercised by
 * {@code StockReplenishmentPurchasedPathTest}; this test shortcuts it by
 * directly calling {@code markDispatched} + {@code markFulfilled} on the
 * replenishment-request aggregate to keep the §2.36 wiring under test.
 */
class OrderToCashPurchasedShortagePathTest {

    @Test
    void purchased_only_shortage_reroutes_through_inventory_replenishment_then_reserves_and_ships() {
        ObjectMapper json = new ObjectMapper();
        SynchronousBus bus = new SynchronousBus();

        SalesTestKit sales = new SalesTestKit(bus, json);
        InventoryTestKit inventory = new InventoryTestKit(bus, json);
        ManufacturingTestKit manufacturing = new ManufacturingTestKit(bus, json);

        // Seed: customer + purchased-only product + ZERO initial stock.
        sales.customers.put("CUST-001", "Acme Corp", Customer.Status.ACTIVE);
        UUID productId = UUID.randomUUID();
        sales.productCards.put(productId, new BigDecimal("12.50"), Currencies.AUD);
        // Manufacturing's snapshot: purchased-only (will classify as
        // rejected_not_manufactured).
        manufacturing.replenishment.put(productId, /*purchased=*/true, /*manufactured=*/false);
        // Inventory's snapshot: same — so the SO-shortage replenishment routes
        // to PURCHASING.
        inventory.productReplenishment.put(productId, /*purchased=*/true, /*manufactured=*/false);
        // No stock at start — first reservation attempt will fail.

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
        // Step 3: drain bus — inventory partial-reserves (0 reserved, 5 short),
        // emits StockReserved [failed]; sales saga lands at stock_reservation_incomplete.
        bus.drain();
        assertThat(sales.findSagaBySalesOrderId(orderId).orElseThrow().state())
            .isEqualTo(SalesOrderFulfilmentSaga.STOCK_RESERVATION_INCOMPLETE);

        // Step 4: advance worker again — emits ManufacturingRequested for the
        // 5 short units; saga → manufacturing_requested.
        sales.advanceSagaWorker();
        assertThat(sales.findSagaBySalesOrderId(orderId).orElseThrow().state())
            .isEqualTo(SalesOrderFulfilmentSaga.MANUFACTURING_REQUESTED);

        // Step 5: drain bus — manufacturing rejects the line as
        // rejected_not_manufactured (is_manufactured=false), emits
        // ManufacturingDispatched [zero accepted]. Sales' handler fires the
        // §2.36 reroute path: saga → purchasing_requested, emits
        // SalesOrderPurchasingRequested. Inventory's handler raises a
        // ReplenishmentRequest with reason=sales_order_shortage and stamps
        // both back-references.
        bus.drain();

        SalesOrderFulfilmentSaga afterReroute =
            sales.findSagaBySalesOrderId(orderId).orElseThrow();
        assertThat(afterReroute.state())
            .as("§2.36 reroute lands at purchasing_requested instead of terminal rejected")
            .isEqualTo(SalesOrderFulfilmentSaga.PURCHASING_REQUESTED);
        assertThat(sales.outbox.all())
            .extracting(OutboxRow::getEventType)
            .contains(SalesOrderPurchasingRequested.EVENT_TYPE);
        assertThat(inventory.outbox.all())
            .extracting(OutboxRow::getEventType)
            .contains(ReplenishmentRequested.EVENT_TYPE);

        // Find the raised RR. Confirm back-references stamped.
        List<ReplenishmentRequest> rrs = inventory.replenishmentRequests.all();
        assertThat(rrs).hasSize(1);
        ReplenishmentRequest rr = rrs.get(0);
        assertThat(rr.reason()).isEqualTo(ReplenishmentRequest.Reason.SALES_ORDER_SHORTAGE);
        assertThat(rr.targetService()).isEqualTo(ReplenishmentRequest.TargetService.PURCHASING);
        assertThat(rr.sourceSalesOrderHeaderId()).isEqualTo(orderId);
        assertThat(rr.sourceSalesOrderLineId()).isNotNull();
        assertThat(rr.requestedQuantity()).isEqualByComparingTo(new BigDecimal("5"));
        assertThat(rr.status()).isEqualTo(ReplenishmentRequest.Status.REQUESTED);

        // Step 6: simulate PR-dispatch + goods-receipt by directly driving the
        // aggregate's state machine. (The full §2.35 PR→PO→GR path is covered
        // by StockReplenishmentPurchasedPathTest; this test focuses on the
        // §2.36 fan-in.)
        UUID fakePurchaseRequisitionId = UUID.randomUUID();
        rr.markDispatched(ReplenishmentRequest.DispatchedAggregateKind.PURCHASE_REQUISITION,
            fakePurchaseRequisitionId);
        inventory.replenishmentRequests.save(rr);
        // Bump stock as if a PO + goods receipt had landed.
        inventory.seedStock(productId, new BigDecimal("5"));
        // Now fulfil the request — emits ReplenishmentFulfilled with the back-
        // references on the payload.
        rr.markFulfilled();
        inventory.replenishmentRequests.save(rr);

        // Step 7: drain the bus. ReplenishmentFulfilled propagates to sales:
        //   - sales.fulfilment-saga.replenishment-fulfilled removes the line
        //     from outstandingPurchasingLineIds → set empties → saga →
        //     stock_reservation_requested + re-emits StockReservationRequested.
        //   - inventory re-reserves: cancelPriorSalesOrderReservation drops
        //     the partial, the new reservation claims 5 from the bumped stock.
        //   - StockReserved [reserved] flows to sales → saga → ready_to_ship.
        bus.drain();

        SalesOrderFulfilmentSaga afterFulfilment =
            sales.findSagaBySalesOrderId(orderId).orElseThrow();
        assertThat(afterFulfilment.state())
            .as("post-fulfilment saga should be ready_to_ship — order survived")
            .isEqualTo(SalesOrderFulfilmentSaga.READY_TO_SHIP);
        assertThat(sales.orderStatus(orderId))
            .as("order header projected to in_fulfilment after retry")
            .contains(SalesOrder.Status.IN_FULFILMENT);
        assertThat(inventory.outbox.all())
            .extracting(OutboxRow::getEventType)
            .contains(ReplenishmentFulfilled.EVENT_TYPE);

        // No pending outbox rows remain anywhere — full close-the-loop.
        assertThat(sales.outbox.findPending(100)).isEmpty();
        assertThat(inventory.outbox.findPending(100)).isEmpty();
        assertThat(manufacturing.outbox.findPending(100)).isEmpty();
    }
}
