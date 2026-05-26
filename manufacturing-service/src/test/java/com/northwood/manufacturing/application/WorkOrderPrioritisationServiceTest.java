package com.northwood.manufacturing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.northwood.manufacturing.application.WorkOrderOperationService.WorkOrderNotFoundException;
import com.northwood.manufacturing.domain.WorkOrder;
import com.northwood.manufacturing.domain.WorkOrderId;
import com.northwood.manufacturing.domain.WorkOrderMaterial;
import com.northwood.manufacturing.domain.WorkOrderOperation;
import com.northwood.manufacturing.domain.WorkOrderRepository;
import com.northwood.manufacturing.domain.events.WorkOrderPriorityChanged;
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
class WorkOrderPrioritisationServiceTest {

    @Mock WorkOrderRepository workOrders;
    @Mock OutboxAppender outbox;

    private WorkOrderPrioritisationService service;

    @BeforeEach
    void setUp() {
        service = new WorkOrderPrioritisationService(workOrders, outbox);
    }

    private WorkOrder existingWo() {
        WorkOrderOperation op = new WorkOrderOperation(
            UUID.randomUUID(), 10, "OP-10", "Op", UUID.randomUUID(),
            BigDecimal.ZERO, new BigDecimal("30"), WorkOrder.OperationStatus.PLANNED
        );
        WorkOrderMaterial mat = new WorkOrderMaterial(
            UUID.randomUUID(), UUID.randomUUID(), "RM", "Material", new BigDecimal("1"), BigDecimal.ZERO, WorkOrder.MaterialLineStatus.REQUIRED
        );
        return WorkOrder.release(
            "WO-001", UUID.randomUUID(), UUID.randomUUID(), null,
            UUID.randomUUID(), "FG", "Finished",
            UUID.randomUUID(), BigDecimal.ONE,
            List.of(mat), List.of(op)
        );
    }

    @Test void happy_path_emits_priority_changed_event() {
        WorkOrder wo = existingWo();
        when(workOrders.findById(wo.id())).thenReturn(Optional.of(wo));

        service.setPriority(wo.id().value(), "urgent", "VIP customer");

        ArgumentCaptor<WorkOrderPriorityChanged> cap = ArgumentCaptor.forClass(WorkOrderPriorityChanged.class);
        verify(outbox).append(cap.capture(), eq(WorkOrder.AGGREGATE_TYPE));
        WorkOrderPriorityChanged event = cap.getValue();
        assertThat(event.eventType()).isEqualTo(WorkOrderPriorityChanged.EVENT_TYPE);
        assertThat(event.priority()).isEqualTo("urgent");
        assertThat(event.reason()).isEqualTo("VIP customer");
    }

    @Test void rejects_unknown_priority() {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> service.setPriority(id, "extreme", "test"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("priority must be one of");
        verify(outbox, never()).append(any(), any());
    }

    @Test void rejects_unknown_work_order() {
        UUID id = UUID.randomUUID();
        when(workOrders.findById(WorkOrderId.of(id))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.setPriority(id, "high", "test"))
            .isInstanceOf(WorkOrderNotFoundException.class);
        verify(outbox, never()).append(any(), any());
    }

    @Test void all_four_allowed_priorities_accepted() {
        WorkOrder wo = existingWo();
        when(workOrders.findById(wo.id())).thenReturn(Optional.of(wo));

        for (String p : List.of("low", "normal", "high", "urgent")) {
            service.setPriority(wo.id().value(), p, "rotation");
        }

        verify(outbox, times(4)).append(any(), any());
    }
}
