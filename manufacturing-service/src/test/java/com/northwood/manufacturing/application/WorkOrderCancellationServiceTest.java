package com.northwood.manufacturing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.northwood.manufacturing.application.saga.WorkOrderSagaManager;
import com.northwood.manufacturing.domain.WorkOrder;
import com.northwood.manufacturing.domain.WorkOrderId;
import com.northwood.manufacturing.domain.WorkOrderMaterial;
import com.northwood.manufacturing.domain.WorkOrderOperation;
import com.northwood.manufacturing.domain.WorkOrderRepository;
import com.northwood.manufacturing.domain.events.ManufacturingSalesOrderCancellationApplied;
import com.northwood.shared.application.outbox.OutboxAppender;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkOrderCancellationServiceTest {

    private static final UUID SO = UUID.randomUUID();
    private static final UUID SO_LINE = UUID.randomUUID();
    private static final UUID FG = UUID.randomUUID();
    private static final UUID BOM = UUID.randomUUID();
    private static final UUID WORKCENTRE = UUID.randomUUID();

    @Mock WorkOrderRepository workOrders;
    @Mock WorkOrderSagaManager sagaManager;
    @Mock OutboxAppender outbox;

    private WorkOrderCancellationService service;

    @BeforeEach
    void setUp() {
        service = new WorkOrderCancellationService(workOrders, sagaManager, outbox);
    }

    private WorkOrder activeWo() {
        WorkOrderOperation op = new WorkOrderOperation(
            UUID.randomUUID(), 10, "OP-10", "Op 10", WORKCENTRE,
            BigDecimal.ZERO, new BigDecimal("30"), WorkOrder.OperationStatus.PLANNED
        );
        WorkOrderMaterial mat = new WorkOrderMaterial(
            UUID.randomUUID(), UUID.randomUUID(), "RM-X", "Material X",
            new BigDecimal("4"), BigDecimal.ZERO, WorkOrder.MaterialLineStatus.REQUIRED
        );
        WorkOrder wo = WorkOrder.release(
            "WO-001", SO, SO_LINE, null,
            FG, "FG-X", "Finished X",
            BOM, BigDecimal.ONE,
            List.of(mat), List.of(op)
        );
        wo.pullPendingEvents();
        return wo;
    }

    private ManufacturingSalesOrderCancellationApplied capturedAck() {
        ArgumentCaptor<ManufacturingSalesOrderCancellationApplied> cap =
            ArgumentCaptor.forClass(ManufacturingSalesOrderCancellationApplied.class);
        verify(outbox).append(cap.capture(), eq(WorkOrder.AGGREGATE_TYPE));
        ManufacturingSalesOrderCancellationApplied event = cap.getValue();
        assertThat(event.eventType()).isEqualTo(ManufacturingSalesOrderCancellationApplied.EVENT_TYPE);
        assertThat(event.aggregateId()).isEqualTo(SO);
        return event;
    }

    @Test void no_active_work_orders_still_emits_ack_with_zero_count() {
        when(workOrders.findActiveIdsForSalesOrder(SO)).thenReturn(List.of());

        service.cancelForSalesOrder(SO, "customer requested");

        verify(workOrders, never()).save(any());
        verifyNoInteractions(sagaManager);
        assertThat(capturedAck().workOrdersCancelled()).isEqualTo(0);
    }

    @Test void two_active_work_orders_both_cancelled_and_saga_compensated_via_manager() {
        WorkOrder wo1 = activeWo();
        WorkOrder wo2 = activeWo();
        when(workOrders.findActiveIdsForSalesOrder(SO))
            .thenReturn(List.of(wo1.id().value(), wo2.id().value()));
        when(workOrders.findById(wo1.id())).thenReturn(Optional.of(wo1));
        when(workOrders.findById(wo2.id())).thenReturn(Optional.of(wo2));

        service.cancelForSalesOrder(SO, "customer requested");

        assertThat(wo1.status()).isEqualTo(WorkOrder.Status.CANCELLED);
        assertThat(wo2.status()).isEqualTo(WorkOrder.Status.CANCELLED);
        verify(workOrders).save(wo1);
        verify(workOrders).save(wo2);
        verify(sagaManager).cancelForWorkOrder(wo1.id().value());
        verify(sagaManager).cancelForWorkOrder(wo2.id().value());
        assertThat(capturedAck().workOrdersCancelled()).isEqualTo(2);
    }

    @Test void wo_disappeared_between_list_and_load_is_skipped_with_short_ack_count() {
        WorkOrder wo1 = activeWo();
        UUID missingId = UUID.randomUUID();
        when(workOrders.findActiveIdsForSalesOrder(SO))
            .thenReturn(List.of(wo1.id().value(), missingId));
        when(workOrders.findById(wo1.id())).thenReturn(Optional.of(wo1));
        when(workOrders.findById(WorkOrderId.of(missingId))).thenReturn(Optional.empty());

        service.cancelForSalesOrder(SO, "customer requested");

        verify(workOrders, times(1)).save(any());
        verify(sagaManager).cancelForWorkOrder(wo1.id().value());
        verify(sagaManager, never()).cancelForWorkOrder(missingId);
        assertThat(capturedAck().workOrdersCancelled()).isEqualTo(1);
    }
}
