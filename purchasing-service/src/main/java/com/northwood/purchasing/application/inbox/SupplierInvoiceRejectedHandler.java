package com.northwood.purchasing.application.inbox;

import com.northwood.finance.domain.events.SupplierInvoiceRejected;
import com.northwood.purchasing.application.saga.PurchaseToPaySagaManager;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Inbox handler for {@code finance.SupplierInvoiceRejected}. Lands the P2P
 * saga in terminal {@code failed} via the manager so it stops being polled.
 *
 * <p>No projection writeback is needed: the PO header stays in whatever
 * state it's in (typically {@code 'sent'} or {@code 'partially_received'};
 * goods were already received but never invoiced). Cleaning up the PO
 * itself is the operator's job — there's no PO-cancellation flow today
 * (documented as a deferred gap on Story 6.1).
 */
@Component
public class SupplierInvoiceRejectedHandler extends AbstractInboxHandler<SupplierInvoiceRejected> {

    public static final String HANDLER_NAME = "purchasing.p2p.supplier-invoice-rejected";

    private final PurchaseToPaySagaManager sagaManager;

    public SupplierInvoiceRejectedHandler(
        InboxPort inbox,
        PurchaseToPaySagaManager sagaManager,
        ObjectMapper json
    ) {
        super(inbox, json, SupplierInvoiceRejected.class, SupplierInvoiceRejected.EVENT_TYPE, HANDLER_NAME);
        this.sagaManager = sagaManager;
    }

    @Override
    protected void apply(SupplierInvoiceRejected payload, EventEnvelope envelope) {
        sagaManager.applySupplierInvoiceRejected(payload.purchaseOrderHeaderId());
        log.info("[{}] po={} reason='{}' → saga failed",
            HANDLER_NAME, payload.purchaseOrderHeaderId(), payload.reason());
    }
}
