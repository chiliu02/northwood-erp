package com.northwood.purchasing.application.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.northwood.inventory.domain.InventoryAggregateTypes;
import com.northwood.inventory.domain.events.ReplenishmentRequested;
import com.northwood.purchasing.application.PurchaseRequisitionService;
import com.northwood.purchasing.application.dto.StockReplenishmentCommand;
import com.northwood.purchasing.domain.events.ReplenishmentUndispatchable;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.EventEnvelope;
import com.northwood.shared.application.outbox.OutboxAppender;
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
class ReplenishmentRequestedHandlerTest {

    private static final UUID REPLENISHMENT_REQUEST = UUID.randomUUID();
    private static final UUID PRODUCT = UUID.randomUUID();
    private static final UUID WAREHOUSE = UUID.randomUUID();
    private static final BigDecimal QTY = new BigDecimal("15");

    @Mock InboxPort inbox;
    @Mock PurchaseRequisitionService requisitions;
    @Mock OutboxAppender outbox;

    private final ObjectMapper json = new ObjectMapper();
    private ReplenishmentRequestedHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ReplenishmentRequestedHandler(inbox, requisitions, outbox, json);
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

    @Test void manufacturing_routed_request_is_a_no_op_for_purchasing() {
        handler.handle(event(ReplenishmentRequested.TARGET_SERVICE_MANUFACTURING));

        verify(requisitions, never()).createForStockReplenishment(any());
        verify(inbox).recordProcessed(any());
    }

    @Test void purchasing_routed_request_creates_a_single_line_PR() {
        when(requisitions.createForStockReplenishment(any())).thenReturn(Optional.of(UUID.randomUUID()));

        handler.handle(event(ReplenishmentRequested.TARGET_SERVICE_PURCHASING));

        ArgumentCaptor<StockReplenishmentCommand> captor =
            ArgumentCaptor.forClass(StockReplenishmentCommand.class);
        verify(requisitions).createForStockReplenishment(captor.capture());
        StockReplenishmentCommand cmd = captor.getValue();
        assertThat(cmd.replenishmentRequestId()).isEqualTo(REPLENISHMENT_REQUEST);
        assertThat(cmd.lines()).hasSize(1);
        assertThat(cmd.lines().get(0).productId()).isEqualTo(PRODUCT);
        assertThat(cmd.lines().get(0).requestedQuantity()).isEqualByComparingTo(QTY);
        assertThat(cmd.requisitionNumber()).startsWith("PR-");
        verify(outbox, never()).append(any(), any(), any());
    }

    @Test void no_vendor_emits_undispatchable_and_raises_no_PR() {
        when(requisitions.createForStockReplenishment(any())).thenReturn(Optional.empty());

        handler.handle(event(ReplenishmentRequested.TARGET_SERVICE_PURCHASING));

        ArgumentCaptor<ReplenishmentUndispatchable> cap = ArgumentCaptor.forClass(ReplenishmentUndispatchable.class);
        verify(outbox).append(cap.capture(), eq(InventoryAggregateTypes.REPLENISHMENT_REQUEST), any());
        assertThat(cap.getValue().replenishmentRequestId()).isEqualTo(REPLENISHMENT_REQUEST);
        assertThat(cap.getValue().productId()).isEqualTo(PRODUCT);
    }
}
