package com.northwood.finance.application.inbox;

import com.northwood.finance.application.CustomerInvoiceService;
import com.northwood.sales.domain.events.DepositInvoiceRequested;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Idempotent inbox handler for
 * {@code sales.DepositInvoiceRequested}. Auto-creates a customer invoice with
 * {@code invoice_type='deposit'} for the up-front part-payment — a single
 * synthetic deposit line, no journal entry at creation (Treatment A; the
 * deposit hits the GL when paid, Dr Cash / Cr 2110). Inbox dedupe ensures a
 * redelivered request doesn't double-invoice.
 */
@Component
public class DepositInvoiceRequestedHandler extends AbstractInboxHandler<DepositInvoiceRequested> {

    public static final String CONSUMER_NAME = "finance.customer-invoice.deposit-requested";

    private final CustomerInvoiceService invoices;

    public DepositInvoiceRequestedHandler(
        InboxPort inbox,
        CustomerInvoiceService invoices,
        ObjectMapper json
    ) {
        super(inbox, json, DepositInvoiceRequested.class, DepositInvoiceRequested.EVENT_TYPE, CONSUMER_NAME);
        this.invoices = invoices;
    }

    @Override
    protected void apply(DepositInvoiceRequested payload, EventEnvelope envelope) {
        invoices.createFromDepositRequest(payload);

        log.info("[{}] auto-deposit-invoiced sales_order={} ({})",
            CONSUMER_NAME, payload.aggregateId(), payload.orderNumber());
    }
}
