package com.northwood.manufacturing.application.inbox;

import com.northwood.product.domain.events.ApprovedVendorListChanged;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Idempotent inbox handler for {@code product.ApprovedVendorListChanged}.
 * Maintains the {@code manufacturing.product_approved_vendor} projection,
 * which the materialsCost rollup engine reads to find the preferred
 * supplier when computing a purchased item's cost.
 */
@Component
public class ApprovedVendorListChangedHandler extends AbstractInboxHandler<ApprovedVendorListChanged> {

    public static final String HANDLER_NAME = "manufacturing.approved-vendor-projector";

    private final ProductApprovedVendorProjection projection;

    public ApprovedVendorListChangedHandler(
        InboxPort inbox,
        ProductApprovedVendorProjection projection,
        ObjectMapper json
    ) {
        super(inbox, json, ApprovedVendorListChanged.class, ApprovedVendorListChanged.EVENT_TYPE, HANDLER_NAME);
        this.projection = projection;
    }

    @Override
    protected void apply(ApprovedVendorListChanged payload, EventEnvelope envelope) {
        projection.replaceFor(payload.aggregateId(), payload.approvedVendors());
    }
}
