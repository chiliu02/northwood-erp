package com.northwood.manufacturing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northwood.manufacturing.domain.events.OperationCompleted;
import com.northwood.manufacturing.domain.events.ReplenishmentDispatched;
import com.northwood.manufacturing.domain.events.WorkOrderCreated;
import com.northwood.manufacturing.domain.events.WorkOrderManufacturingCompleted;
import com.northwood.shared.domain.DomainEvent;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class WorkOrderTest {

    private static final UUID FG_PRODUCT = UUID.randomUUID();
    private static final UUID BOM = UUID.randomUUID();
    private static final UUID WORKCENTRE = UUID.randomUUID();
    private static final UUID SO_HEADER = UUID.randomUUID();
    private static final UUID SO_LINE = UUID.randomUUID();

    private static WorkOrderOperation op(int seq) {
        return new WorkOrderOperation(
            UUID.randomUUID(), seq, "OP-" + seq, "Op " + seq, WORKCENTRE,
            BigDecimal.ZERO, new BigDecimal("30"), WorkOrder.OperationStatus.PLANNED
        );
    }

    private static WorkOrderMaterial mat() {
        return new WorkOrderMaterial(
            UUID.randomUUID(), UUID.randomUUID(), "RM-X", "Material X",
            new BigDecimal("4"), BigDecimal.ZERO, WorkOrder.MaterialLineStatus.REQUIRED
        );
    }

    private static WorkOrder release(UUID parentId, List<WorkOrderOperation> ops) {
        return WorkOrder.release(
            "WO-001", SO_HEADER, SO_LINE, parentId,
            FG_PRODUCT, "FG-X", "Finished X",
            BOM, BigDecimal.ONE,
            List.of(mat()), ops
        );
    }

    private static WorkOrder release(List<WorkOrderOperation> ops) {
        return release(null, ops);
    }

    @Nested
    class Release {
        @Test void rejects_zero_planned_quantity() {
            assertThatThrownBy(() -> WorkOrder.release(
                "WO", SO_HEADER, SO_LINE, null,
                FG_PRODUCT, "FG-X", "X",
                BOM, BigDecimal.ZERO,
                List.of(), List.of(op(10))
            )).isInstanceOf(IllegalArgumentException.class);
        }

        @Test void rejects_no_operations() {
            assertThatThrownBy(() -> WorkOrder.release(
                "WO", SO_HEADER, SO_LINE, null,
                FG_PRODUCT, "FG-X", "X",
                BOM, BigDecimal.ONE,
                List.of(), List.of()
            )).isInstanceOf(IllegalArgumentException.class);
        }

        @Test void released_status_is_released() {
            WorkOrder wo = release(List.of(op(10)));
            assertThat(wo.status()).isEqualTo(WorkOrder.Status.RELEASED);
        }

        @Test void emits_WorkOrderCreated_with_materials_and_operations() {
            WorkOrder wo = release(List.of(op(10), op(20)));
            List<DomainEvent> events = wo.pullPendingEvents();
            assertThat(events).hasSize(1).first().isInstanceOf(WorkOrderCreated.class);
            WorkOrderCreated e = (WorkOrderCreated) events.get(0);
            assertThat(e.materials()).hasSize(1);
            assertThat(e.operations()).hasSize(2);
            assertThat(e.parentWorkOrderId()).isNull();
        }

        @Test void emits_event_with_parent_id_for_subassembly() {
            UUID parentId = UUID.randomUUID();
            WorkOrder wo = release(parentId, List.of(op(10)));
            WorkOrderCreated e = (WorkOrderCreated) wo.pullPendingEvents().get(0);
            assertThat(e.parentWorkOrderId()).isEqualTo(parentId);
        }

        @Test void make_to_order_path_leaves_replenishmentRequestId_null() {
            WorkOrder wo = release(List.of(op(10)));
            assertThat(wo.replenishmentRequestId()).isNull();
            WorkOrderCreated e = (WorkOrderCreated) wo.pullPendingEvents().get(0);
            assertThat(e.replenishmentRequestId()).isNull();
        }
    }

    @Nested
    class ReleaseForReplenishment {
        @Test void emits_both_WorkOrderCreated_and_ReplenishmentDispatched() {
            UUID replenishmentRequestId = UUID.randomUUID();
            UUID sourceSalesOrderHeaderId = UUID.randomUUID();
            WorkOrder wo = WorkOrder.releaseForReplenishment(
                "WO-REPL-001", replenishmentRequestId, sourceSalesOrderHeaderId,
                FG_PRODUCT, "FG-X", "Finished X",
                BOM, BigDecimal.ONE,
                List.of(mat()), List.of(op(10))
            );

            assertThat(wo.replenishmentRequestId()).isEqualTo(replenishmentRequestId);
            assertThat(wo.salesOrderHeaderId()).isNull();
            assertThat(wo.salesOrderLineId()).isNull();
            assertThat(wo.parentWorkOrderId()).isNull();
            assertThat(wo.status()).isEqualTo(WorkOrder.Status.RELEASED);

            List<DomainEvent> events = wo.pullPendingEvents();
            assertThat(events).hasSize(2);
            assertThat(events.get(0)).isInstanceOf(WorkOrderCreated.class);
            assertThat(events.get(1)).isInstanceOf(ReplenishmentDispatched.class);

            WorkOrderCreated created = (WorkOrderCreated) events.get(0);
            assertThat(created.aggregateId()).isEqualTo(wo.id().value());
            assertThat(created.salesOrderHeaderId()).isNull();
            assertThat(created.salesOrderLineId()).isNull();
            assertThat(created.replenishmentRequestId()).isEqualTo(replenishmentRequestId);
            // §2.37 Slice 4: the originating SO is threaded onto WorkOrderCreated.
            assertThat(created.sourceSalesOrderHeaderId()).isEqualTo(sourceSalesOrderHeaderId);

            ReplenishmentDispatched dispatched = (ReplenishmentDispatched) events.get(1);
            assertThat(dispatched.aggregateId()).isEqualTo(wo.id().value());
            assertThat(dispatched.replenishmentRequestId()).isEqualTo(replenishmentRequestId);
        }

        @Test void rejects_null_replenishmentRequestId() {
            assertThatThrownBy(() -> WorkOrder.releaseForReplenishment(
                "WO", null, /* sourceSalesOrderHeaderId */ null,
                FG_PRODUCT, "FG-X", "X", BOM, BigDecimal.ONE,
                List.of(), List.of(op(10))
            )).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class CompleteOperation {
        @Test void rejects_completing_unknown_sequence() {
            WorkOrder wo = release(List.of(op(10)));
            wo.pullPendingEvents();
            assertThatThrownBy(() -> wo.completeOperation(99, BigDecimal.TEN, true))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test void rejects_out_of_order_completion() {
            WorkOrder wo = release(List.of(op(10), op(20), op(30)));
            wo.pullPendingEvents();
            assertThatThrownBy(() -> wo.completeOperation(20, BigDecimal.TEN, true))
                .isInstanceOf(IllegalStateException.class);
        }

        @Test void rejects_completing_same_op_twice() {
            WorkOrder wo = release(List.of(op(10)));
            wo.pullPendingEvents();
            wo.completeOperation(10, new BigDecimal("30"), true);
            assertThatThrownBy(() -> wo.completeOperation(10, new BigDecimal("30"), true))
                .isInstanceOf(IllegalStateException.class);
        }

        @Test void first_op_transitions_status_to_in_progress() {
            WorkOrder wo = release(List.of(op(10), op(20)));
            wo.pullPendingEvents();
            wo.completeOperation(10, new BigDecimal("30"), true);
            assertThat(wo.status()).isEqualTo(WorkOrder.Status.IN_PROGRESS);
            assertThat(wo.actualStartAt()).isNotNull();
        }

        @Test void completing_each_op_emits_OperationCompleted() {
            WorkOrder wo = release(List.of(op(10), op(20)));
            wo.pullPendingEvents();
            wo.completeOperation(10, new BigDecimal("30"), true);
            assertThat(wo.pullPendingEvents()).hasSize(1)
                .first().isInstanceOf(OperationCompleted.class);
        }

        @Test void last_op_with_no_pending_children_completes_WO() {
            WorkOrder wo = release(List.of(op(10), op(20)));
            wo.pullPendingEvents();
            wo.completeOperation(10, new BigDecimal("30"), true);
            wo.pullPendingEvents();
            wo.completeOperation(20, new BigDecimal("30"), true);
            assertThat(wo.status()).isEqualTo(WorkOrder.Status.COMPLETED);
            assertThat(wo.completedQuantity()).isEqualByComparingTo(BigDecimal.ONE);
            assertThat(wo.actualCompletedAt()).isNotNull();
            // last completion emits both OperationCompleted + WorkOrderManufacturingCompleted
            List<DomainEvent> events = wo.pullPendingEvents();
            assertThat(events).hasSize(2);
            assertThat(events.get(1)).isInstanceOf(WorkOrderManufacturingCompleted.class);
        }

        @Test void last_op_with_pending_children_holds_WO_at_in_progress() {
            WorkOrder wo = release(List.of(op(10)));
            wo.pullPendingEvents();
            wo.completeOperation(10, new BigDecimal("30"), false /* children pending */);
            assertThat(wo.status()).isEqualTo(WorkOrder.Status.IN_PROGRESS);
            // OperationCompleted fired but NOT WorkOrderManufacturingCompleted
            List<DomainEvent> events = wo.pullPendingEvents();
            assertThat(events).hasSize(1).first().isInstanceOf(OperationCompleted.class);
        }

        @Test void completing_op_on_completed_WO_throws() {
            WorkOrder wo = release(List.of(op(10)));
            wo.pullPendingEvents();
            wo.completeOperation(10, new BigDecimal("30"), true);
            wo.pullPendingEvents();
            assertThatThrownBy(() -> wo.completeOperation(10, new BigDecimal("30"), true))
                .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    class OnChildCompleted {
        @Test void noop_when_WO_already_completed() {
            WorkOrder wo = release(List.of(op(10)));
            wo.pullPendingEvents();
            wo.completeOperation(10, new BigDecimal("30"), true);  // → completed
            wo.pullPendingEvents();
            wo.onChildCompleted(true);  // should be a no-op
            assertThat(wo.pullPendingEvents()).isEmpty();
        }

        @Test void noop_when_ops_still_pending() {
            // Parent WO with one op still pending, child completes
            WorkOrder wo = release(List.of(op(10)));
            wo.pullPendingEvents();
            wo.onChildCompleted(true);
            assertThat(wo.status()).isEqualTo(WorkOrder.Status.RELEASED);  // unchanged
            assertThat(wo.pullPendingEvents()).isEmpty();
        }

        @Test void noop_when_children_still_pending() {
            WorkOrder wo = release(List.of(op(10)));
            wo.pullPendingEvents();
            wo.completeOperation(10, new BigDecimal("30"), false);  // ops done, children still pending
            wo.pullPendingEvents();
            wo.onChildCompleted(false);  // not all children done yet
            assertThat(wo.status()).isEqualTo(WorkOrder.Status.IN_PROGRESS);
            assertThat(wo.pullPendingEvents()).isEmpty();
        }

        @Test void completes_WO_when_ops_done_and_last_child_finishes() {
            // Parent WO with ops complete but holding for children
            WorkOrder wo = release(List.of(op(10)));
            wo.pullPendingEvents();
            wo.completeOperation(10, new BigDecimal("30"), false);  // ops done, children still pending
            wo.pullPendingEvents();
            wo.onChildCompleted(true);  // last child done now
            assertThat(wo.status()).isEqualTo(WorkOrder.Status.COMPLETED);
            assertThat(wo.pullPendingEvents()).hasSize(1)
                .first().isInstanceOf(WorkOrderManufacturingCompleted.class);
        }
    }

    @Nested
    class ApplyReservationOutcome {
        @Test void starts_at_reservation_pending() {
            WorkOrder wo = release(List.of(op(10)));
            assertThat(wo.materialStatus()).isEqualTo(WorkOrder.MaterialStatus.RESERVATION_PENDING);
        }

        @Test void reserved_transitions_material_status() {
            WorkOrder wo = release(List.of(op(10)));
            wo.applyReservationOutcome(WorkOrder.MaterialStatus.RESERVED);
            assertThat(wo.materialStatus()).isEqualTo(WorkOrder.MaterialStatus.RESERVED);
        }

        @Test void partially_reserved_transitions_material_status() {
            WorkOrder wo = release(List.of(op(10)));
            wo.applyReservationOutcome(WorkOrder.MaterialStatus.PARTIALLY_RESERVED);
            assertThat(wo.materialStatus()).isEqualTo(WorkOrder.MaterialStatus.PARTIALLY_RESERVED);
        }

        @Test void shortage_transitions_material_status() {
            WorkOrder wo = release(List.of(op(10)));
            wo.applyReservationOutcome(WorkOrder.MaterialStatus.SHORTAGE);
            assertThat(wo.materialStatus()).isEqualTo(WorkOrder.MaterialStatus.SHORTAGE);
        }

        @Test void emits_no_event() {
            WorkOrder wo = release(List.of(op(10)));
            wo.pullPendingEvents();
            wo.applyReservationOutcome(WorkOrder.MaterialStatus.RESERVED);
            assertThat(wo.pullPendingEvents()).isEmpty();
        }

        @Test void rejects_value_outside_happy_path_set() {
            // The enum type itself ensures only schema-allowed values reach
            // this method (compile-time). The aggregate's internal guard
            // narrows further to the three expected outcomes — NOT_CHECKED
            // and ISSUED are valid schema values but never legal here.
            WorkOrder wo = release(List.of(op(10)));
            assertThatThrownBy(() -> wo.applyReservationOutcome(WorkOrder.MaterialStatus.NOT_CHECKED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown material status");
        }

        @Test void rejects_null_value() {
            WorkOrder wo = release(List.of(op(10)));
            assertThatThrownBy(() -> wo.applyReservationOutcome(null))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test void rejects_reservation_pending_as_apply_target() {
            // The "pending" state is the initial value only — projecting it
            // back from an event would be nonsensical (the reservation by
            // definition isn't pending any more once the outcome event fires).
            WorkOrder wo = release(List.of(op(10)));
            assertThatThrownBy(() -> wo.applyReservationOutcome(WorkOrder.MaterialStatus.RESERVATION_PENDING))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test void noop_on_completed() {
            WorkOrder wo = release(List.of(op(10)));
            wo.pullPendingEvents();
            wo.completeOperation(10, new BigDecimal("30"), true);
            wo.pullPendingEvents();
            wo.applyReservationOutcome(WorkOrder.MaterialStatus.SHORTAGE);
            // material_status whatever it was at completion — not 'shortage'
            assertThat(wo.materialStatus()).isNotEqualTo(WorkOrder.MaterialStatus.SHORTAGE);
        }

        @Test void noop_on_same_value() {
            WorkOrder wo = release(List.of(op(10)));
            wo.pullPendingEvents();  // drain the WorkOrderCreated from release
            wo.applyReservationOutcome(WorkOrder.MaterialStatus.RESERVED);
            wo.applyReservationOutcome(WorkOrder.MaterialStatus.RESERVED);
            assertThat(wo.materialStatus()).isEqualTo(WorkOrder.MaterialStatus.RESERVED);
            // No event was emitted (none ever is for this projection).
            assertThat(wo.pullPendingEvents()).isEmpty();
        }
    }

    @Nested
    class OperationInvariants {
        @Test void rejects_zero_planned_run_minutes() {
            assertThatThrownBy(() -> new WorkOrderOperation(
                UUID.randomUUID(), 10, "OP-10", "x", WORKCENTRE,
                BigDecimal.ZERO, BigDecimal.ZERO, WorkOrder.OperationStatus.PLANNED
            )).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class MaterialInvariants {
        @Test void rejects_negative_required_quantity() {
            assertThatThrownBy(() -> new WorkOrderMaterial(
                UUID.randomUUID(), UUID.randomUUID(), "RM-X", "X",
                new BigDecimal("-1"), BigDecimal.ZERO, WorkOrder.MaterialLineStatus.REQUIRED
            )).isInstanceOf(IllegalArgumentException.class);
        }

        @Test void allows_zero_required_quantity() {
            new WorkOrderMaterial(
                UUID.randomUUID(), UUID.randomUUID(), "RM-X", "X",
                BigDecimal.ZERO, BigDecimal.ZERO, WorkOrder.MaterialLineStatus.REQUIRED
            );
        }
    }
}
