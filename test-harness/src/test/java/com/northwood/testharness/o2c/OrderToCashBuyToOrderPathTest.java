package com.northwood.testharness.o2c;

import static org.assertj.core.api.Assertions.assertThat;

import com.northwood.inventory.application.dto.GoodsReceiptLineRequest;
import com.northwood.inventory.application.dto.PostGoodsReceiptCommand;
import com.northwood.inventory.domain.ReplenishmentRequest;
import com.northwood.inventory.domain.WarehouseCodes;
import com.northwood.inventory.domain.events.ReplenishmentFulfilled;
import com.northwood.purchasing.domain.events.PurchaseOrderCreated;
import com.northwood.sales.application.dto.PlaceOrderCommand;
import com.northwood.sales.application.dto.PlaceOrderCommand.OrderLine;
import com.northwood.sales.domain.Customer;
import com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga;
import com.northwood.shared.domain.Currencies;
import com.northwood.testharness.inmemory.SynchronousBus;
import com.northwood.testharness.kits.InventoryTestKit;
import com.northwood.testharness.kits.PurchasingTestKit;
import com.northwood.testharness.kits.SalesTestKit;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end proof of the <b>buy-to-order</b> path — the symmetric
 * purchasing mirror of {@link OrderToCashMakeToOrderPathTest}. A sales order for
 * a {@code to_order} <em>purchased</em> SKU raises a dedicated, order-pegged
 * <b>purchase order</b>; on goods receipt the bought stock is reserved (pegged)
 * to the originating SO line atomically with the on-hand credit — so the saga
 * ships straight off the peg, no re-reservation retry, and the pegged stock
 * never enters free ATP.
 *
 * <ol>
 *   <li>The reserve step skips free stock (line is pegged). Inventory raises a
 *       {@code ReplenishmentRequest(order_pegged, target=purchasing)} for the
 *       FULL line qty; saga parks at {@code stock_reservation_incomplete}.</li>
 *   <li>Purchasing creates a PR → auto-converts to a PO; inventory flips the
 *       request to {@code dispatched} and stamps {@code linked_purchase_order_id}.</li>
 *   <li>The <b>real</b> {@code GoodsReceiptService.post} runs: it credits FG
 *       on-hand AND (because the linked request is {@code order_pegged})
 *       reserves the received qty for the SO line in the same transaction, then
 *       {@code markFulfilled} emits {@code ReplenishmentFulfilled(pegged=true)}.</li>
 *   <li>Sales' fan-in sees {@code pegged} → saga → {@code SUPPLY_SECURED}
 *       directly. ATP excludes the peg (available = 0).</li>
 * </ol>
 *
 * <p>Unlike the existing purchased-path replenishment tests (which shortcut the
 * receipt via {@code markFulfilled}), this drives the real {@code GoodsReceiptService}
 * so the buy-side peg-on-receipt is exercised end-to-end.
 */
class OrderToCashBuyToOrderPathTest {

