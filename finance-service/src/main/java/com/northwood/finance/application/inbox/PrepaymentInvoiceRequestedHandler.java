package com.northwood.finance.application.inbox;

import com.northwood.finance.application.CustomerInvoiceService;
import com.northwood.sales.domain.events.PrepaymentInvoiceRequested;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Idempotent inbox handler for
 * {@code sales.PrepaymentInvoiceRequested}. Auto-creates a customer invoice
 * with {@code invoice_type='prepayment'} from the order's line snapshots
 * (qty + price + tax) — finance posts <b>no</b> journal entry at creation
 * (Treatment A); revenue is deferred until shipment. Inbox dedupe ensures a
 * redelivered request doesn't double-invoice.
 */
@Component
public class PrepaymentInvoiceRequestedHandler extends AbstractInboxHandler<PrepaymentInvoiceRequested> {

    public static final String CONSUMER_NAME = "finance.customer-invoice.prepayment-requested";

    private final CustomerInvoiceService invoices;

    public PrepaymentInvoiceRequestedHandler(
        InboxPort inbox,
        CustomerInvoiceService invoices,
        ObjectMapper json
    ) {
        super(inbox, json, PrepaymentInvoiceRequested.class, PrepaymentInvoiceRequested.EVENT_TYPE, CONSUMER_NAME);
        this.invoices = invoices;
    }

    @Override
    protected void apply(PrepaymentInvoiceRequested payload, EventEnvelope envelope) {
        invoices.createFromPrepaymentRequest(payload);

        log.info("[{}] auto-prepayment-invoiced sales_order={} ({})",
            CONSUMER_NAME, payload.aggregateId(), payload.orderNumber());
    }
}
