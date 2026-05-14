package com.northwood.testharness.o2c;

import com.northwood.inventory.domain.events.StockReserved;
import com.northwood.sales.domain.events.SalesOrderPlaced;
import com.northwood.sales.domain.events.SalesOrderShipped;
import com.northwood.sales.domain.events.StockReservationRequested;

import static org.assertj.core.api.Assertions.assertThat;

import com.northwood.sales.application.dto.PlaceOrderCommand;
import com.northwood.sales.domain.Customer;
import com.northwood.sales.application.dto.PlaceOrderCommand.OrderLine;
import com.northwood.sales.domain.Customer;
import com.northwood.finance.domain.events.CustomerInvoiceCreated;
import com.northwood.finance.domain.events.CustomerPaymentReceived;
import com.northwood.inventory.domain.events.ShipmentPosted;
import com.northwood.sales.domain.SalesOrder;
import com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga;
import com.northwood.shared.application.outbox.OutboxRow;
import com.northwood.testharness.inmemory.SynchronousBus;
import com.northwood.testharness.kits.FinanceTestKit;
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
 * §2.5.1 Slice D — order-to-cash happy path through the saga state machine.
 *
 * <p>Walks {@code placeOrder → ready_to_ship (full reservation shortcut) →
 * goods_shipped → invoice_created → completed}. With enough on-hand stock
 * to cover the order, {@code applyStockReserved} shortcuts directly from
 * {@code stock_reservation_requested} to {@code ready_to_ship}, skipping
 * the manufacturing leg entirely (see Side rail 2 in
 * {@code docs/SalesOrderFulfilmentSaga.md}). The shipment-post and
 * customer-payment events are injected directly via the outbox bus rather
 * than driving them through the inventory shipment service / finance
 * payment service, because those service paths require extra in-memory
 * adapters (ShipmentRepository, GoodsReceiptRepository, Payment domain
 * handling) that aren't in scope for Slice D's "prove the saga progresses
 * through every happy-path state" delivery.
 *
 * <p>The full make-to-order leg (manufacturing requested → WO created →
 * raw materials reserved → operation completed → manufacturing completed)
 * exercises in Slice E (shortage recovery) where the manufacturing kit's
 * full saga driving is the focus.
 */
class OrderToCashHappyPathTest {