    @Test
    void to_order_purchased_sku_pegs_on_goods_receipt_and_ships_without_retry() {
        ObjectMapper json = new ObjectMapper();
        SynchronousBus bus = new SynchronousBus();

        SalesTestKit sales = new SalesTestKit(bus, json);
        InventoryTestKit inventory = new InventoryTestKit(bus, json);
        PurchasingTestKit purchasing = new PurchasingTestKit(bus, json);

        // Seed: customer + a purchased, order-pegged FG (the "custom carpet").
        sales.customers.put("CUST-001", "Acme Corp", Customer.Status.ACTIVE);
        UUID carpetId = UUID.randomUUID();
        sales.productCards.put(carpetId, new BigDecimal("1200.00"), Currencies.AUD);
        sales.lineSnapshots.markOrderPegged(carpetId);   // to_order

        inventory.productReplenishment.put(carpetId, /*purchased=*/true, /*manufactured=*/false);
        // Purchasing must be able to source it (default supplier price list).
        UUID supplierId = purchasing.suppliers.findByCode("SUP-001").orElseThrow().id().value();
        purchasing.priceLookup.put(supplierId, carpetId, Currencies.AUD, new BigDecimal("700.00"));
        // NO FG stock — built/bought per order.

        // Step 1: place a SO for 1 carpet.
        UUID orderId = sales.placeOrder(new PlaceOrderCommand(
            "SO-0001", "CUST-001",
            LocalDate.of(2026, 6, 1),
            Currencies.AUD, null,
            List.of(new OrderLine(
                carpetId, "FG-CARPET-001", "Custom-design Carpet",
                new BigDecimal("1"), null, BigDecimal.ZERO
            ))
        ));

        // Step 2: worker advances started → stock_reservation_requested.
        sales.advanceSagaWorker();

        // Step 3: drain — inventory skips free stock (pegged), raises an
        // order-pegged replenishment routed to purchasing, which creates a PR +
        // auto-converts to a PO; inventory flips the request to dispatched and
        // links the PO.
        bus.drain();

        assertThat(sales.findSagaBySalesOrderId(orderId).orElseThrow().state())
            .isEqualTo(SalesOrderFulfilmentSaga.STOCK_RESERVATION_INCOMPLETE);

        ReplenishmentRequest rr = inventory.replenishmentRequests.all().stream()
            .filter(r -> r.productId().equals(carpetId))
            .findFirst().orElseThrow();
        assertThat(rr.reason()).isEqualTo(ReplenishmentRequest.Reason.ORDER_PEGGED);
        assertThat(rr.targetService()).isEqualTo(ReplenishmentRequest.TargetService.PURCHASING);
        assertThat(rr.requestedQuantity()).isEqualByComparingTo(new BigDecimal("1"));   // FULL line qty
        assertThat(rr.sourceSalesOrderHeaderId()).isEqualTo(orderId);
        assertThat(rr.status()).isEqualTo(ReplenishmentRequest.Status.DISPATCHED);
        assertThat(rr.linkedPurchaseOrderId()).isNotNull();

        UUID poId = (UUID) purchasing.outbox.all().stream()
            .filter(o -> PurchaseOrderCreated.EVENT_TYPE.equals(o.getEventType()))
            .findFirst()
            .map(o -> {
                try { return json.readValue(o.getPayload(), PurchaseOrderCreated.class).aggregateId(); }
                catch (Exception e) { throw new RuntimeException(e); }
            })
            .orElseThrow();
        assertThat(rr.linkedPurchaseOrderId()).isEqualTo(poId);

        // Step 4: post the goods receipt against the PO via the REAL service.
        // GoodsReceiptService credits on-hand AND peg-reserves for the SO line
        // (order_pegged), then markFulfilled emits ReplenishmentFulfilled(pegged).
        inventory.goodsReceiptService.post(new PostGoodsReceiptCommand(
            "GR-0001", poId, "PO-CARPET-001", supplierId, "Floor Coverings Direct", WarehouseCodes.MAIN,
            List.of(new GoodsReceiptLineRequest(
                /*purchaseOrderLineId*/ null, carpetId, "FG-CARPET-001", "Custom-design Carpet",
                new BigDecimal("1"), new BigDecimal("700.00")
            ))
        ));

        // Step 5: drain — ReplenishmentFulfilled(pegged) reaches sales; the saga
        // ships off the peg without a re-reservation retry.
        bus.drain();

        assertThat(sales.findSagaBySalesOrderId(orderId).orElseThrow().state())
            .as("order-pegged goods receipt → SUPPLY_SECURED straight off the peg")
            .isEqualTo(SalesOrderFulfilmentSaga.SUPPLY_SECURED);

        // ATP excludes the pegged stock: 1 on hand, 1 reserved, 0 available.
        var balance = inventory.stockBalances
            .findBalance(InventoryTestKit.DEFAULT_WAREHOUSE_ID, carpetId).orElseThrow();
        assertThat(balance.onHand()).isEqualByComparingTo(new BigDecimal("1"));
        assertThat(balance.reserved()).isEqualByComparingTo(new BigDecimal("1"));
        assertThat(balance.available()).isEqualByComparingTo(BigDecimal.ZERO);

        assertThat(inventory.replenishmentRequests.findById(rr.id()).orElseThrow().status())
            .isEqualTo(ReplenishmentRequest.Status.FULFILLED);

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
            .as("pegged goods receipt ships off the peg — no second reservation attempt")
            .isEqualTo(1);
    }
}
