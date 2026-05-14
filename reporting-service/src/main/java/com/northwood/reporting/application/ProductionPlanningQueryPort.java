package com.northwood.reporting.application;

import com.northwood.reporting.application.dto.ProductionPlanningView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductionPlanningQueryPort {

    Optional<ProductionPlanningView> findByWorkOrderId(UUID workOrderId);

    /** All open work orders, newest activity first. Used by the demo UI list view. */
    List<ProductionPlanningView> findAll();
}
