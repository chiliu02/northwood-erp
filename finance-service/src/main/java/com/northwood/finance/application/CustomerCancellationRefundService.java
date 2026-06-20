package com.northwood.finance.application;

import com.northwood.finance.domain.CustomerInvoice;
import com.northwood.finance.domain.CustomerInvoiceRepository;
import com.northwood.finance.domain.CustomerInvoiceRepository.PaymentSnapshot;
import com.northwood.finance.domain.CustomerInvoiceRepository.ShipmentTimeInvoice;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Refund a cancelled/rejected order's paid up-front (prepayment/deposit) invoice:
 * the cash sitting in 2110 Customer Deposits is returned — Dr 2110 / Cr 1000 Bank,
 * the exact inverse of the original payment receipt.
 *
 * <p><b>Triggered on the confirmed non-shippable terminal, not the cancel request.</b>
 * Three thin inbox handlers call this — {@code SalesOrderCompensated} /
 * {@code SalesOrderCompensationFailed} (the cancel won, header is {@code cancelled})
 * and {@code SalesOrderRejected} (unsourceable line). It is deliberately <em>not</em>
 * driven by {@code SalesOrderCancellationRequested}: that fires before the two-phase
 * cancel arbitrates cancel-vs-ship, so a cancel that loses the race to a shipment
 * would otherwise be refunded <em>and</em> shipped. Exactly one of the three confirmed
 * signals fires per terminated order, and {@code markRefunded} is the idempotency
 * backstop.
 *
 * <p>On-shipment and COD orders have no invoice before shipment, so nothing is parked
 * in 2110 and this no-ops for them. The earliest invoice for an order is always the
 * up-front one (the balance invoice is only created at shipment, which a cancellable /
 * rejected order hasn't reached).
 */
@Service
public class CustomerCancellationRefundService {

    private static final Logger log = LoggerFactory.getLogger(CustomerCancellationRefundService.class);

    private final JournalEntryService journals;
    private final CustomerInvoiceRepository customerInvoices;

    public CustomerCancellationRefundService(JournalEntryService journals, CustomerInvoiceRepository customerInvoices) {
        this.journals = journals;
        this.customerInvoices = customerInvoices;
    }

    /**
     * Post the deposit/prepayment refund for {@code salesOrderHeaderId} if its
     * up-front invoice was paid. Idempotent (the {@code refunded_at} gate); no-op for
     * on-shipment/COD orders, unpaid up-front invoices, and already-refunded ones.
     * {@code occurredAt} (from the terminal event) dates the journal; null → today.
     */
    @Transactional
    public void refundUpfrontIfPaid(java.util.UUID salesOrderHeaderId, Instant occurredAt) {
        Optional<ShipmentTimeInvoice> found = customerInvoices.findInvoiceForShipment(salesOrderHeaderId);
        if (found.isEmpty()) {
            log.debug("sales_order={} has no invoice — nothing to refund (on-shipment/COD terminated pre-invoice)",
                salesOrderHeaderId);
            return;
        }
        ShipmentTimeInvoice invoice = found.get();
        if (invoice.invoiceType() != CustomerInvoice.InvoiceType.PREPAYMENT
            && invoice.invoiceType() != CustomerInvoice.InvoiceType.DEPOSIT) {
            log.debug("sales_order={} up-front invoice is {} — no Customer Deposits balance to refund",
                salesOrderHeaderId, invoice.invoiceType().code());
            return;
        }

        BigDecimal paid = customerInvoices.findPaymentSnapshot(invoice.customerInvoiceHeaderId())
            .map(PaymentSnapshot::paidAmount).orElse(BigDecimal.ZERO);
        if (paid.signum() <= 0) {
            log.debug("sales_order={} {} invoice unpaid — nothing in 2110, clean termination suffices",
                salesOrderHeaderId, invoice.invoiceType().code());
            return;
        }

        if (!customerInvoices.markRefunded(invoice.customerInvoiceHeaderId())) {
            log.debug("sales_order={} invoice {} already refunded — skipping",
                salesOrderHeaderId, invoice.invoiceNumber());
            return;
        }

        LocalDate postingDate = occurredAt == null
            ? LocalDate.now()
            : occurredAt.atZone(ZoneId.systemDefault()).toLocalDate();
        journals.postCustomerRefund(
            invoice.customerInvoiceHeaderId(),
            invoice.customerName(),
            invoice.invoiceNumber(),
            paid,
            invoice.currencyCode(),
            postingDate
        );
        log.info("refunded {} {} for terminated sales_order={} (invoice {})",
            paid, invoice.currencyCode(), salesOrderHeaderId, invoice.invoiceNumber());
    }
}
