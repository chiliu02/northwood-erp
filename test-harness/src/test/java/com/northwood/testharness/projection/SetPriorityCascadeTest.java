package com.northwood.testharness.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.northwood.manufacturing.application.WorkOrderPrioritisationService;
import com.northwood.manufacturing.domain.WorkOrder;
import com.northwood.manufacturing.domain.WorkOrderId;
import com.northwood.reporting.application.inbox.BoardWorkOrderPriorityChangedHandler;
import com.northwood.shared.application.outbox.OutboxAppender;
import com.northwood.shared.application.security.CurrentUserAccessor;
import com.northwood.testharness.inmemory.InMemoryInboxPort;
import com.northwood.testharness.inmemory.InMemoryOutboxPort;
import com.northwood.testharness.inmemory.SynchronousBus;
import com.northwood.testharness.inmemory.manufacturing.InMemoryWorkOrderRepository;
import com.northwood.testharness.inmemory.reporting.InMemoryProductionPlanningProjection;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * SetPriority projection cascade. Single-event flow, no saga: the manufacturing service writes
 * {@code WorkOrderPriorityChanged} straight to the outbox; reporting's
 * handler updates the production_planning_board projection.
 *
 * <p>Inlined harness setup (no formal kits yet for manufacturing/reporting) —
 * minimal infrastructure proves the pattern carries to non-saga flows.
 */
class SetPriorityCascadeTest {

    @Test
    void priority_change_propagates_from_manufacturing_to_reporting_projection() {
        ObjectMapper json = new ObjectMapper();
        SynchronousBus bus = new SynchronousBus();

        // Manufacturing-side
        InMemoryOutboxPort mfgOutbox = new InMemoryOutboxPort();
        InMemoryWorkOrderRepository workOrders = new InMemoryWorkOrderRepository(mfgOutbox, json);
        CurrentUserAccessor currentUser = mock(CurrentUserAccessor.class);
        when(currentUser.currentUsername()).thenReturn(Optional.empty());
        OutboxAppender appender = new OutboxAppender(mfgOutbox, json, currentUser);
        WorkOrderPrioritisationService prioritisationService =
            new WorkOrderPrioritisationService(workOrders, appender);

        // Reporting-side
        InMemoryInboxPort reportingInbox = new InMemoryInboxPort();
        InMemoryProductionPlanningProjection planning = new InMemoryProductionPlanningProjection();
        BoardWorkOrderPriorityChangedHandler handler =
            new BoardWorkOrderPriorityChangedHandler(reportingInbox, planning, json);

        bus.register(mfgOutbox);
        bus.register(handler);

        // Seed a WO so the prioritisation service's existence check passes.
        WorkOrder wo = WorkOrder.reconstitute(
            WorkOrderId.newId(), "WO-PRIO-001",
            UUID.randomUUID(), UUID.randomUUID(), null, null,
            UUID.randomUUID(), "FG-001", "Finished Good 1",
            UUID.randomUUID(), new BigDecimal("10"),
            WorkOrder.Status.RELEASED, WorkOrder.MaterialStatus.RESERVATION_PENDING,
            BigDecimal.ZERO, null, null,
            0L,
            List.of(), List.of()
        );
        workOrders.seed(wo);

        // Act: change priority.
        prioritisationService.setPriority(wo.id().value(), "urgent", "rush order");

        // Drain: handler picks up manufacturing.WorkOrderPriorityChanged → projection update.
        bus.drain();

        // Assert: priority propagated.
        assertThat(planning.priorityOf(wo.id().value()))
            .as("production_planning_board.priority projected")
            .contains("urgent");

        assertThat(mfgOutbox.findPending(100)).isEmpty();
    }
}
