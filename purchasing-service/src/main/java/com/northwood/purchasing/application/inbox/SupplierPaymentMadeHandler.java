package com.northwood.purchasing.application.inbox;

import static com.northwood.purchasing.domain.saga.PurchaseToPaySaga.COMPLETED;
import static com.northwood.purchasing.domain.saga.PurchaseToPaySaga.SUPPLIER_PARTIALLY_PAID;

import com.northwood.finance.domain.events.SupplierPaymentMade;
import com.northwood.purchasing.application.saga.PurchaseToPaySagaManager;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Inbox handler for {@code finance.SupplierPaymentMade}. Asks the manager
 * to apply the payment outcome; on full settlement
 * ({@code invoiceStatusAfter='paid'}) the manager flips the saga to
 * {@code completed} and the handler marks the PO fully paid in the projection,
 * otherwise marks the partial payment.
 */
@Component
public class SupplierPaymentMadeHandler extends AbstractInboxHandler<SupplierPaymentMade> {

    public static final String HANDLER_NAME = "purchasing.p2p.supplier-payment-made";

    private final PurchaseToPaySagaManager sagaManager;
    private final PurchaseOrderPaymentProjection paymentProjection;

    public SupplierPaymentMadeHandler(
        InboxPort inbox,
        PurchaseToPaySagaManager sagaManager,
        PurchaseOrderPaymentProjection paymentProjection,
        ObjectMapper json
    ) {
        super(inbox, json, SupplierPaymentMade.class, SupplierPaymentMade.EVENT_TYPE, HANDLER_NAME);
        this.sagaManager = sagaManager;
        this.paymentProjection = paymentProjection;
    }

    @Override
    protected void apply(SupplierPaymentMade payload, EventEnvelope envelope) {
        boolean fullySettled = SupplierPaymentMade.INVOICE_STATUS_PAID.equals(payload.invoiceStatusAfter());
        String newState = sagaManager.applySupplierPaymentMade(
            payload.purchaseOrderHeaderId(), fullySettled
        );
        if (COMPLETED.equals(newState)) {
            paymentProjection.markFullyPaid(payload.purchaseOrderHeaderId());
        } else if (SUPPLIER_PARTIALLY_PAID.equals(newState)) {
            paymentProjection.addPartialPayment(payload.purchaseOrderHeaderId(), payload.allocatedAmount());
        }
    }
}
