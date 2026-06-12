package com.northwood.finance.application.inbox;

import com.northwood.finance.application.JournalEntryService;
import com.northwood.finance.domain.CustomerInvoice;
import com.northwood.finance.domain.CustomerInvoiceRepository;
import com.northwood.finance.domain.CustomerInvoiceRepository.PaymentSnapshot;
import com.northwood.finance.domain.CustomerInvoiceRepository.ShipmentTimeInvoice;
import com.northwood.sales.domain.events.SalesOrderCancellationRequested;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Refund on a cancelled prepayment/deposit order. Idempotent inbox
 * handler for {@code sales.SalesOrderCancellationRequested}: if the cancelled
 * order had an up-front (prepayment or deposit) invoice that was paid, the cash
 * sitting in 2110 Customer Deposits is returned — Dr 2110 / Cr 1000 Bank — the
 * exact inverse of the original payment receipt.
 *
 * <p>The existing sales↔inventory compensation (release the reservation) is the
 * other arm of the cancel; this is the finance-only refund arm. On-shipment and
 * COD orders have no invoice before shipment, so nothing is parked in 2110 and
 * this handler no-ops for them. The earliest invoice for an order is always the
 * up-front one (the balance invoice is only created at shipment, which a
 * cancellable order hasn't reached).
 *
 * <p>Idempotency: the {@code customer_invoice_header.refunded_at} gate
 * (mirroring {@code revenue_recognized_at} for prepayment orders) means a
 * redelivered cancellation posts the refund at most once.
 */
@Component
public class SalesOrderCancellationRefundHandler
    extends AbstractInboxHandler<SalesOrderCancellationRequested> {

    public static final String CONSUMER_NAME = "finance.refund.sales-order-cancellation";

    private final JournalEntryService journals;
    private final CustomerInvoiceRepository customerInvoices;

    public SalesOrderCancellationRefundHandler(
        InboxPort inbox,
        JournalEntryService journals,
        CustomerInvoiceRepository customerInvoices,
        ObjectMapper json
    ) {
        super(inbox, json,
            SalesOrderCancellationRequested.class,
            SalesOrderCancellationRequested.EVENT_TYPE,
            CONSUMER_NAME);
        this.journals = journals;
        this.customerInvoices = customerInvoices;
    }

    @Override
    protected void apply(SalesOrderCancellationRequested payload, EventEnvelope envelope) {
        Optional<ShipmentTimeInvoice> found = customerInvoices.findInvoiceForShipment(payload.aggregateId());
        if (found.isEmpty()) {
            log.debug("[{}] sales_order={} has no invoice — nothing to refund (on-shipment/COD cancelled pre-invoice)",
                CONSUMER_NAME, payload.aggregateId());
            return;
        }
        ShipmentTimeInvoice invoice = found.get();
        if (invoice.invoiceType() != CustomerInvoice.InvoiceType.PREPAYMENT
            && invoice.invoiceType() != CustomerInvoice.InvoiceType.DEPOSIT) {
            log.debug("[{}] sales_order={} up-front invoice is {} — no Customer Deposits balance to refund",
                CONSUMER_NAME, payload.aggregateId(), invoice.invoiceType().code());
            return;
        }

        BigDecimal paid = customerInvoices.findPaymentSnapshot(invoice.customerInvoiceHeaderId())
            .map(PaymentSnapshot::paidAmount).orElse(BigDecimal.ZERO);
        if (paid.signum() <= 0) {
            log.debug("[{}] sales_order={} {} invoice unpaid — nothing in 2110, clean cancel suffices",
                CONSUMER_NAME, payload.aggregateId(), invoice.invoiceType().code());
            return;
        }

        if (!customerInvoices.markRefunded(invoice.customerInvoiceHeaderId())) {
            log.debug("[{}] sales_order={} invoice {} already refunded — skipping",
                CONSUMER_NAME, payload.aggregateId(), invoice.invoiceNumber());
            return;
        }

        LocalDate postingDate = payload.occurredAt() == null
            ? LocalDate.now()
            : payload.occurredAt().atZone(ZoneId.systemDefault()).toLocalDate();
        journals.postCustomerRefund(
            invoice.customerInvoiceHeaderId(),
            invoice.customerName(),
            invoice.invoiceNumber(),
            paid,
            invoice.currencyCode(),
            postingDate
        );
        log.info("[{}] refunded {} {} for cancelled sales_order={} (invoice {})",
            CONSUMER_NAME, paid, invoice.currencyCode(), payload.aggregateId(), invoice.invoiceNumber());
    }
}
