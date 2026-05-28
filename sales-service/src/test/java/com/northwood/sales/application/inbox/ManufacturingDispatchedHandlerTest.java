package com.northwood.sales.application.inbox;

import com.northwood.sales.domain.SalesOrder;
import com.northwood.sales.domain.SalesOrderRepository;
import com.northwood.sales.domain.events.SalesOrderCancellationRequested;

import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.MANUFACTURING_REQUESTED;
import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.REJECTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.northwood.manufacturing.domain.ManufacturingAggregateTypes;
import com.northwood.manufacturing.domain.events.ManufacturingDispatched;
import com.northwood.sales.application.saga.SalesOrderFulfilmentSagaManager;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.EventEnvelope;
import com.northwood.shared.application.outbox.OutboxAppender;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class ManufacturingDispatchedHandlerTest {

    private static final UUID SO = UUID.randomUUID();

    @Mock InboxPort inbox;
    @Mock SalesOrderFulfilmentSagaManager sagaManager;
    @Mock SalesOrderHeaderStatusProjection statusProjection;
    @Mock SalesOrderRepository salesOrders;
    @Mock OutboxAppender outbox;

    private final ObjectMapper json = new ObjectMapper();
    private ManufacturingDispatchedHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ManufacturingDispatchedHandler(inbox, sagaManager, statusProjection, salesOrders, outbox, json);
    }

    private EventEnvelope event(String... outcomes) {
        UUID eventId = UUID.randomUUID();
        List<ManufacturingDispatched.LineOutcome> lines = new java.util.ArrayList<>();
        int line = 10;
        for (String outcome : outcomes) {
            lines.add(new ManufacturingDispatched.LineOutcome(
                UUID.randomUUID(), line, UUID.randomUUID(), "SKU-" + line, outcome
            ));
            line += 10;
        }
        ManufacturingDispatched payload = new ManufacturingDispatched(
            eventId, UUID.randomUUID(), SO, lines, Instant.now()
        );
        return new EventEnvelope(
            eventId, ManufacturingAggregateTypes.WORK_ORDER, UUID.randomUUID(),
            ManufacturingDispatched.EVENT_TYPE, 1,
            json.writeValueAsString(payload),
            null, null, null, null, Instant.now()
        );
    }

    /** Stub the order load that the inlined cancellation-request emission performs. */
    private void stubOrderExists() {
        SalesOrder order = mock(SalesOrder.class);
        when(order.orderNumber()).thenReturn("SO-001");
        when(order.customerId()).thenReturn(UUID.randomUUID());
        when(salesOrders.findById(any())).thenReturn(Optional.of(order));
    }

    @Test void happy_path_all_accepted_no_rejection_side_effects() {
        when(sagaManager.applyManufacturingDispatched(eq(SO), eq(3), eq(3))).thenReturn(MANUFACTURING_REQUESTED);

        handler.handle(event("accepted", "accepted", "accepted"));

        verify(sagaManager).applyManufacturingDispatched(SO, 3, 3);
        verify(statusProjection, never()).markStatus(any(), any());
        verify(outbox, never()).append(any(), any());
        verify(inbox).recordProcessed(any());
    }

    @Test void all_rejected_marks_status_and_emits_cancellation() {
        when(sagaManager.applyManufacturingDispatched(eq(SO), eq(0), eq(2)))
            .thenReturn(REJECTED);
        stubOrderExists();

        handler.handle(event("rejected_no_bom", "rejected_no_bom"));

        verify(statusProjection).markStatus(SO, SalesOrder.Status.REJECTED);
        ArgumentCaptor<SalesOrderCancellationRequested> cap =
            ArgumentCaptor.forClass(SalesOrderCancellationRequested.class);
        verify(outbox).append(cap.capture(), eq(SalesOrder.AGGREGATE_TYPE));
        assertThat(cap.getValue().reason()).contains("All 2 line(s) rejected");
    }

    @Test void partial_rejection_marks_status_and_emits_cancellation() {
        // §4.2 closure: 2 of 3 accepted is still a rejection — whole order rejected,
        // cancellation request emitted so partial reservation + accepted-line WO
        // sagas get cleaned up.
        when(sagaManager.applyManufacturingDispatched(eq(SO), eq(2), eq(3)))
            .thenReturn(REJECTED);
        stubOrderExists();

        handler.handle(event("accepted", "rejected_no_bom", "accepted"));

        verify(statusProjection).markStatus(SO, SalesOrder.Status.REJECTED);
        ArgumentCaptor<SalesOrderCancellationRequested> cap =
            ArgumentCaptor.forClass(SalesOrderCancellationRequested.class);
        verify(outbox).append(cap.capture(), eq(SalesOrder.AGGREGATE_TYPE));
        assertThat(cap.getValue().reason()).contains("1 of 3 line(s) rejected");
    }

    @Test void already_processed_short_circuits() {
        EventEnvelope envelope = event("accepted");
        when(inbox.alreadyProcessed(eq(envelope.eventId()), eq(ManufacturingDispatchedHandler.CONSUMER_NAME)))
            .thenReturn(true);

        handler.handle(envelope);

        verify(sagaManager, never()).applyManufacturingDispatched(any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
        verifyNoInteractions(statusProjection);
        verifyNoInteractions(outbox);
    }
}
