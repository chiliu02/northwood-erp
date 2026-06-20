package com.northwood.finance.application.inbox;

import com.northwood.finance.application.CustomerCancellationRefundService;
import com.northwood.sales.domain.events.SalesOrderCompensated;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Refunds a cancelled order's paid prepayment/deposit on {@code sales.SalesOrderCompensated}
 * — the <b>confirmed</b> cancel terminal (the cancel won the cancel-vs-ship race and
 * the order is {@code cancelled}). Delegates to {@link CustomerCancellationRefundService}.
 * Keying on this confirmed signal (not the cancel <em>request</em>) is what stops a
 * cancel that loses to a shipment from being refunded-then-shipped.
 */
@Component
public class SalesOrderCompensatedRefundHandler extends AbstractInboxHandler<SalesOrderCompensated> {

    public static final String HANDLER_NAME = "finance.refund.sales-order-compensated";

    private final CustomerCancellationRefundService refunds;

    public SalesOrderCompensatedRefundHandler(
        InboxPort inbox,
        CustomerCancellationRefundService refunds,
        ObjectMapper json
    ) {
        super(inbox, json, SalesOrderCompensated.class, SalesOrderCompensated.EVENT_TYPE, HANDLER_NAME);
        this.refunds = refunds;
    }

    @Override
    protected void apply(SalesOrderCompensated payload, EventEnvelope envelope) {
        refunds.refundUpfrontIfPaid(payload.aggregateId(), payload.occurredAt());
    }
}
