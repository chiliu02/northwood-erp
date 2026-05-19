package com.northwood.finance.application;

import com.northwood.finance.application.dto.CustomerInvoiceView;
import com.northwood.finance.domain.CustomerInvoice;
import com.northwood.finance.domain.CustomerInvoiceId;
import com.northwood.finance.domain.CustomerInvoiceLine;
import com.northwood.finance.domain.CustomerInvoiceRepository;
import com.northwood.sales.domain.events.SalesOrderShipped;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for customer invoices. Phase 5c supports one
 * automated path: {@link #createFromShippedOrder}, called by the
 * {@code SalesOrderShippedHandler} on every shipment. Goes straight to
 * status {@code 'posted'}.
 *
 * <p>Phase 5c limitations:
 * <ul>
 *   <li>Phase 5b: GL posting (Dr AR, Cr Revenue) lands in the same txn.</li>
 *   <li>No manual creation endpoint — auto-only.</li>
 *   <li>No credit notes / refunds.</li>
 * </ul>
 */
@Service
public class CustomerInvoiceService {

    private static final Logger log = LoggerFactory.getLogger(CustomerInvoiceService.class);

    private final CustomerInvoiceRepository customerInvoices;
    private final JournalEntryService journalEntries;

    public CustomerInvoiceService(CustomerInvoiceRepository customerInvoices, JournalEntryService journalEntries) {
        this.customerInvoices = customerInvoices;
        this.journalEntries = journalEntries;
    }

    @Transactional(readOnly = true)
    public Optional<CustomerInvoiceView> findById(UUID customerInvoiceHeaderId) {
        return customerInvoices.findById(CustomerInvoiceId.of(customerInvoiceHeaderId))
            .map(CustomerInvoiceView::from);
    }

    @Transactional(readOnly = true)
    public List<CustomerInvoiceView> findAll() {
        return customerInvoices.findAll().stream().map(CustomerInvoiceView::from).toList();
    }

    /**
     * Auto-create a customer invoice from a {@code sales.SalesOrderShipped}
     * event. Per-line {@code unitPrice} and {@code taxRate} come from the
     * payload <b>verbatim</b> — no re-fetch from {@code sales_order_line} or
     * any pricing projection. Sales is the source of truth for price; finance
     * trusts what's on the wire.
     *
     * <p><b>Sentinel-zero contract from the upstream emitter.</b>
     * {@code SalesOrder.recordShipped} matches inbound shipment lines to its
     * own ordered lines by {@code salesOrderLineId} and falls back to
     * {@code lineNumber=0} + {@code unitPrice=ZERO} + {@code taxRate=ZERO} on
     * a miss (the saga must keep flowing — see the recordShipped Javadoc for
     * the full rationale). This service propagates the zero through to the
     * {@code customer_invoice_line} row and the AR/Revenue GL posting, so a
     * sentinel-zero line emits a zero-amount invoice line and contributes
     * zero to the journal entry. We log at DEBUG when the sentinel is
     * detected so the divergence is visible without breaking the flow.
     */
    @Transactional
    public CustomerInvoiceId createFromShippedOrder(SalesOrderShipped payload) {
        List<CustomerInvoiceLine> lines = new ArrayList<>();
        int sentinelZeroLines = 0;
        for (SalesOrderShipped.ShippedLine sl : payload.lines()) {
            BigDecimal qty = sl.shippedQuantity();
            BigDecimal unit = sl.unitPrice() == null ? BigDecimal.ZERO : sl.unitPrice();
            BigDecimal taxRate = sl.taxRate() == null ? BigDecimal.ZERO : sl.taxRate();
            // Sales-side fallback signal: real SalesOrderLines have
            // lineNumber >= 1, so lineNumber == 0 means upstream couldn't
            // match the inbound shipment line to an ordered line.
            if (sl.lineNumber() == 0) {
                sentinelZeroLines++;
            }
            BigDecimal lineSubtotal = qty.multiply(unit).setScale(2, RoundingMode.HALF_UP);
            BigDecimal lineTax = lineSubtotal.multiply(taxRate).setScale(2, RoundingMode.HALF_UP);
            lines.add(new CustomerInvoiceLine(
                UUID.randomUUID(), sl.lineNumber(),
                sl.salesOrderLineId(),
                sl.productId(), sl.productSku(), sl.productName(),
                qty, unit, taxRate, lineTax, lineSubtotal
            ));
        }
        if (sentinelZeroLines > 0) {
            log.debug(
                "createFromShippedOrder sales_order={} shipment={} encountered {} sentinel-zero line(s) "
                    + "(unmatched salesOrderLineId on the sales side); those line(s) will invoice at zero",
                payload.aggregateId(), payload.shipmentNumber(), sentinelZeroLines
            );
        }

        String invoiceNumber = CustomerInvoice.NUMBER_PREFIX + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        CustomerInvoice invoice = CustomerInvoice.create(
            invoiceNumber,
            payload.aggregateId(),
            payload.customerId(),
            payload.customerCode(),
            payload.customerName(),
            payload.currencyCode(),
            lines
        );
        customerInvoices.save(invoice);

        // Phase 5b: post the GL pair (Dr AR, Cr Revenue) in the same txn.
        journalEntries.postCustomerInvoiceCreation(
            invoice.id().value(),
            invoice.customerName(),
            invoice.invoiceNumber(),
            invoice.totalAmount(),
            invoice.currencyCode(),
            java.time.LocalDate.now()
        );

        log.info("auto-created customer_invoice {} for sales_order={} (total={} {}, {} line(s))",
            invoiceNumber, payload.aggregateId(),
            invoice.totalAmount(), invoice.currencyCode(), lines.size());
        return invoice.id();
    }

}
