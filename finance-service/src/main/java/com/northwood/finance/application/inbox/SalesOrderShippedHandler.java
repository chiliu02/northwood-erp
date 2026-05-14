package com.northwood.finance.application.inbox;

import com.northwood.finance.application.CustomerInvoiceService;
import com.northwood.sales.domain.events.SalesOrderShipped;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Idempotent inbox handler for {@code sales.SalesOrderShipped}. Auto-creates
 * a customer invoice from the shipment's line snapshots (qty + price + tax).
 * Inbox dedupe ensures a redelivered shipment doesn't double-invoice.
 */
@Component
public class SalesOrderShippedHandler extends AbstractInboxHandler<SalesOrderShipped> {

    public static final String CONSUMER_NAME = "finance.customer-invoice.shipped-order";

    private final CustomerInvoiceService invoices;

    public SalesOrderShippedHandler(
        InboxPort inbox,
        CustomerInvoiceService invoices,
        ObjectMapper json
    ) {
        super(inbox, json, SalesOrderShipped.class, SalesOrderShipped.EVENT_TYPE, CONSUMER_NAME);
        this.invoices = invoices;
    }

    @Override
    protected void apply(SalesOrderShipped payload, EventEnvelope envelope) {
        invoices.createFromShippedOrder(payload);

        log.info("[{}] auto-invoiced sales_order={} (shipment={})",
            CONSUMER_NAME, payload.aggregateId(), payload.shipmentNumber());
    }
}
