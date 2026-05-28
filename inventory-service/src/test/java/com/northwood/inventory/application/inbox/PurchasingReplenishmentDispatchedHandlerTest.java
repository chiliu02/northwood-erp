package com.northwood.inventory.application.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.northwood.inventory.domain.ReplenishmentRequest;
import com.northwood.inventory.domain.ReplenishmentRequest.Reason;
import com.northwood.inventory.domain.ReplenishmentRequest.TargetService;
import com.northwood.inventory.domain.ReplenishmentRequestId;
import com.northwood.inventory.domain.ReplenishmentRequestRepository;
import com.northwood.purchasing.domain.PurchasingAggregateTypes;
import com.northwood.purchasing.domain.events.ReplenishmentDispatched;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.EventEnvelope;
import java.math.BigDecimal;
import java.time.Instant;
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
class PurchasingReplenishmentDispatchedHandlerTest {

    private static final UUID REPLENISHMENT = UUID.randomUUID();
    private static final UUID PURCHASE_REQUISITION = UUID.randomUUID();
    private static final UUID PRODUCT = UUID.randomUUID();
    private static final UUID WAREHOUSE = UUID.randomUUID();

    @Mock InboxPort inbox;
    @Mock ReplenishmentRequestRepository replenishmentRequests;

    private final ObjectMapper json = new ObjectMapper();
    private PurchasingReplenishmentDispatchedHandler handler;

    @BeforeEach
    void setUp() {
        handler = new PurchasingReplenishmentDispatchedHandler(inbox, replenishmentRequests, json);
    }

    @Test void flips_requested_to_dispatched_and_records_pr_id() {
        ReplenishmentRequest existing = ReplenishmentRequest.request(
            PRODUCT, WAREHOUSE, new BigDecimal("5"),
            TargetService.PURCHASING, Reason.REORDER_POINT_BREACH
        );
        existing.pullPendingEvents();
        when(replenishmentRequests.findById(ReplenishmentRequestId.of(REPLENISHMENT)))
            .thenReturn(Optional.of(existing));

        UUID eventId = UUID.randomUUID();
        ReplenishmentDispatched payload = new ReplenishmentDispatched(
            eventId, PURCHASE_REQUISITION, REPLENISHMENT, Instant.now()
        );
        EventEnvelope envelope = new EventEnvelope(
            eventId, PurchasingAggregateTypes.PURCHASE_REQUISITION, PURCHASE_REQUISITION,
            ReplenishmentDispatched.EVENT_TYPE, 1,
            json.writeValueAsString(payload),
            null, null, null, null, Instant.now()
        );
        handler.handle(envelope);

        ArgumentCaptor<ReplenishmentRequest> captor = ArgumentCaptor.forClass(ReplenishmentRequest.class);
        verify(replenishmentRequests).save(captor.capture());
        ReplenishmentRequest r = captor.getValue();
        assertThat(r.status()).isEqualTo(ReplenishmentRequest.Status.DISPATCHED);
        assertThat(r.dispatchedAggregateKind())
            .isEqualTo(ReplenishmentRequest.DispatchedAggregateKind.PURCHASE_REQUISITION);
        assertThat(r.dispatchedAggregateId()).isEqualTo(PURCHASE_REQUISITION);
    }
}
