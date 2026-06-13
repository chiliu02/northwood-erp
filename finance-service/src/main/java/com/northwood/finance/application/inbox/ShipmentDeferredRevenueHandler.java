package com.northwood.finance.application.inbox;

import com.northwood.finance.application.JournalEntryService;
import com.northwood.finance.domain.CustomerInvoice;
import com.northwood.finance.domain.CustomerInvoiceRepository;
import com.northwood.inventory.domain.events.ShipmentPosted;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Idempotent inbox handler for {@code inventory.ShipmentPosted} that recognises
 * <b>deferred revenue</b> for prepayment / deposit orders at the goods-delivered
 * moment: Dr 2110 Customer Deposits / Cr 4000 Revenue against the up-front
 * invoice (the performance obligation is met on shipment).
 *
 * <p>COGS / inventory and the on-shipment customer invoice are <em>not</em> posted
 * here — they're driven by {@code sales.SalesOrderShipped}
 * ({@link SalesOrderShippedHandler}), which carries the per-line pricing finance
 * needs (to split free-of-charge cost to Promotions) that inventory's
 * {@code ShipmentPosted} doesn't own. This handler stays on {@code ShipmentPosted}
 * purely for the deferred-revenue reclassification, gated by
 * {@code markRevenueRecognized} so a redelivered shipment can't double-recognise.
 */
@Component
public class ShipmentDeferredRevenueHandler extends AbstractInboxHandler<ShipmentPosted> {

    public static final String HANDLER_NAME = "finance.deferred-revenue.shipment-posted";

    private final JournalEntryService journals;
    private final CustomerInvoiceRepository customerInvoices;

    public ShipmentDeferredRevenueHandler(
        InboxPort inbox,
        JournalEntryService journals,
        CustomerInvoiceRepository customerInvoices,
        ObjectMapper json
    ) {
        super(inbox, json, ShipmentPosted.class, ShipmentPosted.EVENT_TYPE, HANDLER_NAME);
        this.journals = journals;
        this.customerInvoices = customerInvoices;
    }

    @Override
    protected void apply(ShipmentPosted payload, EventEnvelope envelope) {
        LocalDate postingDate = payload.occurredAt() == null
            ? LocalDate.now()
            : payload.occurredAt().atZone(ZoneId.systemDefault()).toLocalDate();

        // For prepayment AND deposit orders, recognise the deferred revenue now
        // (goods delivered): Dr 2110 Customer Deposits / Cr Revenue against the
        // up-front invoice. findInvoiceForShipment returns the earliest invoice
        // for the order (always the prepayment/deposit invoice, created at
        // placement). markRevenueRecognized is the idempotency gate.
        var existing = customerInvoices.findInvoiceForShipment(payload.salesOrderHeaderId());
        if (existing.isPresent()
            && (existing.get().invoiceType() == CustomerInvoice.InvoiceType.PREPAYMENT
                || existing.get().invoiceType() == CustomerInvoice.InvoiceType.DEPOSIT)
            && !existing.get().revenueRecognized()) {
            boolean stamped = customerInvoices.markRevenueRecognized(existing.get().customerInvoiceHeaderId());
            if (stamped) {
                journals.postPrepaymentRevenueRecognition(
                    existing.get().customerInvoiceHeaderId(),
                    existing.get().customerName(),
                    existing.get().invoiceNumber(),
                    existing.get().totalAmount(),
                    existing.get().currencyCode(),
                    postingDate
                );
                log.info("[{}] recognised deferred revenue for {} invoice {} (sales_order={}, total={} {})",
                    HANDLER_NAME, existing.get().invoiceType().code(), existing.get().invoiceNumber(),
                    payload.salesOrderHeaderId(), existing.get().totalAmount(), existing.get().currencyCode());
            }
        }
    }
}
