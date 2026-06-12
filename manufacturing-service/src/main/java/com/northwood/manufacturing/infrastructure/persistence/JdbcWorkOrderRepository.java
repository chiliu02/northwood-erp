package com.northwood.manufacturing.infrastructure.persistence;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.northwood.manufacturing.domain.WorkOrder;
import com.northwood.manufacturing.domain.WorkOrderId;
import com.northwood.manufacturing.domain.WorkOrderMaterial;
import com.northwood.manufacturing.domain.WorkOrderOperation;
import com.northwood.manufacturing.domain.WorkOrderRepository;
import com.northwood.shared.domain.DomainEvent;
import com.northwood.shared.application.messaging.OutboxTraceHeaders;
import com.northwood.shared.application.security.CurrentUserAccessor;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcWorkOrderRepository implements WorkOrderRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final CurrentUserAccessor currentUser;

    public JdbcWorkOrderRepository(JdbcTemplate jdbc, ObjectMapper json, CurrentUserAccessor currentUser) {
        this.jdbc = jdbc;
        this.json = json;
        this.currentUser = currentUser;
    }

    @Override
    public Optional<WorkOrder> findById(WorkOrderId id) {
        try {
            HeaderRow header = jdbc.queryForObject("""
                SELECT work_order_id, work_order_number,
                       sales_order_header_id, sales_order_line_id,
                       replenishment_request_id, parent_work_order_id,
                       finished_product_id, finished_product_sku, finished_product_name,
                       bom_header_id, planned_quantity, completed_quantity,
                       status, material_status,
                       actual_start_at, actual_completed_at, version
                FROM manufacturing.work_order
                WHERE work_order_id = ?
                """, HEADER_MAPPER, id.value());
            List<WorkOrderMaterial> materials = jdbc.query("""
                SELECT work_order_material_id, component_product_id, component_sku, component_name,
                       required_quantity, unit_cost, status
                FROM manufacturing.work_order_material
                WHERE work_order_id = ?
                ORDER BY component_sku
                """, MATERIAL_MAPPER, id.value());
            List<WorkOrderOperation> operations = jdbc.query("""
                SELECT work_order_operation_id, operation_sequence, operation_code, description,
                       work_center_id, planned_setup_minutes, planned_run_minutes,
                       status, actual_minutes, started_at, completed_at
                FROM manufacturing.work_order_operation
                WHERE work_order_id = ?
                ORDER BY operation_sequence
                """, OPERATION_MAPPER, id.value());
            return Optional.of(WorkOrder.reconstitute(
                WorkOrderId.of(header.id),
                header.workOrderNumber,
                header.salesOrderHeaderId, header.salesOrderLineId,
                header.replenishmentRequestId, header.parentWorkOrderId,
                header.finishedProductId, header.finishedProductSku, header.finishedProductName,
                header.bomHeaderId, header.plannedQuantity,
                WorkOrder.Status.fromCode(header.status), WorkOrder.MaterialStatus.fromCode(header.materialStatus),
                header.completedQuantity, header.actualStartAt, header.actualCompletedAt,
                header.version,
                materials, operations
            ));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public void save(WorkOrder workOrder) {
        String actor = currentUser.currentUsername().orElse(null);
        if (workOrder.version() == 0L) {
            insert(workOrder, actor);
        } else {
            update(workOrder, actor);
        }
        for (DomainEvent event : workOrder.pullPendingEvents()) {
            writeOutbox(event, actor);
        }
    }

    @Override
    public int countUnfinishedChildren(UUID parentWorkOrderId) {
        Integer count = jdbc.queryForObject("""
            SELECT COUNT(*) FROM manufacturing.work_order
            WHERE parent_work_order_id = ?
              AND status NOT IN ('completed', 'closed', 'cancelled')
            """, Integer.class, parentWorkOrderId);
        return count == null ? 0 : count;
    }

    @Override
    public int countUnfinishedChildrenExcluding(UUID parentWorkOrderId, UUID excludeChildId) {
        Integer count = jdbc.queryForObject("""
            SELECT COUNT(*) FROM manufacturing.work_order
            WHERE parent_work_order_id = ?
              AND work_order_id <> ?
              AND status NOT IN ('completed', 'closed', 'cancelled')
            """, Integer.class, parentWorkOrderId, excludeChildId);
        return count == null ? 0 : count;
    }

    @Override
    public List<CompletedChild> findCompletedChildren(UUID parentWorkOrderId) {
        return jdbc.query("""
            SELECT work_order_id, finished_product_id, completed_quantity
            FROM manufacturing.work_order
            WHERE parent_work_order_id = ?
              AND status = 'completed'
            """,
            (rs, n) -> new CompletedChild(
                rs.getObject("work_order_id", UUID.class),
                rs.getObject("finished_product_id", UUID.class),
                rs.getBigDecimal("completed_quantity")
            ),
            parentWorkOrderId
        );
    }

    private void insert(WorkOrder workOrder, String actor) {
        jdbc.update("""
            INSERT INTO manufacturing.work_order (
                work_order_id, work_order_number,
                sales_order_header_id, sales_order_line_id,
                replenishment_request_id, parent_work_order_id,
                finished_product_id, finished_product_sku, finished_product_name,
                bom_header_id, planned_quantity, status, material_status, version,
                created_by, last_modified_by
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            workOrder.id().value(), workOrder.workOrderNumber(),
            workOrder.salesOrderHeaderId(), workOrder.salesOrderLineId(),
            workOrder.replenishmentRequestId(), workOrder.parentWorkOrderId(),
            workOrder.finishedProductId(), workOrder.finishedProductSku(), workOrder.finishedProductName(),
            workOrder.bomHeaderId(), workOrder.plannedQuantity(),
            workOrder.status().code(), workOrder.materialStatus().code(),
            1L,
            actor, actor
        );
        for (WorkOrderMaterial m : workOrder.materials()) {
            jdbc.update("""
                INSERT INTO manufacturing.work_order_material (
                    work_order_material_id, work_order_id, component_product_id,
                    component_sku, component_name, required_quantity,
                    reserved_quantity, issued_quantity, shortage_quantity,
                    unit_cost, total_cost, status
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                m.id(), workOrder.id().value(), m.componentProductId(),
                m.componentSku(), m.componentName(), m.requiredQuantity(),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                m.unitCost(), BigDecimal.ZERO, m.status().code()
            );
        }
        for (WorkOrderOperation op : workOrder.operations()) {
            jdbc.update("""
                INSERT INTO manufacturing.work_order_operation (
                    work_order_operation_id, work_order_id, operation_sequence,
                    operation_code, description, work_center_id,
                    planned_setup_minutes, planned_run_minutes, status
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                op.id(), workOrder.id().value(), op.operationSequence(),
                op.operationCode(), op.description(), op.workCenterId(),
                op.plannedSetupMinutes(), op.plannedRunMinutes(), op.status().code()
            );
        }
    }

    private void update(WorkOrder workOrder, String actor) {
        int rows = jdbc.update("""
            UPDATE manufacturing.work_order SET
                status = ?, material_status = ?, completed_quantity = ?,
                actual_start_at = ?, actual_completed_at = ?,
                version = version + 1,
                last_modified_by = ?
            WHERE work_order_id = ? AND version = ?
            """,
            workOrder.status().code(), workOrder.materialStatus().code(), workOrder.completedQuantity(),
            workOrder.actualStartAt() == null ? null : Timestamp.from(workOrder.actualStartAt()),
            workOrder.actualCompletedAt() == null ? null : Timestamp.from(workOrder.actualCompletedAt()),
            actor,
            workOrder.id().value(), workOrder.version()
        );
        if (rows == 0) {
            throw new OptimisticLockingFailureException(
                "Work order " + workOrder.id().value() + " was modified by another transaction"
            );
        }
        for (WorkOrderOperation op : workOrder.operations()) {
            jdbc.update("""
                UPDATE manufacturing.work_order_operation SET
                    status = ?, actual_minutes = ?,
                    started_at = ?, completed_at = ?
                WHERE work_order_operation_id = ?
                """,
                op.status().code(), op.actualMinutes(),
                op.startedAt() == null ? null : Timestamp.from(op.startedAt()),
                op.completedAt() == null ? null : Timestamp.from(op.completedAt()),
                op.id()
            );
        }
    }

    private void writeOutbox(DomainEvent event, String actor) {
        try {
            jdbc.update("""
                INSERT INTO manufacturing.outbox_message (
                    outbox_message_id, aggregate_type, aggregate_id,
                    event_type, event_version, payload, headers, status, actor_user_id
                ) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, 'pending', ?)
                """,
                event.eventId(),
                WorkOrder.AGGREGATE_TYPE,
                event.aggregateId(),
                event.eventType(),
                event.eventVersion(),
                json.writeValueAsString(event), OutboxTraceHeaders.currentJson(),
                actor
            );
        } catch (JacksonException e) {
            throw new IllegalStateException("Cannot serialise event " + event.eventType(), e);
        }
    }

    private record HeaderRow(
        UUID id, String workOrderNumber,
        UUID salesOrderHeaderId, UUID salesOrderLineId,
        UUID replenishmentRequestId, UUID parentWorkOrderId,
        UUID finishedProductId, String finishedProductSku, String finishedProductName,
        UUID bomHeaderId, BigDecimal plannedQuantity, BigDecimal completedQuantity,
        String status, String materialStatus,
        Instant actualStartAt, Instant actualCompletedAt, long version
    ) {}

    private static final RowMapper<HeaderRow> HEADER_MAPPER = (rs, n) -> {
        Timestamp startAt = rs.getTimestamp("actual_start_at");
        Timestamp completedAt = rs.getTimestamp("actual_completed_at");
        return new HeaderRow(
            rs.getObject("work_order_id", UUID.class),
            rs.getString("work_order_number"),
            rs.getObject("sales_order_header_id", UUID.class),
            rs.getObject("sales_order_line_id", UUID.class),
            rs.getObject("replenishment_request_id", UUID.class),
            rs.getObject("parent_work_order_id", UUID.class),
            rs.getObject("finished_product_id", UUID.class),
            rs.getString("finished_product_sku"),
            rs.getString("finished_product_name"),
            rs.getObject("bom_header_id", UUID.class),
            rs.getBigDecimal("planned_quantity"),
            rs.getBigDecimal("completed_quantity"),
            rs.getString("status"),
            rs.getString("material_status"),
            startAt == null ? null : startAt.toInstant(),
            completedAt == null ? null : completedAt.toInstant(),
            rs.getLong("version")
        );
    };

    private static final RowMapper<WorkOrderMaterial> MATERIAL_MAPPER = (rs, n) -> new WorkOrderMaterial(
        rs.getObject("work_order_material_id", UUID.class),
        rs.getObject("component_product_id", UUID.class),
        rs.getString("component_sku"),
        rs.getString("component_name"),
        rs.getBigDecimal("required_quantity"),
        rs.getBigDecimal("unit_cost"),
        WorkOrder.MaterialLineStatus.fromCode(rs.getString("status"))
    );

    private static final RowMapper<WorkOrderOperation> OPERATION_MAPPER = (rs, n) -> {
        Timestamp startedAt = rs.getTimestamp("started_at");
        Timestamp completedAt = rs.getTimestamp("completed_at");
        return new WorkOrderOperation(
            rs.getObject("work_order_operation_id", UUID.class),
            rs.getInt("operation_sequence"),
            rs.getString("operation_code"),
            rs.getString("description"),
            rs.getObject("work_center_id", UUID.class),
            rs.getBigDecimal("planned_setup_minutes"),
            rs.getBigDecimal("planned_run_minutes"),
            WorkOrder.OperationStatus.fromCode(rs.getString("status")),
            rs.getBigDecimal("actual_minutes"),
            startedAt == null ? null : startedAt.toInstant(),
            completedAt == null ? null : completedAt.toInstant()
        );
    };
}
