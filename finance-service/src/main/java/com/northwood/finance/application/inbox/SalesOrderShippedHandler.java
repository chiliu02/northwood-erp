package com.northwood.finance.application.inbox;

import com.northwood.finance.application.CustomerInvoiceService;
import com.northwood.finance.application.PaymentService;
import com.northwood.finance.domain.CustomerInvoiceId;
import com.northwood.sales.domain.PaymentTerms;
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
 *
 * <p>§2.33 — for cash-on-delivery orders ({@code paymentTerms = cod}), payment
 * lands at the goods-delivered moment, so this handler also auto-records the
 * full customer payment against the just-created invoice in the same
 * transaction (Dr Cash / Cr AR). On-shipment orders still wait for an operator
 * to record the payment; prepayment orders were invoiced + paid at placement
 * (their existing prepayment invoice is reused, no second invoice created).
 */
@Component
public class SalesOrderShippedHandler extends AbstractInboxHandler<SalesOrderShipped> {

    public static final String CONSUMER_NAME = "finance.customer-invoice.shipped-order";

    private final CustomerInvoiceService invoices;
    private final PaymentService payments;

    public SalesOrderShippedHandler(
        InboxPort inbox,
        CustomerInvoiceService invoices,
        PaymentService payments,
        ObjectMapper json
    ) {
        super(inbox, json, SalesOrderShipped.class, SalesOrderShipped.EVENT_TYPE, CONSUMER_NAME);
        this.invoices = invoices;
        this.payments = payments;
    }

    @Override
    protected void apply(SalesOrderShipped payload, EventEnvelope envelope) {
        CustomerInvoiceId invoiceId = invoices.createFromShippedOrder(payload);

        if (PaymentTerms.CASH_ON_DELIVERY.dbValue().equals(payload.paymentTerms())) {
            payments.recordCashOnDeliveryPayment(invoiceId.value(), payload.shipmentDate());
            log.info("[{}] auto-invoiced + COD-settled sales_order={} (shipment={})",
                CONSUMER_NAME, payload.aggregateId(), payload.shipmentNumber());
        } else {
            log.info("[{}] auto-invoiced sales_order={} (shipment={})",
                CONSUMER_NAME, payload.aggregateId(), payload.shipmentNumber());
        }
    }
}
