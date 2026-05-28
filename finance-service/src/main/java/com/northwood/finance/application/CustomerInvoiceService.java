package com.northwood.finance.application;

import com.northwood.finance.application.dto.CustomerInvoiceView;
import com.northwood.finance.domain.CustomerInvoice;
import com.northwood.finance.domain.CustomerInvoiceId;
import com.northwood.finance.domain.CustomerInvoiceLine;
import com.northwood.finance.domain.CustomerInvoiceRepository;
import com.northwood.sales.domain.events.PrepaymentInvoiceRequested;
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
        // §2.31 Slice C: prepayment orders already have an invoice (created
        // from PrepaymentInvoiceRequested at order placement) — finance must
        // not create a second one when the shipment lands. The deferred-
        // revenue Dr 2110 / Cr Revenue pair is posted by the shipment-time
        // handler (ShipmentPostedCogsHandler) against the existing invoice.
        java.util.Optional<CustomerInvoiceRepository.ShipmentTimeInvoice> existing =
            customerInvoices.findInvoiceForShipment(payload.aggregateId());
        if (existing.isPresent()
            && existing.get().invoiceType() == CustomerInvoice.InvoiceType.PREPAYMENT) {
            log.info("skipping createFromShippedOrder for sales_order={} — prepayment invoice {} already exists",
                payload.aggregateId(), existing.get().invoiceNumber());
            return CustomerInvoiceId.of(existing.get().customerInvoiceHeaderId());
        }

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

        String invoiceNumber = CustomerInvoice.NUMBER_PREFIX + UUID.randomUUID().toString().substring(0, CustomerInvoice.NUMBER_SUFFIX_LENGTH).toUpperCase();
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

    /**
     * §2.31 Slice B. Auto-create a <b>prepayment</b> customer invoice from a
     * {@code sales.PrepaymentInvoiceRequested} event. Same line-construction
     * logic as {@link #createFromShippedOrder} (qty × unitPrice × taxRate from
     * the event payload verbatim) but stamps {@code invoice_type='prepayment'}
     * on the header and does <b>not</b> post a journal entry — Treatment A:
     * revenue is deferred until shipment (Slice C), and the payment receipt
     * (Cr {@code 2110 Customer Deposits}) is what touches the GL.
     */
    @Transactional
    public CustomerInvoiceId createFromPrepaymentRequest(PrepaymentInvoiceRequested payload) {
        List<CustomerInvoiceLine> lines = new ArrayList<>();
        for (PrepaymentInvoiceRequested.RequestedLine rl : payload.lines()) {
            BigDecimal qty = rl.quantity();
            BigDecimal unit = rl.unitPrice() == null ? BigDecimal.ZERO : rl.unitPrice();
            BigDecimal taxRate = rl.taxRate() == null ? BigDecimal.ZERO : rl.taxRate();
            BigDecimal lineSubtotal = qty.multiply(unit).setScale(2, RoundingMode.HALF_UP);
            BigDecimal lineTax = lineSubtotal.multiply(taxRate).setScale(2, RoundingMode.HALF_UP);
            lines.add(new CustomerInvoiceLine(
                UUID.randomUUID(), rl.lineNumber(),
                rl.salesOrderLineId(),
                rl.productId(), rl.productSku(), rl.productName(),
                qty, unit, taxRate, lineTax, lineSubtotal
            ));
        }

        String invoiceNumber = CustomerInvoice.NUMBER_PREFIX + UUID.randomUUID().toString().substring(0, CustomerInvoice.NUMBER_SUFFIX_LENGTH).toUpperCase();
        CustomerInvoice invoice = CustomerInvoice.createPrepayment(
            invoiceNumber,
            payload.aggregateId(),
            payload.customerId(),
            payload.customerCode(),
            payload.customerName(),
            payload.currencyCode(),
            lines
        );
        customerInvoices.save(invoice);

        log.info("auto-created prepayment customer_invoice {} for sales_order={} (total={} {}, {} line(s); no GL until payment)",
            invoiceNumber, payload.aggregateId(),
            invoice.totalAmount(), invoice.currencyCode(), lines.size());
        return invoice.id();
    }

}
