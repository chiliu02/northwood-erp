package com.northwood.purchasing.application.inbox;

import com.northwood.finance.domain.events.SupplierInvoiceApproved;
import com.northwood.purchasing.application.saga.PurchaseToPaySagaManager;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Inbox handler for {@code finance.SupplierInvoiceApproved}. Two effects in
 * the same transaction:
 *
 * <ol>
 *   <li>Advance the saga {@code goods_received → supplier_invoice_approved}
 *       via the manager.</li>
 *   <li>Bump the PO header's {@code invoiced_amount} by the approved invoice
 *       total and flip status to {@code 'invoiced'} (or
 *       {@code 'partially_invoiced'}). Must happen here because the schema
 *       CHECK {@code paid_amount <= invoiced_amount} would otherwise reject
 *       any subsequent {@code SupplierPaymentMade} that flows through.</li>
 * </ol>
 */
@Component
public class SupplierInvoiceApprovedHandler extends AbstractInboxHandler<SupplierInvoiceApproved> {

    public static final String HANDLER_NAME = "purchasing.p2p.supplier-invoice-approved";

    private final PurchaseToPaySagaManager sagaManager;
    private final PurchaseOrderPaymentProjection paymentProjection;

    public SupplierInvoiceApprovedHandler(
        InboxPort inbox,
        PurchaseToPaySagaManager sagaManager,
        PurchaseOrderPaymentProjection paymentProjection,
        ObjectMapper json
    ) {
        super(inbox, json, SupplierInvoiceApproved.class, SupplierInvoiceApproved.EVENT_TYPE, HANDLER_NAME);
        this.sagaManager = sagaManager;
        this.paymentProjection = paymentProjection;
    }

    @Override
    protected void apply(SupplierInvoiceApproved payload, EventEnvelope envelope) {
        sagaManager.applySupplierInvoiceApproved(payload.purchaseOrderHeaderId());
        paymentProjection.addInvoicedAmount(payload.purchaseOrderHeaderId(), payload.totalAmount());
    }
}
