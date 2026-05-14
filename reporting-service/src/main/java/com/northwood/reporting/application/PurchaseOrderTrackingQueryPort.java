package com.northwood.reporting.application;

import com.northwood.reporting.application.dto.PurchaseOrderTrackingView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PurchaseOrderTrackingQueryPort {

    Optional<PurchaseOrderTrackingView> findByPurchaseOrderId(UUID purchaseOrderHeaderId);

    /** All tracked POs, newest activity first. Used by the demo UI list view. */
    List<PurchaseOrderTrackingView> findAll();
}
