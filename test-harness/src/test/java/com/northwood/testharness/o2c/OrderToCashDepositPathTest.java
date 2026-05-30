package com.northwood.testharness.o2c;

import com.northwood.inventory.domain.WarehouseCodes;
import com.northwood.inventory.domain.events.ShipmentPosted;
import com.northwood.inventory.domain.events.StockReserved;
import com.northwood.sales.domain.events.DepositInvoiceRequested;
import com.northwood.sales.domain.events.SalesOrderPlaced;
import com.northwood.sales.domain.events.SalesOrderShipped;
import com.northwood.sales.domain.events.SalesOrderUpfrontPaymentSettled;

import static org.assertj.core.api.Assertions.assertThat;

import com.northwood.finance.application.dto.RecordCustomerPaymentCommand;
import com.northwood.finance.domain.CustomerInvoice;
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
 * §2.32 — order-to-cash for a deposit (part-payment) order. The richest AR
 * pattern: two invoices (deposit at placement, balance at shipment) and two
 * customer payments, with Customer Deposits (2110) carrying the deposit on the
 * balance sheet between them.
 *
 * <p>Walks the full lifecycle: place (50% deposit) → deposit invoice → deposit
 * payment (Cr 2110) → deposit_paid → reservation → ready_to_ship → ship
 * (recognise deposit Dr 2110/Cr Revenue + balance invoice Dr AR/Cr Revenue) →
 * balance payment → completed. End-state revenue = full order total; 2110 + AR
 * net to zero.
 *
 * <p>Order: 3 × $100 = $300 total, 50% deposit → $150 deposit + $150 balance.
 */
class OrderToCashDepositPathTest {

    @Test
    void deposit_order_completes_across_two_invoices_and_payments() {
        ObjectMapper json = new ObjectMapper();
        SynchronousBus bus = new SynchronousBus();

        SalesTestKit sales = new SalesTestKit(bus, json);
        InventoryTestKit inventory = new InventoryTestKit(bus, json);
        FinanceTestKit finance = new FinanceTestKit(bus, json);

        sales.customers.put("CUST-001", "Acme Corp", Customer.Status.ACTIVE);
        UUID productId = UUID.randomUUID();
        sales.productCards.put(productId, new BigDecimal("100.00"), Currencies.AUD);
        inventory.seedStock(productId, new BigDecimal("50"));

        // Place a deposit order: 50% up front.
        UUID orderId = sales.placeOrder(new PlaceOrderCommand(
            "SO-DEP-1", "CUST-001",
            LocalDate.of(2026, 5, 20),
            Currencies.AUD,
            PaymentTerms.DEPOSIT.dbValue(),
            new BigDecimal("50"),
            List.of(new OrderLine(productId, "FG-001", "Finished Good 1",
                new BigDecimal("3"), null, BigDecimal.ZERO))
        ));

        // Worker emits DepositInvoiceRequested → finance creates the deposit
        // invoice → saga parks at deposit_invoiced.
        sales.advanceSagaWorker();
        bus.drain();

        assertThat(sales.findSagaBySalesOrderId(orderId).orElseThrow().state())
            .isEqualTo(SalesOrderFulfilmentSaga.DEPOSIT_INVOICED);
        CustomerInvoice depositInvoice = finance.customerInvoices.findAll().stream()
            .filter(i -> i.invoiceType() == CustomerInvoice.InvoiceType.DEPOSIT)
            .findFirst().orElseThrow();
        assertThat(depositInvoice.totalAmount()).isEqualByComparingTo("150.00");

        // Pay the deposit → saga reaches deposit_paid + emits upfront-settled.
        finance.paymentService.recordCustomerPayment(new RecordCustomerPaymentCommand(
            "PAY-DEP", depositInvoice.id().value(),
            new BigDecimal("150.00"), Payment.Method.BANK_TRANSFER.dbValue(),
            LocalDate.of(2026, 5, 20)
        ));
        bus.drain();
        assertThat(sales.findSagaBySalesOrderId(orderId).orElseThrow().state())
            .isEqualTo(SalesOrderFulfilmentSaga.DEPOSIT_PAID);

        // Worker picks up deposit_paid → requests reservation → full cover →
        // ready_to_ship.
        sales.advanceSagaWorker();
        bus.drain();
        assertThat(sales.findSagaBySalesOrderId(orderId).orElseThrow().state())
            .isEqualTo(SalesOrderFulfilmentSaga.READY_TO_SHIP);

        // Ship: gate passes (upfront settled); finance creates the balance
        // invoice (Dr AR/Cr Revenue) + recognises the deposit (Dr 2110/Cr Revenue).
        UUID customerId = sales.customers.findByCode("CUST-001").orElseThrow().customerId();
        SalesOrderLine placedLine = sales.orders.findById(SalesOrderId.of(orderId)).orElseThrow().lines().get(0);
        inventory.shipmentService.post(new PostShipmentCommand(
            "SHIP-DEP-1", orderId, customerId, "Acme Corp", WarehouseCodes.MAIN,
            List.of(new ShipmentLineRequest(
                placedLine.lineId(), productId, "FG-001", "Finished Good 1",
                new BigDecimal("3"), new BigDecimal("60.00")
            ))
        ));
        bus.drain();

        assertThat(sales.findSagaBySalesOrderId(orderId).orElseThrow().state())
            .isEqualTo(SalesOrderFulfilmentSaga.INVOICE_CREATED);
        CustomerInvoice balanceInvoice = finance.customerInvoices.findAll().stream()
            .filter(i -> i.invoiceType() == CustomerInvoice.InvoiceType.BALANCE)
            .findFirst().orElseThrow();
        assertThat(balanceInvoice.totalAmount()).isEqualByComparingTo("150.00");

        // Settle the balance → completed.
        finance.paymentService.recordCustomerPayment(new RecordCustomerPaymentCommand(
            "PAY-BAL", balanceInvoice.id().value(),
            new BigDecimal("150.00"), Payment.Method.BANK_TRANSFER.dbValue(),
            LocalDate.of(2026, 5, 21)
        ));
        bus.drain();

        assertThat(sales.findSagaBySalesOrderId(orderId).orElseThrow().state())
            .isEqualTo(SalesOrderFulfilmentSaga.COMPLETED);
        assertThat(sales.orderStatus(orderId)).contains(SalesOrder.Status.COMPLETED);

        // Two invoices (deposit + balance), two customer payments totalling 300.
        assertThat(finance.customerInvoices.findAll()).hasSize(2);
        assertThat(finance.payments.findAll()).hasSize(2);

        // Cross-service event audit for the two-invoice deposit flow.
        assertThat(sales.outbox.all()).extracting(OutboxRow::getEventType)
            .contains(SalesOrderPlaced.EVENT_TYPE, DepositInvoiceRequested.EVENT_TYPE,
                SalesOrderUpfrontPaymentSettled.EVENT_TYPE, SalesOrderShipped.EVENT_TYPE);
        assertThat(inventory.outbox.all()).extracting(OutboxRow::getEventType)
            .contains(StockReserved.EVENT_TYPE, ShipmentPosted.EVENT_TYPE);
        assertThat(finance.outbox.all()).extracting(OutboxRow::getEventType)
            .contains(CustomerInvoiceCreated.EVENT_TYPE, CustomerPaymentReceived.EVENT_TYPE);
    }
}
