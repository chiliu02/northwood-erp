package com.northwood.testharness.o2c;

import com.northwood.inventory.domain.WarehouseCodes;
import com.northwood.inventory.domain.events.StockReserved;
import com.northwood.sales.domain.events.SalesOrderPlaced;
import com.northwood.sales.domain.events.SalesOrderReadyToShip;
import com.northwood.sales.domain.events.SalesOrderShipped;
import com.northwood.sales.domain.events.StockReservationRequested;

import static org.assertj.core.api.Assertions.assertThat;

import com.northwood.finance.application.dto.RecordCustomerPaymentCommand;
import com.northwood.finance.domain.Payment;
import com.northwood.finance.domain.events.CustomerInvoiceCreated;
import com.northwood.finance.domain.events.CustomerPaymentReceived;
import com.northwood.inventory.application.dto.PostShipmentCommand;
import com.northwood.inventory.application.dto.ShipmentLineRequest;
import com.northwood.inventory.domain.events.ShipmentPosted;
import com.northwood.sales.application.dto.PlaceOrderCommand;
import com.northwood.sales.application.dto.PlaceOrderCommand.OrderLine;
import com.northwood.sales.domain.Customer;
import com.northwood.sales.domain.SalesOrder;
import com.northwood.sales.domain.SalesOrderId;
import com.northwood.sales.domain.SalesOrderLine;
import com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga;
import com.northwood.shared.application.outbox.OutboxRow;
import com.northwood.testharness.inmemory.SynchronousBus;
import com.northwood.testharness.kits.FinanceTestKit;
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
 * Order-to-cash happy path through the saga state machine.
 *
 * <p>Walks {@code placeOrder → ready_to_ship (full reservation shortcut) →
 * goods_shipped → invoice_created → completed}. With enough on-hand stock
 * to cover the order, {@code applyStockReserved} shortcuts directly from
 * {@code stock_reservation_requested} to {@code ready_to_ship}, skipping
 * the manufacturing leg entirely.
 *
 * <p>Shipment + customer payment are driven through the real
 * {@code ShipmentService.post} and {@code PaymentService.recordCustomerPayment}
 * — no event injection. The full make-to-order leg (manufacturing requested →
 * WO created → raw materials reserved → operation completed → manufacturing
 * completed) is exercised in the shortage-recovery path where the
 * manufacturing kit's full saga driving is the focus.
 */
class OrderToCashHappyPathTest {

    @Test
    void place_order_to_completed_via_saga_state_progression() {
        ObjectMapper json = new ObjectMapper();
        SynchronousBus bus = new SynchronousBus();

        SalesTestKit sales = new SalesTestKit(bus, json);
        InventoryTestKit inventory = new InventoryTestKit(bus, json);
        FinanceTestKit finance = new FinanceTestKit(bus, json);

        // Seed: customer + product + stock for direct-ship.
        sales.customers.put("CUST-001", "Acme Corp", Customer.Status.ACTIVE);
        UUID productId = UUID.randomUUID();
        sales.productCards.put(productId, new BigDecimal("100.00"), Currencies.AUD);
        inventory.seedStock(productId, new BigDecimal("50"));

        // Step 1: place the order. Saga starts.
        UUID orderId = sales.placeOrder(new PlaceOrderCommand(
            "SO-9001", "CUST-001",
            LocalDate.of(2026, 5, 20),
            Currencies.AUD, null,
            List.of(new OrderLine(productId, "FG-001", "Finished Good 1",
                new BigDecimal("3"), null, BigDecimal.ZERO))
        ));

        // Step 2: drive sales worker → stock_reservation_requested.
        sales.advanceSagaWorker();

        // Step 3: drain the bus — inventory fully reserves stock; sales'
        // applyStockReserved shortcuts directly to ready_to_ship
        // (full-reservation branch), skipping the manufacturing leg.
        // SalesOrderPlaced also seeds inventory's sales_order_line_facts
        // projection so the upcoming shipment validation has something to
        // check against.
        bus.drain();

        SalesOrderFulfilmentSaga sagaAtReadyToShip = sales.findSagaBySalesOrderId(orderId).orElseThrow();
        assertThat(sagaAtReadyToShip.state()).isEqualTo(SalesOrderFulfilmentSaga.READY_TO_SHIP);
        assertThat(sales.orderStatus(orderId)).contains(SalesOrder.Status.IN_FULFILMENT);

        // Step 4: post the shipment through the real ShipmentService.
        // Inventory persists the Shipment aggregate, decrements on-hand,
        // releases the matching reserved qty, and drains ShipmentPosted to
        // its outbox. Sales' handler advances ready_to_ship → goods_shipped
        // and emits SalesOrderShipped; finance auto-creates the customer
        // invoice from the richer sales event.
        UUID customerId = sales.customers.findByCode("CUST-001").orElseThrow().customerId();
        SalesOrderLine placedLine = sales.orders.findById(SalesOrderId.of(orderId)).orElseThrow().lines().get(0);
        inventory.shipmentService.post(new PostShipmentCommand(
            "SHIP-001",
            orderId,
            "SO-HAPPY-1",
            customerId,
            "Acme Corp",
            WarehouseCodes.MAIN,
            List.of(new ShipmentLineRequest(
                placedLine.lineId(),
                productId,
                "FG-001", "Finished Good 1",
                new BigDecimal("3"),
                new BigDecimal("60.00")
            ))
        ));
        bus.drain();

        SalesOrderFulfilmentSaga sagaAtInvoiced = sales.findSagaBySalesOrderId(orderId).orElseThrow();
        assertThat(sagaAtInvoiced.state()).isEqualTo(SalesOrderFulfilmentSaga.INVOICE_CREATED);

        assertThat(finance.customerInvoices.findAll()).hasSize(1);
        UUID invoiceHeaderId = finance.customerInvoices.findAll().get(0).id().value();

        // Step 5: settle the customer invoice through the real PaymentService.
        // recordCustomerPayment computes invoiceStatusAfter from the snapshot's
        // paidAmount + payment amount, so no pre-stamp via recordAllocation
        // is needed. Invoice total is 3 × 100 = 300.00 AUD.
        finance.paymentService.recordCustomerPayment(new RecordCustomerPaymentCommand(
            "PAY-001",
            invoiceHeaderId,
            new BigDecimal("300.00"),
            Payment.Method.BANK_TRANSFER.dbValue(),
            LocalDate.of(2026, 5, 20)
        ));
        bus.drain();

        // Step 6: assertions. Saga is at completed; status projection
        // is at completed.
        SalesOrderFulfilmentSaga sagaAtCompleted = sales.findSagaBySalesOrderId(orderId).orElseThrow();
        assertThat(sagaAtCompleted.state()).isEqualTo(SalesOrderFulfilmentSaga.COMPLETED);
        assertThat(sales.orderStatus(orderId)).contains(SalesOrder.Status.COMPLETED);

        // Cross-service event audit:
        assertThat(sales.outbox.all())
            .extracting(OutboxRow::getEventType)
            .contains(SalesOrderPlaced.EVENT_TYPE, StockReservationRequested.EVENT_TYPE,
                SalesOrderReadyToShip.EVENT_TYPE, SalesOrderShipped.EVENT_TYPE);
        assertThat(inventory.outbox.all())
            .extracting(OutboxRow::getEventType)
            .contains(StockReserved.EVENT_TYPE, ShipmentPosted.EVENT_TYPE);
        assertThat(finance.outbox.all())
            .extracting(OutboxRow::getEventType)
            .contains(CustomerInvoiceCreated.EVENT_TYPE, CustomerPaymentReceived.EVENT_TYPE);
    }
}
