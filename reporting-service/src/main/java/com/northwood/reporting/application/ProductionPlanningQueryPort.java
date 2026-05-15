package com.northwood.reporting.application;

import com.northwood.reporting.application.dto.ProductionPlanningView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductionPlanningQueryPort {

    Optional<ProductionPlanningView> findByWorkOrderId(UUID workOrderId);

    /**
     * All work orders across every status (including {@code cancelled}),
     * newest activity first. Two SPA consumers rely on this:
     * <ul>
     *   <li>{@code /production-board} (3-lane Kanban) filters client-side
     *       to {@code released} / {@code in_progress} / {@code completed},
     *       so a cancelled row simply doesn't render.</li>
     *   <li>{@code /work-orders} (table view) offers a status dropdown that
     *       includes {@code cancelled} — it needs the row to surface so
     *       Linda can audit cancellations.</li>
     * </ul>
     * Filtering server-side here would silently break the table view's
     * cancelled filter; keep the endpoint behaviour as "give me everything"
     * and let each consumer slice client-side.
     */
    List<ProductionPlanningView> findAll();
}
