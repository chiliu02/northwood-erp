package com.northwood.finance.application.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.northwood.finance.application.JournalEntryService;
import com.northwood.inventory.domain.InventoryAggregateTypes;
import com.northwood.inventory.domain.WarehouseCodes;
import com.northwood.inventory.domain.events.GoodsReceived;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.EventEnvelope;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class GoodsReceivedHandlerTest {

    private static final UUID PO = UUID.randomUUID();
    private static final UUID GR = UUID.randomUUID();

    @Mock InboxPort inbox;
    @Mock PurchaseOrderLineFactsProjection projection;
    @Mock JournalEntryService journals;

    private final ObjectMapper json = new ObjectMapper();
    private GoodsReceivedHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GoodsReceivedHandler(inbox, projection, journals, json);
    }

    private GoodsReceived.ReceivedLine line(UUID poLineId, UUID productId, String qty, String unitCost) {
        return new GoodsReceived.ReceivedLine(
            UUID.randomUUID(), poLineId, productId, "SKU", "Product",
            new BigDecimal(qty), new BigDecimal(unitCost)
        );
    }

    private EventEnvelope event(List<GoodsReceived.ReceivedLine> lines) {
        UUID eventId = UUID.randomUUID();
        GoodsReceived payload = new GoodsReceived(
            eventId, GR, "GR-001", PO, UUID.randomUUID(), WarehouseCodes.MAIN,
            lines, Instant.now()
        );
        return new EventEnvelope(
            eventId, InventoryAggregateTypes.GOODS_RECEIPT, GR,
            GoodsReceived.EVENT_TYPE, 1,
            json.writeValueAsString(payload),
            null, null, null, null, Instant.now()
        );
    }

    @Test void per_line_applies_to_projection_and_aggregates_to_journal() {
        UUID poLine1 = UUID.randomUUID();
        UUID poLine2 = UUID.randomUUID();
        UUID prod1 = UUID.randomUUID();
        UUID prod2 = UUID.randomUUID();

        handler.handle(event(List.of(
            line(poLine1, prod1, "10", "5.00"),
            line(poLine2, prod2, "4", "20.00")
        )));

        verify(projection).applyGoodsReceived(poLine1, new BigDecimal("10"));
        verify(projection).applyGoodsReceived(poLine2, new BigDecimal("4"));

        ArgumentCaptor<List<JournalEntryService.LineCost>> captor = ArgumentCaptor.forClass(List.class);
        verify(journals).postGoodsReceived(eq(GR), eq("GR-001"), captor.capture(), eq("AUD"), any());
        List<JournalEntryService.LineCost> costs = captor.getValue();
        assertThat(costs).hasSize(2);
        assertThat(costs.get(0).productId()).isEqualTo(prod1);
        assertThat(costs.get(0).amount()).isEqualByComparingTo("50.00");
        assertThat(costs.get(1).amount()).isEqualByComparingTo("80.00");

        verify(inbox).recordProcessed(any());
    }

    @Test void null_unit_cost_or_quantity_treated_as_zero() {
        UUID prod = UUID.randomUUID();
        handler.handle(event(List.of(
            new GoodsReceived.ReceivedLine(
                UUID.randomUUID(), UUID.randomUUID(), prod, "SKU", "P",
                null, null
            )
        )));

        ArgumentCaptor<List<JournalEntryService.LineCost>> captor = ArgumentCaptor.forClass(List.class);
        verify(journals).postGoodsReceived(any(), any(), captor.capture(), any(), any());
        assertThat(captor.getValue().get(0).amount()).isEqualByComparingTo("0");
    }

    @Test void already_processed_short_circuits() {
        EventEnvelope envelope = event(List.of(line(UUID.randomUUID(), UUID.randomUUID(), "1", "1")));
        when(inbox.alreadyProcessed(eq(envelope.eventId()),
            eq(GoodsReceivedHandler.CONSUMER_NAME))).thenReturn(true);

        handler.handle(envelope);

        verifyNoInteractions(projection);
        verifyNoInteractions(journals);
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
        verifyNoInteractions(projection);
        verifyNoInteractions(journals);
    }
}
