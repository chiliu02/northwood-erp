package com.northwood.purchasing.application.inbox;

import com.northwood.product.domain.events.ApprovedVendorListChanged;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Idempotent inbox handler for {@code product.ApprovedVendorListChanged}.
 * Maintains the {@code purchasing.product_approved_vendor} projection.
 */
@Component
public class ApprovedVendorListChangedHandler extends AbstractInboxHandler<ApprovedVendorListChanged> {

    public static final String CONSUMER_NAME = "purchasing.approved-vendor-projector";

    private final ProductApprovedVendorProjection projection;

    public ApprovedVendorListChangedHandler(
        InboxPort inbox,
        ProductApprovedVendorProjection projection,
        ObjectMapper json
    ) {
        super(inbox, json, ApprovedVendorListChanged.class, ApprovedVendorListChanged.EVENT_TYPE, CONSUMER_NAME);
        this.projection = projection;
    }

    @Override
    protected void apply(ApprovedVendorListChanged payload, EventEnvelope envelope) {
        projection.replaceFor(payload.aggregateId(), payload.approvedVendors());
    }
}
