package com.northwood.purchasing.application.inbox;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.northwood.inventory.domain.InventoryAggregateTypes;
import com.northwood.inventory.domain.WarehouseCodes;
import com.northwood.inventory.domain.events.GoodsReceived;
import com.northwood.purchasing.application.inbox.PurchaseOrderReceiptProjection.ReceiptOutcome;
import com.northwood.purchasing.application.saga.PurchaseToPaySagaManager;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.EventEnvelope;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class GoodsReceivedHandlerTest {

    private static final UUID PO = UUID.randomUUID();

    @Mock InboxPort inbox;
    @Mock PurchaseToPaySagaManager sagaManager;
    @Mock PurchaseOrderReceiptProjection receiptProjection;

    private final ObjectMapper json = new ObjectMapper();
    private GoodsReceivedHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GoodsReceivedHandler(inbox, sagaManager, receiptProjection, json);
    }

    private EventEnvelope event(String receivedQty) {
        UUID eventId = UUID.randomUUID();
        GoodsReceived payload = new GoodsReceived(
            eventId, UUID.randomUUID(), "GR-001",
            PO, UUID.randomUUID(), WarehouseCodes.MAIN,
            List.of(new GoodsReceived.ReceivedLine(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "SKU", "Product", new BigDecimal(receivedQty), new BigDecimal("10.00")
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

    @Test void fully_received_calls_projection_and_advances_saga_via_manager() {
        when(receiptProjection.recordReceipt(eq(PO), anyList()))
            .thenReturn(new ReceiptOutcome(true, new BigDecimal("100")));

        handler.handle(event("100"));

        verify(receiptProjection).recordReceipt(eq(PO), anyList());
        verify(sagaManager).applyGoodsReceived(PO, true);
        verify(inbox).recordProcessed(any());
    }

    @Test void partial_receipt_passes_false_to_manager() {
        when(receiptProjection.recordReceipt(eq(PO), anyList()))
            .thenReturn(new ReceiptOutcome(false, new BigDecimal("50")));

        handler.handle(event("50"));

        verify(sagaManager).applyGoodsReceived(PO, false);
    }

    @Test void already_processed_short_circuits() {
        EventEnvelope envelope = event("100");
        when(inbox.alreadyProcessed(eq(envelope.eventId()),
            eq(GoodsReceivedHandler.CONSUMER_NAME))).thenReturn(true);

        handler.handle(envelope);

        verifyNoInteractions(receiptProjection);
        verifyNoInteractions(sagaManager);
        verify(inbox, never()).recordProcessed(any());
    }

    @Test void wrong_event_type_is_no_op() {
        EventEnvelope wrong = new EventEnvelope(
            UUID.randomUUID(), InventoryAggregateTypes.GOODS_RECEIPT, UUID.randomUUID(),
            "inventory.SomethingElse", 1, "{}",
            null, null, null, null, Instant.now()
        );

        handler.handle(wrong);

        verify(inbox, never()).alreadyProcessed(any(), any());
        verifyNoInteractions(receiptProjection);
        verifyNoInteractions(sagaManager);
    }
}
