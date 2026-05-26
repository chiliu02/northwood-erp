package com.northwood.manufacturing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.northwood.manufacturing.application.WorkOrderOperationService.WorkOrderNotFoundException;
import com.northwood.manufacturing.application.dto.CompleteOperationCommand;
import com.northwood.manufacturing.application.saga.MakeToOrderSagaManager;
import com.northwood.manufacturing.domain.WorkOrder;
import com.northwood.manufacturing.domain.WorkOrderId;
import com.northwood.manufacturing.domain.WorkOrderMaterial;
import com.northwood.manufacturing.domain.WorkOrderOperation;
import com.northwood.manufacturing.domain.WorkOrderRepository;
import com.northwood.manufacturing.domain.WorkOrderRepository.CompletedChild;
import com.northwood.manufacturing.domain.events.SubAssembliesConsumed;
import com.northwood.shared.application.outbox.OutboxAppender;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkOrderOperationServiceTest {

    private static final UUID SO_HEADER = UUID.randomUUID();
    private static final UUID SO_LINE = UUID.randomUUID();
    private static final UUID FG_PRODUCT = UUID.randomUUID();
    private static final UUID BOM = UUID.randomUUID();
    private static final UUID WORKCENTRE = UUID.randomUUID();

    @Mock WorkOrderRepository workOrders;
    @Mock MakeToOrderSagaManager sagaManager;
    @Mock OutboxAppender outbox;

    private WorkOrderOperationService service;

    @BeforeEach
    void setUp() {
        service = new WorkOrderOperationService(workOrders, sagaManager, outbox);
    }

    private WorkOrderOperation op(int seq) {
        return new WorkOrderOperation(
            UUID.randomUUID(), seq, "OP-" + seq, "Op " + seq, WORKCENTRE,
            BigDecimal.ZERO, new BigDecimal("30"), WorkOrder.OperationStatus.PLANNED
        );
    }

    private WorkOrderMaterial mat() {
        return new WorkOrderMaterial(
            UUID.randomUUID(), UUID.randomUUID(), "RM-X", "Material X",
            new BigDecimal("4"), BigDecimal.ZERO, WorkOrder.MaterialLineStatus.REQUIRED
        );
    }

    private WorkOrder release(UUID parentId, UUID finishedProduct, List<WorkOrderOperation> ops) {
        return WorkOrder.release(
            "WO-001", SO_HEADER, SO_LINE, parentId,
            finishedProduct, "FG-X", "Finished X",
            BOM, BigDecimal.ONE,
            List.of(mat()), ops
        );
    }

    @Nested
    class CompleteOperation {

        @Test void rejects_when_work_order_not_found() {
            WorkOrderId missing = WorkOrderId.newId();
            when(workOrders.findById(missing)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.completeOperation(
                new CompleteOperationCommand(missing.value(), 10, new BigDecimal("30"))))
                .isInstanceOf(WorkOrderNotFoundException.class);
            verify(workOrders, never()).save(any());
            verifyNoInteractions(sagaManager);
        }

        @Test void completing_middle_op_holds_at_in_progress_no_saga_advance() {
            WorkOrder wo = release(null, FG_PRODUCT, List.of(op(10), op(20)));
            wo.pullPendingEvents();
            when(workOrders.findById(wo.id())).thenReturn(Optional.of(wo));
            when(workOrders.countUnfinishedChildren(wo.id().value())).thenReturn(0);

            service.completeOperation(new CompleteOperationCommand(wo.id().value(), 10, new BigDecimal("30")));

            assertThat(wo.status()).isEqualTo(WorkOrder.Status.IN_PROGRESS);
            verify(workOrders).save(wo);
            verifyNoInteractions(sagaManager, outbox);
        }

        @Test void completing_last_op_on_leaf_wo_advances_saga_no_subassemblies_event() {
            WorkOrder wo = release(null, FG_PRODUCT, List.of(op(10)));
            wo.pullPendingEvents();
            when(workOrders.findById(wo.id())).thenReturn(Optional.of(wo));
            when(workOrders.countUnfinishedChildren(wo.id().value())).thenReturn(0);
            when(workOrders.findCompletedChildren(wo.id().value())).thenReturn(List.of());

            service.completeOperation(new CompleteOperationCommand(wo.id().value(), 10, new BigDecimal("30")));

            assertThat(wo.status()).isEqualTo(WorkOrder.Status.COMPLETED);
            verify(sagaManager).applyManufacturingCompleted(wo.id().value());
            verify(outbox, never()).append(any(), any());
        }

        @Test void unfinished_children_holds_completion_at_in_progress() {
            WorkOrder wo = release(null, FG_PRODUCT, List.of(op(10)));
            wo.pullPendingEvents();
            when(workOrders.findById(wo.id())).thenReturn(Optional.of(wo));
            when(workOrders.countUnfinishedChildren(wo.id().value())).thenReturn(2);

            service.completeOperation(new CompleteOperationCommand(wo.id().value(), 10, new BigDecimal("30")));

            assertThat(wo.status()).isEqualTo(WorkOrder.Status.IN_PROGRESS);
            verify(workOrders).save(wo);
            verifyNoInteractions(sagaManager, outbox);
        }

        @Test void parent_completion_emits_subassemblies_consumed_per_child() {
            WorkOrder parent = release(null, FG_PRODUCT, List.of(op(10)));
            parent.pullPendingEvents();
            UUID childWoA = UUID.randomUUID();
            UUID childWoB = UUID.randomUUID();
            UUID childProductA = UUID.randomUUID();
            UUID childProductB = UUID.randomUUID();
            when(workOrders.findById(parent.id())).thenReturn(Optional.of(parent));
            when(workOrders.countUnfinishedChildren(parent.id().value())).thenReturn(0);
            when(workOrders.findCompletedChildren(parent.id().value())).thenReturn(List.of(
                new CompletedChild(childWoA, childProductA, new BigDecimal("3")),
                new CompletedChild(childWoB, childProductB, new BigDecimal("2"))
            ));

            service.completeOperation(new CompleteOperationCommand(parent.id().value(), 10, new BigDecimal("30")));

            verify(sagaManager).applyManufacturingCompleted(parent.id().value());

            ArgumentCaptor<SubAssembliesConsumed> cap = ArgumentCaptor.forClass(SubAssembliesConsumed.class);
            verify(outbox).append(cap.capture(), eq(WorkOrder.AGGREGATE_TYPE));
            SubAssembliesConsumed event = cap.getValue();
            assertThat(event.eventType()).isEqualTo(SubAssembliesConsumed.EVENT_TYPE);
            assertThat(event.aggregateId()).isEqualTo(parent.id().value());
            assertThat(event.items()).hasSize(2);
            assertThat(event.items().get(0).childWorkOrderId()).isEqualTo(childWoA);
            assertThat(event.items().get(0).quantity()).isEqualByComparingTo("3");
            assertThat(event.items().get(1).childWorkOrderId()).isEqualTo(childWoB);
            assertThat(event.items().get(1).quantity()).isEqualByComparingTo("2");
        }

        @Test void child_completion_cascades_to_parent_when_siblings_done() {
            WorkOrder parent = release(null, FG_PRODUCT, List.of(op(10)));
            parent.pullPendingEvents();
            parent.completeOperation(10, new BigDecimal("30"), false);
            parent.pullPendingEvents();
            assertThat(parent.status()).isEqualTo(WorkOrder.Status.IN_PROGRESS);

            UUID parentId = parent.id().value();
            WorkOrder child = release(parentId, UUID.randomUUID(), List.of(op(10)));
            child.pullPendingEvents();

            when(workOrders.findById(child.id())).thenReturn(Optional.of(child));
            when(workOrders.countUnfinishedChildren(child.id().value())).thenReturn(0);
            when(workOrders.findCompletedChildren(child.id().value())).thenReturn(List.of());

            when(workOrders.findById(WorkOrderId.of(parentId))).thenReturn(Optional.of(parent));
            when(workOrders.countUnfinishedChildrenExcluding(parentId, child.id().value())).thenReturn(0);
            when(workOrders.findCompletedChildren(parentId)).thenReturn(List.of(
                new CompletedChild(child.id().value(), UUID.randomUUID(), new BigDecimal("1"))
            ));

            service.completeOperation(new CompleteOperationCommand(child.id().value(), 10, new BigDecimal("30")));

            assertThat(child.status()).isEqualTo(WorkOrder.Status.COMPLETED);
            assertThat(parent.status()).isEqualTo(WorkOrder.Status.COMPLETED);
            verify(sagaManager).applyManufacturingCompleted(child.id().value());
            verify(sagaManager).applyManufacturingCompleted(parentId);
            verify(workOrders).save(child);
            verify(workOrders).save(parent);
        }

        @Test void child_completion_holds_parent_when_siblings_pending() {
            WorkOrder parent = release(null, FG_PRODUCT, List.of(op(10)));
            parent.pullPendingEvents();
            parent.completeOperation(10, new BigDecimal("30"), false);
            parent.pullPendingEvents();

            UUID parentId = parent.id().value();
            WorkOrder child = release(parentId, UUID.randomUUID(), List.of(op(10)));
            child.pullPendingEvents();

            when(workOrders.findById(child.id())).thenReturn(Optional.of(child));
            when(workOrders.countUnfinishedChildren(child.id().value())).thenReturn(0);
            when(workOrders.findCompletedChildren(child.id().value())).thenReturn(List.of());

            when(workOrders.findById(WorkOrderId.of(parentId))).thenReturn(Optional.of(parent));
            when(workOrders.countUnfinishedChildrenExcluding(parentId, child.id().value())).thenReturn(1);

            service.completeOperation(new CompleteOperationCommand(child.id().value(), 10, new BigDecimal("30")));

            assertThat(child.status()).isEqualTo(WorkOrder.Status.COMPLETED);
            assertThat(parent.status()).isEqualTo(WorkOrder.Status.IN_PROGRESS);
            verify(sagaManager).applyManufacturingCompleted(child.id().value());
            verify(sagaManager, never()).applyManufacturingCompleted(parentId);
            verify(workOrders).save(child);
            verify(workOrders, never()).save(parent);
        }

        @Test void null_completed_quantity_child_is_excluded_from_consumed_emission() {
            WorkOrder parent = release(null, FG_PRODUCT, List.of(op(10)));
            parent.pullPendingEvents();
            UUID nullQtyChild = UUID.randomUUID();
            UUID validChild = UUID.randomUUID();
            UUID validProduct = UUID.randomUUID();
            when(workOrders.findById(parent.id())).thenReturn(Optional.of(parent));
            when(workOrders.countUnfinishedChildren(parent.id().value())).thenReturn(0);
            when(workOrders.findCompletedChildren(parent.id().value())).thenReturn(List.of(
                new CompletedChild(nullQtyChild, UUID.randomUUID(), null),
                new CompletedChild(validChild, validProduct, new BigDecimal("5"))
            ));

            service.completeOperation(new CompleteOperationCommand(parent.id().value(), 10, new BigDecimal("30")));

            ArgumentCaptor<SubAssembliesConsumed> cap = ArgumentCaptor.forClass(SubAssembliesConsumed.class);
            verify(outbox).append(cap.capture(), eq(WorkOrder.AGGREGATE_TYPE));
            SubAssembliesConsumed event = cap.getValue();
            assertThat(event.items()).hasSize(1);
            assertThat(event.items().get(0).childWorkOrderId()).isEqualTo(validChild);
        }

        @Test void all_null_quantities_skip_emission_entirely() {
            WorkOrder parent = release(null, FG_PRODUCT, List.of(op(10)));
            parent.pullPendingEvents();
            when(workOrders.findById(parent.id())).thenReturn(Optional.of(parent));
            when(workOrders.countUnfinishedChildren(parent.id().value())).thenReturn(0);
            when(workOrders.findCompletedChildren(parent.id().value())).thenReturn(List.of(
                new CompletedChild(UUID.randomUUID(), UUID.randomUUID(), null)
            ));

            service.completeOperation(new CompleteOperationCommand(parent.id().value(), 10, new BigDecimal("30")));

            verify(outbox, never()).append(any(), any());
        }
    }

    @Nested
    class SkipOperation {

        @Test void skip_last_op_completes_wo_and_advances_saga() {
            WorkOrder wo = release(null, FG_PRODUCT, List.of(op(10)));
            wo.pullPendingEvents();
            when(workOrders.findById(wo.id())).thenReturn(Optional.of(wo));
            when(workOrders.countUnfinishedChildren(wo.id().value())).thenReturn(0);
            when(workOrders.findCompletedChildren(wo.id().value())).thenReturn(List.of());

            service.skipOperation(wo.id().value(), 10, "no fixture available");

            assertThat(wo.status()).isEqualTo(WorkOrder.Status.COMPLETED);
            verify(sagaManager).applyManufacturingCompleted(wo.id().value());
        }

        @Test void rejects_when_work_order_not_found() {
            WorkOrderId missing = WorkOrderId.newId();
            when(workOrders.findById(missing)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.skipOperation(missing.value(), 10, "n/a"))
                .isInstanceOf(WorkOrderNotFoundException.class);
            verify(workOrders, never()).save(any());
        }
    }
}
