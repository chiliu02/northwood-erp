package com.northwood.testharness.o2c;

import com.northwood.inventory.domain.WarehouseCodes;
import com.northwood.inventory.domain.events.ShipmentPosted;
import com.northwood.inventory.domain.events.StockReserved;
import com.northwood.sales.domain.events.SalesOrderPlaced;
import com.northwood.sales.domain.events.SalesOrderReadyToShip;
import com.northwood.sales.domain.events.SalesOrderShipped;
import com.northwood.sales.domain.events.StockReservationRequested;

import static org.assertj.core.api.Assertions.assertThat;

import com.northwood.finance.domain.Payment;
import com.northwood.finance.domain.events.CustomerInvoiceCreated;
import com.northwood.finance.domain.events.CustomerPaymentReceived;
import com.northwood.inventory.application.dto.PostShipmentCommand;
import com.northwood.inventory.application.dto.ShipmentLineRequest;
import com.northwood.sales.application.dto.PlaceOrderCommand;
import com.northwood.sales.application.dto.PlaceOrderCommand.OrderLine;
import com.northwood.sales.domain.Customer;
import com.northwood.sales.domain.PaymentTerms;
import com.northwood.sales.domain.SalesOrder;
import com.northwood.sales.domain.SalesOrderId;
import com.northwood.sales.domain.SalesOrderLine;
import com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga;
import com.northwood.shared.application.outbox.OutboxRow;
import com.northwood.shared.domain.Currencies;
import com.northwood.testharness.inmemory.SynchronousBus;
import com.northwood.testharness.kits.FinanceTestKit;
import com.northwood.testharness.kits.InventoryTestKit;
import com.northwood.testharness.kits.SalesTestKit;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * §2.33 — order-to-cash for a cash-on-delivery (COD) order.
 *
 * <p>COD settles at the goods-delivered moment: the saga walks
 * {@code placeOrder -> ready_to_ship -> goods_shipped -> invoice_created ->
 * completed} entirely at shipment ({@code applyShipmentPosted}'s COD branch),
 * and finance auto-creates the invoice <i>and</i> auto-records the full
 * customer payment from the single {@code SalesOrderShipped} event — no
 * operator-recorded payment, unlike {@link OrderToCashHappyPathTest}.
 *
 * <p>End-state GL ties out the same as the on-shipment terminal (Dr Cash /
 * Cr Revenue, with AR netting to zero); only the trigger differs. Asserts the
 * order reaches header status {@code completed} (not stranded at
 * {@code shipped}) and that exactly one COD-method payment was recorded.
 */
class OrderToCashCodPathTest {

    @Test
    void cod_order_completes_and_auto_settles_at_shipment() {
        ObjectMapper json = new ObjectMapper();
        SynchronousBus bus = new SynchronousBus();

        SalesTestKit sales = new SalesTestKit(bus, json);
        InventoryTestKit inventory = new InventoryTestKit(bus, json);
        FinanceTestKit finance = new FinanceTestKit(bus, json);

        sales.customers.put("CUST-001", "Acme Corp", Customer.Status.ACTIVE);
        UUID productId = UUID.randomUUID();
        sales.productCards.put(productId, new BigDecimal("100.00"), Currencies.AUD);
        inventory.seedStock(productId, new BigDecimal("50"));

        // Place a COD order (per-order paymentTerms override).
        UUID orderId = sales.placeOrder(new PlaceOrderCommand(
            "SO-COD-1", "CUST-001",
            LocalDate.of(2026, 5, 20),
            Currencies.AUD, PaymentTerms.CASH_ON_DELIVERY.dbValue(),
            List.of(new OrderLine(productId, "FG-001", "Finished Good 1",
                new BigDecimal("3"), null, BigDecimal.ZERO))
        ));

        sales.advanceSagaWorker();
        bus.drain();

        SalesOrderFulfilmentSaga sagaAtReadyToShip = sales.findSagaBySalesOrderId(orderId).orElseThrow();
        assertThat(sagaAtReadyToShip.state()).isEqualTo(SalesOrderFulfilmentSaga.READY_TO_SHIP);

        // Post the shipment. The COD branch of applyShipmentPosted walks the
        // saga straight to completed; finance auto-invoices + auto-settles.
        UUID customerId = sales.customers.findByCode("CUST-001").orElseThrow().customerId();
        SalesOrderLine placedLine = sales.orders.findById(SalesOrderId.of(orderId)).orElseThrow().lines().get(0);
        inventory.shipmentService.post(new PostShipmentCommand(
            "SHIP-COD-1",
            orderId,
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

        // Saga + header both terminal at completed (no operator payment step).
        SalesOrderFulfilmentSaga sagaAtCompleted = sales.findSagaBySalesOrderId(orderId).orElseThrow();
        assertThat(sagaAtCompleted.state()).isEqualTo(SalesOrderFulfilmentSaga.COMPLETED);
        assertThat(sales.orderStatus(orderId)).contains(SalesOrder.Status.COMPLETED);

        // Finance auto-created exactly one invoice and one COD-method payment.
        assertThat(finance.customerInvoices.findAll()).hasSize(1);
        assertThat(finance.payments.findAll()).hasSize(1);
        assertThat(finance.payments.findAll().get(0).paymentMethod()).isEqualTo(Payment.Method.CASH);

        // Cross-service event audit. SalesOrderShipped now carries paymentTerms;
        // finance emits both the invoice + the auto-recorded payment.
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
