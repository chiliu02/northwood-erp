package com.northwood.inventory.application.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.northwood.inventory.domain.ReplenishmentRequest;
import com.northwood.inventory.domain.ReplenishmentRequest.DispatchedAggregateKind;
import com.northwood.inventory.domain.ReplenishmentRequest.Reason;
import com.northwood.inventory.domain.ReplenishmentRequest.Status;
import com.northwood.inventory.domain.ReplenishmentRequest.TargetService;
import com.northwood.inventory.domain.ReplenishmentRequestRepository;
import com.northwood.purchasing.domain.PurchasingAggregateTypes;
import com.northwood.purchasing.domain.events.PurchaseOrderCreated;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.EventEnvelope;
import com.northwood.shared.domain.Currencies;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class ReplenishmentPurchaseOrderLinkHandlerTest {

    private static final UUID PO = UUID.randomUUID();
    private static final UUID PR_HEADER = UUID.randomUUID();
    private static final UUID PRODUCT = UUID.randomUUID();
    private static final UUID WAREHOUSE = UUID.randomUUID();

    @Mock InboxPort inbox;
    @Mock ReplenishmentRequestRepository replenishmentRequests;

    private final ObjectMapper json = new ObjectMapper();
    private ReplenishmentPurchaseOrderLinkHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ReplenishmentPurchaseOrderLinkHandler(inbox, replenishmentRequests, json);
    }

    private static ReplenishmentRequest requested() {
        return ReplenishmentRequest.request(
            PRODUCT, WAREHOUSE, BigDecimal.TEN, TargetService.PURCHASING, Reason.REORDER_POINT_BREACH);
    }

    private EventEnvelope event(UUID sourceReplenishmentRequestId) {
        UUID eventId = UUID.randomUUID();
        PurchaseOrderCreated payload = new PurchaseOrderCreated(
            eventId, PO, "PO-001",
            UUID.randomUUID(), "SUP-001", "Acme Supplies",
            PR_HEADER, null, sourceReplenishmentRequestId, Currencies.AUD,
            new BigDecimal("500.00"), "sent",
            List.of(), Instant.now()
        );
        return new EventEnvelope(
            eventId, PurchasingAggregateTypes.PURCHASE_ORDER, PO,
            PurchaseOrderCreated.EVENT_TYPE, 1,
            json.writeValueAsString(payload),
            null, null, null, null, Instant.now()
        );
    }

    @Test void po_before_dispatch_dispatches_and_links_in_one_step() {
        ReplenishmentRequest r = requested();   // still 'requested' — ReplenishmentDispatched hasn't landed
        UUID rrId = r.id().value();
        when(replenishmentRequests.findById(any())).thenReturn(Optional.of(r));

        handler.handle(event(rrId));

        assertThat(r.status()).isEqualTo(Status.DISPATCHED);
        assertThat(r.dispatchedAggregateKind()).isEqualTo(DispatchedAggregateKind.PURCHASE_REQUISITION);
        assertThat(r.dispatchedAggregateId()).isEqualTo(PR_HEADER);
        assertThat(r.linkedPurchaseOrderId()).isEqualTo(PO);
        verify(replenishmentRequests).save(r);
        verify(inbox).recordProcessed(any());
    }

    @Test void dispatch_before_po_only_stamps_the_link() {
        ReplenishmentRequest r = requested();
        r.markDispatched(DispatchedAggregateKind.PURCHASE_REQUISITION, PR_HEADER);   // ReplenishmentDispatched won the race
        UUID rrId = r.id().value();
        when(replenishmentRequests.findById(any())).thenReturn(Optional.of(r));

        handler.handle(event(rrId));

        assertThat(r.status()).isEqualTo(Status.DISPATCHED);   // markDispatched was an idempotent no-op
        assertThat(r.linkedPurchaseOrderId()).isEqualTo(PO);
        verify(replenishmentRequests).save(r);
    }

    @Test void redelivered_same_po_is_idempotent() {
        ReplenishmentRequest r = requested();
        r.markDispatched(DispatchedAggregateKind.PURCHASE_REQUISITION, PR_HEADER);
        r.linkPurchaseOrder(PO);   // already linked to this PO
        when(replenishmentRequests.findById(any())).thenReturn(Optional.of(r));

        handler.handle(event(r.id().value()));

        assertThat(r.linkedPurchaseOrderId()).isEqualTo(PO);
        verify(replenishmentRequests).save(r);   // re-save is harmless
    }

    @Test void manual_po_without_source_replenishment_is_ignored() {
        handler.handle(event(null));

        verifyNoInteractions(replenishmentRequests);
        verify(inbox).recordProcessed(any());
    }

    @Test void unknown_replenishment_request_is_ignored() {
        when(replenishmentRequests.findById(any())).thenReturn(Optional.empty());

        handler.handle(event(UUID.randomUUID()));

        verify(replenishmentRequests, never()).save(any());
        verify(inbox).recordProcessed(any());
    }

    @Test void already_processed_short_circuits() {
        EventEnvelope envelope = event(UUID.randomUUID());
        when(inbox.alreadyProcessed(eq(envelope.eventId()),
            eq(ReplenishmentPurchaseOrderLinkHandler.HANDLER_NAME))).thenReturn(true);

        handler.handle(envelope);

        verifyNoInteractions(replenishmentRequests);
        verify(inbox, never()).recordProcessed(any());
    }
}
