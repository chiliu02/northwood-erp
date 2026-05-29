package com.northwood.manufacturing.application.inbox;

import static com.northwood.manufacturing.domain.saga.WorkOrderSaga.RAW_MATERIAL_RESERVATION_REQUESTED;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.northwood.inventory.domain.InventoryAggregateTypes;
import com.northwood.inventory.domain.WarehouseCodes;
import com.northwood.inventory.domain.events.GoodsReceived;
import com.northwood.manufacturing.application.saga.RawMaterialReservationRequestEmitter;
import com.northwood.manufacturing.application.saga.WorkOrderSagaManager;
import com.northwood.manufacturing.application.saga.WorkOrderSagaManager.RecoveryOutcome;
import com.northwood.manufacturing.application.saga.WorkOrderShortageRecoveryQueryPort;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.EventEnvelope;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

/**
 * Shell-smoke tests: handler dedupes, builds the receivedByProduct map,
 * fetches candidates from the recovery query port, and delegates each to the
 * manager. Substantive un-park / narrow / legacy-fallback transition logic is
 * tested in {@code JdbcWorkOrderSagaManagerTest}.
 */
@ExtendWith(MockitoExtension.class)
class GoodsReceivedHandlerTest {

    @Mock InboxPort inbox;
    @Mock WorkOrderSagaManager sagaManager;
    @Mock WorkOrderShortageRecoveryQueryPort recovery;
    @Mock RawMaterialReservationRequestEmitter reservationEmitter;

    private final ObjectMapper json = new ObjectMapper();
    private GoodsReceivedHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GoodsReceivedHandler(inbox, sagaManager, recovery, reservationEmitter, json);
    }

    private EventEnvelope receiptEvent(UUID productId, String quantity) {
        UUID eventId = UUID.randomUUID();
        GoodsReceived payload = new GoodsReceived(
            eventId, UUID.randomUUID(), "GR-001",
            UUID.randomUUID(), UUID.randomUUID(), WarehouseCodes.MAIN,
            List.of(new GoodsReceived.ReceivedLine(
                UUID.randomUUID(), UUID.randomUUID(), productId,
                "RM-X", "Material X", new BigDecimal(quantity), BigDecimal.ZERO
            )),
            Instant.now()
        );
        return new EventEnvelope(
            eventId, InventoryAggregateTypes.GOODS_RECEIPT, UUID.randomUUID(),
            GoodsReceived.EVENT_TYPE, 1,
            json.writeValueAsString(payload),
            null, null, null, null, Instant.now()
        );
    }

    @Test void candidates_returned_by_query_port_are_delegated_to_manager() {
        UUID prod = UUID.randomUUID();
        UUID sagaA = UUID.randomUUID();
        UUID sagaB = UUID.randomUUID();
        when(recovery.findShortageSagaIdsForReceivedProducts(any(Collection.class)))
            .thenReturn(List.of(sagaA, sagaB));
        // Narrowed (still short): no re-request emission.
        when(sagaManager.unparkOrNarrowShortage(any(), any()))
            .thenReturn(new RecoveryOutcome(null, null));

        handler.handle(receiptEvent(prod, "5"));

        verify(sagaManager).unparkOrNarrowShortage(eq(sagaA), any());
        verify(sagaManager).unparkOrNarrowShortage(eq(sagaB), any());
        verifyNoInteractions(reservationEmitter);
        verify(inbox).recordProcessed(any());
    }

    @Test void unparked_saga_triggers_reservation_re_request() {
        UUID prod = UUID.randomUUID();
        UUID saga = UUID.randomUUID();
        UUID workOrder = UUID.randomUUID();
        when(recovery.findShortageSagaIdsForReceivedProducts(any(Collection.class)))
            .thenReturn(List.of(saga));
        when(sagaManager.unparkOrNarrowShortage(eq(saga), any()))
            .thenReturn(new RecoveryOutcome(RAW_MATERIAL_RESERVATION_REQUESTED, workOrder));

        handler.handle(receiptEvent(prod, "5"));

        verify(reservationEmitter).emitFor(eq(workOrder));
        verify(inbox).recordProcessed(any());
    }

    @Test void no_candidates_means_no_manager_call() {
        when(recovery.findShortageSagaIdsForReceivedProducts(any(Collection.class)))
            .thenReturn(List.of());

        handler.handle(receiptEvent(UUID.randomUUID(), "5"));

        verify(sagaManager, never()).unparkOrNarrowShortage(any(), any());
        verify(inbox).recordProcessed(any());
    }

    @Test void already_processed_short_circuits() {
        EventEnvelope envelope = receiptEvent(UUID.randomUUID(), "5");
        when(inbox.alreadyProcessed(eq(envelope.eventId()),
            eq(GoodsReceivedHandler.CONSUMER_NAME))).thenReturn(true);

        handler.handle(envelope);

        verifyNoInteractions(recovery);
        verifyNoInteractions(sagaManager);
        verify(inbox, never()).recordProcessed(any());
    }
}