    @Test
    void place_order_to_completed_via_saga_state_progression() throws Exception {
        ObjectMapper json = new ObjectMapper();
        SynchronousBus bus = new SynchronousBus();

        SalesTestKit sales = new SalesTestKit(bus, json);
        InventoryTestKit inventory = new InventoryTestKit(bus, json);
        FinanceTestKit finance = new FinanceTestKit(bus, json);

        // Seed: customer + product + stock for direct-ship.
        sales.customers.put("CUST-001", "Acme Corp", Customer.Status.ACTIVE);
        UUID productId = UUID.randomUUID();
        sales.pricing.put(productId, new BigDecimal("100.00"), "AUD");
        inventory.seedStock(productId, new BigDecimal("50"));

        // Step 1: place the order. Saga starts.
        UUID orderId = sales.placeOrder(new PlaceOrderCommand(
            "SO-9001", "CUST-001",
            LocalDate.of(2026, 5, 20),
            "AUD",
            List.of(new OrderLine(productId, "FG-001", "Finished Good 1",
                new BigDecimal("3"), null, BigDecimal.ZERO))
        ));

        // Step 2: drive sales worker → stock_reservation_requested.
        sales.advanceSagaWorker();

        // Step 3: drain the bus — inventory fully reserves stock; sales' applyStockReserved
        // shortcuts directly to ready_to_ship (full-reservation branch), skipping
        // the manufacturing leg.
        bus.drain();

        SalesOrderFulfilmentSaga sagaAtReadyToShip = sales.findSagaBySalesOrderId(orderId).orElseThrow();
        assertThat(sagaAtReadyToShip.state()).isEqualTo(SalesOrderFulfilmentSaga.READY_TO_SHIP);
        assertThat(sales.orderStatus(orderId)).contains(SalesOrder.IN_FULFILMENT);

        // Step 4: inject inventory.ShipmentPosted so sales' handler advances
        // ready_to_ship → goods_shipped and emits SalesOrderShipped.
        UUID shipmentEventId = UUID.randomUUID();
        UUID shipmentHeaderId = UUID.randomUUID();
        UUID warehouseId = InventoryTestKit.DEFAULT_WAREHOUSE_ID;
        UUID customerId = sales.customers.findByCode("CUST-001").orElseThrow().customerId();
        ShipmentPosted shipmentPayload = new ShipmentPosted(
            shipmentEventId,
            shipmentHeaderId,
            "SHIP-001",
            orderId,
            customerId,
            "Acme Corp",
            warehouseId,
            "MAIN",
            List.of(new ShipmentPosted.ShippedLine(
                UUID.randomUUID(),
                sagaAtReadyToShip.salesOrderId() == null ? UUID.randomUUID() : sales.orders.findById(com.northwood.sales.domain.SalesOrderId.of(orderId)).orElseThrow().lines().get(0).lineId(),
                productId, "FG-001", "Finished Good 1",
                new BigDecimal("3"), new BigDecimal("60.00")
            )),
            Instant.now()
        );
        // Inject onto inventory's outbox so it drains to sales' ShipmentPostedHandler
        // and finance's ShipmentPostedCogsHandler (and SalesOrderShippedHandler via
        // the chained sales.SalesOrderShipped emission from the aggregate).
        inventory.outbox.appendPending(OutboxRow.pending(
            shipmentEventId, "Shipment", shipmentHeaderId,
            ShipmentPosted.EVENT_TYPE, 1,
            json.writeValueAsString(shipmentPayload),
            null, null, null, null
        ));

        // Step 5: drain. ShipmentPosted → sales advances to goods_shipped + emits
        // SalesOrderShipped via the aggregate; finance's SalesOrderShippedHandler
        // creates the customer invoice + emits CustomerInvoiceCreated; sales'
        // CustomerInvoiceCreatedHandler advances saga to invoice_created.
        bus.drain();

        SalesOrderFulfilmentSaga sagaAtInvoiced = sales.findSagaBySalesOrderId(orderId).orElseThrow();
        assertThat(sagaAtInvoiced.state()).isEqualTo(SalesOrderFulfilmentSaga.INVOICE_CREATED);

        assertThat(finance.customerInvoices.findAll()).hasSize(1);

        // Step 6: simulate customer payment by injecting finance.CustomerPaymentReceived.
        UUID paymentEventId = UUID.randomUUID();
        UUID invoiceHeaderId = finance.customerInvoices.findAll().get(0).id().value();
        CustomerPaymentReceived paymentPayload = new CustomerPaymentReceived(
            paymentEventId, UUID.randomUUID(), "PAY-001",
            invoiceHeaderId, orderId, customerId, "Acme Corp",
            "bank_transfer", "AUD",
            new BigDecimal("330.00"), new BigDecimal("330.00"),
            "paid",
            Instant.now()
        );
        finance.outbox.appendPending(OutboxRow.pending(
            paymentEventId, "Payment", paymentEventId,
            CustomerPaymentReceived.EVENT_TYPE, 1,
            json.writeValueAsString(paymentPayload),
            null, null, null, null
        ));

        bus.drain();

        // Step 7: assertions. Saga is at completed; status projection is at completed.
        SalesOrderFulfilmentSaga sagaAtCompleted = sales.findSagaBySalesOrderId(orderId).orElseThrow();
        assertThat(sagaAtCompleted.state()).isEqualTo(SalesOrderFulfilmentSaga.COMPLETED);
        assertThat(sales.orderStatus(orderId)).contains(SalesOrder.COMPLETED);

        // Cross-service event audit:
        assertThat(sales.outbox.all())
            .extracting(OutboxRow::getEventType)
            .contains(SalesOrderPlaced.EVENT_TYPE, StockReservationRequested.EVENT_TYPE, SalesOrderShipped.EVENT_TYPE);
        assertThat(inventory.outbox.all())
            .extracting(OutboxRow::getEventType)
            .contains(StockReserved.EVENT_TYPE, ShipmentPosted.EVENT_TYPE);
        assertThat(finance.outbox.all())
            .extracting(OutboxRow::getEventType)
            .contains(CustomerInvoiceCreated.EVENT_TYPE, CustomerPaymentReceived.EVENT_TYPE);
    }
}
