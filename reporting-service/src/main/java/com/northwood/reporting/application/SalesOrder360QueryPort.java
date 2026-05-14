package com.northwood.reporting.application;

import com.northwood.reporting.application.dto.SalesOrder360View;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-side port over {@code reporting.sales_order_360_view}. Reporting is
 * inbox-only, so the query side has no aggregate/repository — just a
 * projection lookup.
 */
public interface SalesOrder360QueryPort {

    Optional<SalesOrder360View> findBySalesOrderId(UUID salesOrderHeaderId);

    /** All projected orders, newest activity first. Used by the demo UI list view. */
    List<SalesOrder360View> findAll();
}
