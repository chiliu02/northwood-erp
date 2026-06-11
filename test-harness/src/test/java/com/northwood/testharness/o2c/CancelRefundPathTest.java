package com.northwood.testharness.o2c;

import static org.assertj.core.api.Assertions.assertThat;

import com.northwood.finance.application.dto.RecordCustomerPaymentCommand;
import com.northwood.finance.domain.CustomerInvoice;
import com.northwood.finance.domain.JournalEntry;
import com.northwood.finance.domain.JournalEntryLine;
import com.northwood.finance.domain.Payment;
import com.northwood.sales.application.dto.PlaceOrderCommand;
import com.northwood.sales.application.dto.PlaceOrderCommand.OrderLine;
import com.northwood.sales.domain.Customer;
import com.northwood.sales.domain.PaymentTerms;
import com.northwood.sales.domain.SalesOrder;
import com.northwood.sales.domain.SalesOrderId;
import com.northwood.sales.domain.events.SalesOrderCancellationRequested;
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
 * Refund on a cancelled deposit order. Place a 50%-deposit order, pay
 * the deposit (Cr 2110 Customer Deposits), then cancel before shipment. Finance's
 * refund handler reverses the deposit (Dr 2110 / Cr 1000 Bank) so 2110 nets to
 * zero, while the existing sales↔inventory compensation completes the saga.
 *
 * <p>Order: 3 × $100 = $300, 50% deposit → $150 deposited then refunded.
 */
class CancelRefundPathTest {

    @Test
    void cancelling_a_paid_deposit_order_refunds_the_deposit() {
        ObjectMapper json = new ObjectMapper();
        SynchronousBus bus = new SynchronousBus();

        SalesTestKit sales = new SalesTestKit(bus, json);
        InventoryTestKit inventory = new InventoryTestKit(bus, json);
        FinanceTestKit finance = new FinanceTestKit(bus, json);

        sales.customers.put("CUST-001", "Acme Corp", Customer.Status.ACTIVE);
        UUID productId = UUID.randomUUID();
        sales.productCards.put(productId, new BigDecimal("100.00"), Currencies.AUD);
        inventory.seedStock(productId, new BigDecimal("50"));

        // Place a deposit order: 50% up front = $150 of $300.
        UUID orderId = sales.placeOrder(new PlaceOrderCommand(
            "SO-RFD-1", "CUST-001",
            LocalDate.of(2026, 5, 20), Currencies.AUD,
            PaymentTerms.DEPOSIT.dbValue(), new BigDecimal("50"),
            List.of(new OrderLine(productId, "FG-001", "Finished Good 1",
                new BigDecimal("3"), null, BigDecimal.ZERO))
        ));

        // Worker emits DepositInvoiceRequested → finance creates the deposit invoice.
        sales.advanceSagaWorker();
        bus.drain();
        CustomerInvoice depositInvoice = finance.customerInvoices.findAll().stream()
            .filter(i -> i.invoiceType() == CustomerInvoice.InvoiceType.DEPOSIT)
            .findFirst().orElseThrow();
        assertThat(depositInvoice.totalAmount()).isEqualByComparingTo("150.00");

        // Pay the deposit → Cr 2110 $150; the up-front-payment gate advances the
        // saga to prepaid (the unified post-up-front-payment checkpoint).
        finance.paymentService.recordCustomerPayment(new RecordCustomerPaymentCommand(
            "PAY-DEP", depositInvoice.id().value(),
            new BigDecimal("150.00"), Payment.Method.BANK_TRANSFER.dbValue(),
            LocalDate.of(2026, 5, 20)
        ));
        bus.drain();
        // Stand in for the maintain_allocation_totals DB trigger so the refund
        // handler sees the deposit as paid (production updates paid_amount on the
        // allocation insert; the harness uses recordAllocation).
        finance.customerInvoices.recordAllocation(depositInvoice.id().value(), new BigDecimal("150.00"));

        assertThat(sales.findSagaBySalesOrderId(orderId).orElseThrow().state())
            .isEqualTo(SalesOrderFulfilmentSaga.PREPAID);

        // Cancel before shipment → sales emits SalesOrderCancellationRequested.
        sales.cancel(orderId, "customer changed mind");
        bus.drain();

        // Saga compensated (inventory ack); finance refunded the deposit.
        assertThat(sales.findSagaBySalesOrderId(orderId).orElseThrow().state())
            .isEqualTo(SalesOrderFulfilmentSaga.COMPENSATED);
        assertThat(sales.orders.findById(SalesOrderId.of(orderId)).orElseThrow().status())
            .isEqualTo(SalesOrder.Status.CANCELLED);

        // A CUSTOMER_REFUND journal posted Dr 2110 / Cr 1000 for $150.
        JournalEntry refund = finance.journalEntries.all().stream()
            .filter(e -> e.sourceDocumentType() == JournalEntry.SourceDocumentType.CUSTOMER_REFUND)
            .findFirst().orElseThrow();
        assertThat(debitFor(refund, "2110")).isEqualByComparingTo("150.00");
        assertThat(creditFor(refund, "1000")).isEqualByComparingTo("150.00");

        // 2110 Customer Deposits nets to zero across the deposit payment + refund.
        BigDecimal deposits2110 = finance.journalEntries.all().stream()
            .flatMap(e -> e.lines().stream())
            .filter(l -> "2110".equals(l.accountCode()))
            .map(l -> l.debitAmount().subtract(l.creditAmount()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(deposits2110).as("2110 nets to zero after refund").isEqualByComparingTo("0.00");

        assertThat(sales.outbox.all()).extracting(OutboxRow::getEventType)
            .contains(SalesOrderCancellationRequested.EVENT_TYPE);
    }

    private static BigDecimal debitFor(JournalEntry entry, String code) {
        return entry.lines().stream().filter(l -> code.equals(l.accountCode()))
            .map(JournalEntryLine::debitAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal creditFor(JournalEntry entry, String code) {
        return entry.lines().stream().filter(l -> code.equals(l.accountCode()))
            .map(JournalEntryLine::creditAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
