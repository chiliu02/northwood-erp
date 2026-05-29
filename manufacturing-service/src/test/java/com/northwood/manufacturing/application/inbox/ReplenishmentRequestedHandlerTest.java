package com.northwood.manufacturing.application.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.northwood.inventory.domain.InventoryAggregateTypes;
import com.northwood.inventory.domain.events.ReplenishmentRequested;
import com.northwood.manufacturing.application.BomLookup;
import com.northwood.manufacturing.application.BomLookup.BomHeaderIdentity;
import com.northwood.manufacturing.application.WorkOrderReleaseService;
import com.northwood.manufacturing.application.dto.ReleaseForReplenishmentCommand;
import com.northwood.manufacturing.domain.WorkOrder;
import com.northwood.manufacturing.domain.WorkOrderId;
import com.northwood.manufacturing.domain.events.ReplenishmentUndispatchable;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.outbox.OutboxAppender;
import com.northwood.shared.application.messaging.EventEnvelope;
import java.math.BigDecimal;
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
class ReplenishmentRequestedHandlerTest {

    private static final UUID REPLENISHMENT_REQUEST = UUID.randomUUID();
    private static final UUID PRODUCT = UUID.randomUUID();
    private static final UUID WAREHOUSE = UUID.randomUUID();
    private static final BigDecimal QTY = new BigDecimal("25");

    @Mock InboxPort inbox;
    @Mock WorkOrderReleaseService releaseService;
    @Mock BomLookup boms;
    @Mock OutboxAppender outbox;

    private final ObjectMapper json = new ObjectMapper();
    private ReplenishmentRequestedHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ReplenishmentRequestedHandler(inbox, releaseService, boms, outbox, json);
    }

    private EventEnvelope event(String targetService) {
        UUID eventId = UUID.randomUUID();
        ReplenishmentRequested payload = new ReplenishmentRequested(
            eventId, REPLENISHMENT_REQUEST, PRODUCT, WAREHOUSE, QTY,
            targetService, ReplenishmentRequested.REASON_REORDER_POINT_BREACH,
            Instant.now()
        );
        return new EventEnvelope(
            eventId, InventoryAggregateTypes.REPLENISHMENT_REQUEST, REPLENISHMENT_REQUEST,
            ReplenishmentRequested.EVENT_TYPE, 1,
            json.writeValueAsString(payload),
            null, null, null, null, Instant.now()
        );
    }

    @Test void purchasing_routed_request_is_a_no_op_for_manufacturing() {
        handler.handle(event(ReplenishmentRequested.TARGET_SERVICE_PURCHASING));

        verify(releaseService, never()).releaseForReplenishment(any());
        verify(boms, never()).findActiveBomIdentity(any());
        verify(inbox).recordProcessed(any());
    }

    @Test void manufacturing_routed_request_with_active_bom_releases_a_stock_wo() {
        when(boms.findActiveBomIdentity(PRODUCT))
            .thenReturn(Optional.of(new BomHeaderIdentity("FG-WIDGET-001", "Widget")));
        WorkOrder stub = WorkOrder.reconstitute(
            WorkOrderId.newId(), "WO-STUB",
            null, null, REPLENISHMENT_REQUEST, null,
            PRODUCT, "FG-WIDGET-001", "Widget", UUID.randomUUID(), QTY,
            WorkOrder.Status.RELEASED, WorkOrder.MaterialStatus.RESERVATION_PENDING,
            BigDecimal.ZERO, null, null, 1L, List.of(), List.of()
        );
        when(releaseService.releaseForReplenishment(any())).thenReturn(stub);

        handler.handle(event(ReplenishmentRequested.TARGET_SERVICE_MANUFACTURING));

        ArgumentCaptor<ReleaseForReplenishmentCommand> captor =
            ArgumentCaptor.forClass(ReleaseForReplenishmentCommand.class);
        verify(releaseService).releaseForReplenishment(captor.capture());
        ReleaseForReplenishmentCommand cmd = captor.getValue();
        assertThat(cmd.replenishmentRequestId()).isEqualTo(REPLENISHMENT_REQUEST);
        assertThat(cmd.finishedProductId()).isEqualTo(PRODUCT);
        assertThat(cmd.finishedProductSku()).isEqualTo("FG-WIDGET-001");
        assertThat(cmd.finishedProductName()).isEqualTo("Widget");
        assertThat(cmd.plannedQuantity()).isEqualByComparingTo(QTY);
        assertThat(cmd.workOrderNumber()).startsWith(WorkOrder.NUMBER_PREFIX);
    }

    @Test void no_active_bom_emits_undispatchable_and_does_not_release() {
        when(boms.findActiveBomIdentity(PRODUCT)).thenReturn(Optional.empty());

        handler.handle(event(ReplenishmentRequested.TARGET_SERVICE_MANUFACTURING));

        verify(releaseService, never()).releaseForReplenishment(any());
        ArgumentCaptor<ReplenishmentUndispatchable> cap = ArgumentCaptor.forClass(ReplenishmentUndispatchable.class);
        verify(outbox).append(cap.capture(), eq(InventoryAggregateTypes.REPLENISHMENT_REQUEST), any());
        assertThat(cap.getValue().replenishmentRequestId()).isEqualTo(REPLENISHMENT_REQUEST);
        assertThat(cap.getValue().productId()).isEqualTo(PRODUCT);
        verify(inbox).recordProcessed(any());
    }
}
