package com.northwood.reporting.infrastructure.persistence;

import com.northwood.inventory.domain.events.RawMaterialsReserved;
import com.northwood.manufacturing.domain.events.WorkOrderMaterialStatuses;
import com.northwood.manufacturing.domain.events.WorkOrderStatuses;
import com.northwood.reporting.application.inbox.ProductionPlanningProjection;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reporting-side projection of the manufacturing work-order lifecycle.
 *
 * <p>The {@code work_order_status} and {@code material_status} columns mirror
 * the wire-format values produced by manufacturing's {@code WorkOrder.Status}
 * and {@code WorkOrder.MaterialStatus} enums. Per the cross-service contract
 * rule, this projection references the dedicated constants holders
 * ({@link WorkOrderStatuses}, {@link WorkOrderMaterialStatuses}) — same wire
 * format, no cross-service domain import.
 *
 * <p>SQL literals in WHERE/CASE conditions (e.g. {@code WHEN current_status
 * IN ('released', 'pending')}) are left as-is: they're engine-side comparisons
 * against current column values, not statuses this code writes.
 */
@Repository
public class JdbcProductionPlanningProjection implements ProductionPlanningProjection {

    private static final Logger log = LoggerFactory.getLogger(JdbcProductionPlanningProjection.class);

    /** Sentinel zero-UUID used for stub finished_product_id. */
    private static final UUID STUB_PRODUCT_ID = new UUID(0L, 0L);

    private final JdbcTemplate jdbc;

