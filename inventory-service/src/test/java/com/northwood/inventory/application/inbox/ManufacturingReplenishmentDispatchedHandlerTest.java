package com.northwood.inventory.application.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.northwood.inventory.domain.replenishment.ReplenishmentRequest;
import com.northwood.inventory.domain.replenishment.ReplenishmentRequest.Reason;
import com.northwood.inventory.domain.replenishment.ReplenishmentRequest.TargetService;
import com.northwood.inventory.domain.replenishment.ReplenishmentRequestId;
import com.northwood.inventory.domain.replenishment.ReplenishmentRequestRepository;
import com.northwood.manufacturing.domain.ManufacturingAggregateTypes;
import com.northwood.manufacturing.domain.events.ReplenishmentDispatched;
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
class ManufacturingReplenishmentDispatchedHandlerTest {

    private static final UUID REPLENISHMENT = UUID.randomUUID();
    private static final UUID WORK_ORDER = UUID.randomUUID();
    private static final UUID PRODUCT = UUID.randomUUID();
    private static final UUID WAREHOUSE = UUID.randomUUID();

    @Mock InboxPort inbox;
    @Mock ReplenishmentRequestRepository replenishmentRequests;

    private final ObjectMapper json = new ObjectMapper();
    private ManufacturingReplenishmentDispatchedHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ManufacturingReplenishmentDispatchedHandler(inbox, replenishmentRequests, json);
    }

    private EventEnvelope event() {
        UUID eventId = UUID.randomUUID();
        ReplenishmentDispatched payload = new ReplenishmentDispatched(
            eventId, WORK_ORDER, REPLENISHMENT, Instant.now()
        );
        return new EventEnvelope(
            eventId, ManufacturingAggregateTypes.WORK_ORDER, WORK_ORDER,
            ReplenishmentDispatched.EVENT_TYPE, 1,
            json.writeValueAsString(payload),
            null, null, null, null, Instant.now()
        );
    }

    @Test void flips_requested_to_dispatched_and_saves() {
        ReplenishmentRequest existing = ReplenishmentRequest.request(
            PRODUCT, WAREHOUSE, new BigDecimal("10"),
            TargetService.MANUFACTURING, Reason.REORDER_POINT_BREACH
        );
        existing.pullPendingEvents();
        when(replenishmentRequests.findById(ReplenishmentRequestId.of(REPLENISHMENT)))
            .thenReturn(Optional.of(existing));

        handler.handle(event());

        ArgumentCaptor<ReplenishmentRequest> captor = ArgumentCaptor.forClass(ReplenishmentRequest.class);
        verify(replenishmentRequests).save(captor.capture());
        ReplenishmentRequest r = captor.getValue();
        assertThat(r.status()).isEqualTo(ReplenishmentRequest.Status.DISPATCHED);
        assertThat(r.dispatchedAggregateKind())
            .isEqualTo(ReplenishmentRequest.DispatchedAggregateKind.WORK_ORDER);
        assertThat(r.dispatchedAggregateId()).isEqualTo(WORK_ORDER);
    }

    @Test void unknown_replenishment_is_a_warn_no_op() {
        when(replenishmentRequests.findById(ReplenishmentRequestId.of(REPLENISHMENT)))
            .thenReturn(Optional.empty());

        handler.handle(event());

        verify(replenishmentRequests, never()).save(any());
        verify(inbox).recordProcessed(any());
    }
}
