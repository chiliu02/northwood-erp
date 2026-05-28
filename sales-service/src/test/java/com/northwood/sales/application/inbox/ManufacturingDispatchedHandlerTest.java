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
import com.northwood.sales.application.saga.SalesOrderFulfilmentSagaManager.PurchasingDivergence;
import com.northwood.sales.application.saga.SalesOrderLineSnapshotPort;
import com.northwood.sales.application.saga.SalesOrderLineSnapshotPort.LineSnapshot;
import com.northwood.sales.domain.events.SalesOrderPurchasingRequested;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.EventEnvelope;
import com.northwood.shared.application.outbox.OutboxAppender;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    @Mock SalesOrderLineSnapshotPort lineSnapshots;
    @Mock OutboxAppender outbox;

    private final ObjectMapper json = new ObjectMapper();
    private ManufacturingDispatchedHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ManufacturingDispatchedHandler(inbox, sagaManager, statusProjection, salesOrders, lineSnapshots, outbox, json);
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

    @Test void all_rejected_not_manufactured_reroutes_to_purchasing_emits_event() {
        // §2.36: every line came back rejected_not_manufactured (purchased-only).
        // Handler should consult the manager's reroute method (returns the
        // stashed shortage map), emit sales.SalesOrderPurchasingRequested,
        // and SKIP the legacy applyManufacturingDispatched/REJECTED path.
        Map<Integer, BigDecimal> shortage = new LinkedHashMap<>();
        shortage.put(10, new BigDecimal("3"));
        shortage.put(20, new BigDecimal("5"));
        when(sagaManager.applyManufacturingDispatchedReroutingToPurchasing(eq(SO), any()))
            .thenReturn(Optional.of(new PurchasingDivergence(shortage)));
        when(lineSnapshots.findLines(eq(SO))).thenReturn(List.of());  // fall back to payload sku/name

        handler.handle(event("rejected_not_manufactured", "rejected_not_manufactured"));

        // Legacy applyManufacturingDispatched NOT invoked when reroute fires.
        verify(sagaManager, never()).applyManufacturingDispatched(any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
        verify(statusProjection, never()).markStatus(any(), any());

        ArgumentCaptor<SalesOrderPurchasingRequested> cap =
            ArgumentCaptor.forClass(SalesOrderPurchasingRequested.class);
        verify(outbox).append(cap.capture(), any());
        SalesOrderPurchasingRequested emitted = cap.getValue();
        assertThat(emitted.salesOrderHeaderId()).isEqualTo(SO);
        assertThat(emitted.lines()).hasSize(2);
        assertThat(emitted.lines())
            .extracting(SalesOrderPurchasingRequested.RequestedLine::shortageQuantity)
            .containsExactlyInAnyOrder(new BigDecimal("3"), new BigDecimal("5"));
    }

    @Test void reroute_declined_falls_back_to_legacy_path() {
        // Manager returns Optional.empty() — e.g. saga not in MANUFACTURING_REQUESTED
        // (a late redelivery). Handler must fall through to the legacy
        // applyManufacturingDispatched path so the existing semantics still apply.
        when(sagaManager.applyManufacturingDispatchedReroutingToPurchasing(eq(SO), any()))
            .thenReturn(Optional.empty());
        when(sagaManager.applyManufacturingDispatched(eq(SO), eq(0), eq(2)))
            .thenReturn(REJECTED);
        stubOrderExists();

        handler.handle(event("rejected_not_manufactured", "rejected_not_manufactured"));

        verify(sagaManager).applyManufacturingDispatched(SO, 0, 2);
        verify(statusProjection).markStatus(SO, SalesOrder.Status.REJECTED);
    }

    @Test void mixed_rejection_outcomes_take_legacy_rejection_path() {
        // §2.36 scope-limit: only ALL-rejected_not_manufactured reroutes.
        // A mix of rejected_no_bom + rejected_not_manufactured falls through
        // to the existing §4.2 full-rejection (the rejected_no_bom line
        // genuinely can't be made). Restoring symmetry for mixed cases is
        // a follow-up.
        when(sagaManager.applyManufacturingDispatched(eq(SO), eq(0), eq(2)))
            .thenReturn(REJECTED);
        stubOrderExists();

        handler.handle(event("rejected_no_bom", "rejected_not_manufactured"));

        verify(sagaManager, never()).applyManufacturingDispatchedReroutingToPurchasing(any(), any());
        verify(sagaManager).applyManufacturingDispatched(SO, 0, 2);
        verify(statusProjection).markStatus(SO, SalesOrder.Status.REJECTED);
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