    public JdbcProductionPlanningProjection(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void createFromWorkOrder(
        UUID workOrderId,
        String workOrderNumber,
        UUID salesOrderHeaderId,
        UUID finishedProductId,
        String finishedProductSku,
        String finishedProductName,
        BigDecimal plannedQuantity,
        Instant occurredAt
    ) {
        BigDecimal planned = plannedQuantity == null ? BigDecimal.ZERO : plannedQuantity;
        jdbc.update("""
            INSERT INTO reporting.production_planning_board (
                work_order_id, work_order_number,
                sales_order_header_id,
                finished_product_id, finished_product_sku, finished_product_name,
                planned_quantity, completed_quantity,
                work_order_status, material_status,
                shortage_materials_count, open_purchase_orders_count,
                priority, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, 0,
                      ?, 'pending',
                      0, 0, 'normal', now())
            ON CONFLICT (work_order_id) DO UPDATE SET
                work_order_number = EXCLUDED.work_order_number,
                sales_order_header_id = EXCLUDED.sales_order_header_id,
                finished_product_id = EXCLUDED.finished_product_id,
                finished_product_sku = EXCLUDED.finished_product_sku,
                finished_product_name = EXCLUDED.finished_product_name,
                planned_quantity = EXCLUDED.planned_quantity,
                work_order_status = CASE
                    WHEN production_planning_board.work_order_status = 'pending'
                        THEN EXCLUDED.work_order_status
                    ELSE production_planning_board.work_order_status
                END,
                updated_at = now()
            """,
            workOrderId, workOrderNumber,
            salesOrderHeaderId,
            finishedProductId, finishedProductSku, finishedProductName,
            planned,
            WorkOrderStatuses.RELEASED
        );
        log.info("seeded production_planning_board for WO {} ({}) qty={}",
            workOrderNumber, workOrderId, planned);
    }

    @Override
    @Transactional
    public void recordOperationCompleted(UUID workOrderId, Instant occurredAt) {
        jdbc.update("""
            INSERT INTO reporting.production_planning_board (
                work_order_id, work_order_number,
                finished_product_id, finished_product_sku, finished_product_name,
                planned_quantity, completed_quantity,
                work_order_status, material_status,
                shortage_materials_count, open_purchase_orders_count,
                priority, updated_at
            ) VALUES (?, '(pending)', ?, '(pending)', '(pending)',
                      0, 0, ?, 'pending',
                      0, 0, 'normal', now())
            ON CONFLICT (work_order_id) DO UPDATE SET
                work_order_status = CASE
                    WHEN production_planning_board.work_order_status IN ('released', 'pending')
                        THEN ?
                    ELSE production_planning_board.work_order_status
                END,
                updated_at = now()
            """,
            workOrderId, STUB_PRODUCT_ID,
            WorkOrderStatuses.IN_PROGRESS, WorkOrderStatuses.IN_PROGRESS
        );
    }

    @Override
    @Transactional
    public void recordWorkOrderCompleted(
        UUID workOrderId,
        BigDecimal completedQuantity,
        Instant occurredAt
    ) {
        BigDecimal completed = completedQuantity == null ? BigDecimal.ZERO : completedQuantity;
        jdbc.update("""
            INSERT INTO reporting.production_planning_board (
                work_order_id, work_order_number,
                finished_product_id, finished_product_sku, finished_product_name,
                planned_quantity, completed_quantity,
                work_order_status, material_status,
                shortage_materials_count, open_purchase_orders_count,
                priority, updated_at
            ) VALUES (?, '(pending)', ?, '(pending)', '(pending)',
                      ?, ?, ?, 'pending',
                      0, 0, 'normal', now())
            ON CONFLICT (work_order_id) DO UPDATE SET
                work_order_status = ?,
                completed_quantity = EXCLUDED.completed_quantity,
                updated_at = now()
            """,
            workOrderId, STUB_PRODUCT_ID, completed, completed,
            WorkOrderStatuses.COMPLETED, WorkOrderStatuses.COMPLETED
        );
    }

    @Override
    @Transactional
    public void recordRawMaterialsReserved(
        UUID workOrderId,
        String reservationStatus,
        Instant occurredAt
    ) {
        String materialStatus = switch (reservationStatus == null ? "" : reservationStatus) {
            case RawMaterialsReserved.STATUS_RESERVED -> WorkOrderMaterialStatuses.RESERVED;
            case RawMaterialsReserved.STATUS_PARTIALLY_RESERVED -> WorkOrderMaterialStatuses.PARTIALLY_RESERVED;
            case RawMaterialsReserved.STATUS_FAILED -> WorkOrderMaterialStatuses.SHORTAGE;
            default -> "pending";
        };
        boolean fullyReserved = WorkOrderMaterialStatuses.RESERVED.equals(materialStatus);
        jdbc.update("""
            INSERT INTO reporting.production_planning_board (
                work_order_id, work_order_number,
                finished_product_id, finished_product_sku, finished_product_name,
                planned_quantity, completed_quantity,
                work_order_status, material_status,
                shortage_materials_count, open_purchase_orders_count,
                priority, updated_at
            ) VALUES (?, '(pending)', ?, '(pending)', '(pending)',
                      0, 0, 'pending', ?,
                      0, 0, 'normal', now())
            ON CONFLICT (work_order_id) DO UPDATE SET
                material_status = EXCLUDED.material_status,
                shortage_materials_count = CASE
                    WHEN ? THEN 0
                    ELSE production_planning_board.shortage_materials_count
                END,
                shortage_summary = CASE
                    WHEN ? THEN NULL
                    ELSE production_planning_board.shortage_summary
                END,
                updated_at = now()
            """,
            workOrderId, STUB_PRODUCT_ID, materialStatus,
            fullyReserved, fullyReserved
        );
    }

    @Override
    @Transactional
    public void recordPriorityChanged(UUID workOrderId, String priority, Instant occurredAt) {
        jdbc.update("""
            INSERT INTO reporting.production_planning_board (
                work_order_id, work_order_number,
                finished_product_id, finished_product_sku, finished_product_name,
                planned_quantity, completed_quantity,
                work_order_status, material_status,
                shortage_materials_count, open_purchase_orders_count,
                priority, updated_at
            ) VALUES (?, '(pending)', ?, '(pending)', '(pending)',
                      0, 0, 'pending', 'pending',
                      0, 0, ?, now())
            ON CONFLICT (work_order_id) DO UPDATE SET
                priority = EXCLUDED.priority,
                updated_at = now()
            """,
            workOrderId, STUB_PRODUCT_ID, priority
        );
    }

    @Override
    @Transactional
    public void recordShortageDetected(
        UUID workOrderId,
        int shortageCount,
        String shortageSummary,
        Instant occurredAt
    ) {
        jdbc.update("""
            INSERT INTO reporting.production_planning_board (
                work_order_id, work_order_number,
                finished_product_id, finished_product_sku, finished_product_name,
                planned_quantity, completed_quantity,
                work_order_status, material_status,
                shortage_materials_count, shortage_summary,
                open_purchase_orders_count,
                priority, updated_at
            ) VALUES (?, '(pending)', ?, '(pending)', '(pending)',
                      0, 0, 'pending', ?,
                      ?, ?, 0, 'normal', now())
            ON CONFLICT (work_order_id) DO UPDATE SET
                material_status = ?,
                shortage_materials_count = EXCLUDED.shortage_materials_count,
                shortage_summary = EXCLUDED.shortage_summary,
                updated_at = now()
            """,
            workOrderId, STUB_PRODUCT_ID,
            WorkOrderMaterialStatuses.SHORTAGE,
            shortageCount, shortageSummary,
            WorkOrderMaterialStatuses.SHORTAGE
        );
    }

    @Override
    @Transactional
    public void setOpenPoCount(UUID workOrderId, int count, Instant occurredAt) {
        jdbc.update("""
            INSERT INTO reporting.production_planning_board (
                work_order_id, work_order_number,
                finished_product_id, finished_product_sku, finished_product_name,
                planned_quantity, completed_quantity,
                work_order_status, material_status,
                shortage_materials_count, open_purchase_orders_count,
                priority, updated_at
            ) VALUES (?, '(pending)', ?, '(pending)', '(pending)',
                      0, 0, 'pending', 'pending',
                      0, ?, 'normal', now())
            ON CONFLICT (work_order_id) DO UPDATE SET
                open_purchase_orders_count = EXCLUDED.open_purchase_orders_count,
                updated_at = now()
            """,
            workOrderId, STUB_PRODUCT_ID, count
        );
    }
}
