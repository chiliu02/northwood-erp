package com.northwood.finance.application.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.northwood.finance.application.JournalEntryService;
import com.northwood.finance.application.ProductCardLookup;
import com.northwood.inventory.domain.InventoryAggregateTypes;
import com.northwood.inventory.domain.WarehouseCodes;
import com.northwood.inventory.domain.events.StockAdjusted;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.EventEnvelope;
import com.northwood.shared.domain.Currencies;
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
class StockAdjustedHandlerTest {

    private static final UUID ADJ = UUID.randomUUID();
    private static final UUID PRODUCT = UUID.randomUUID();
    private static final UUID WAREHOUSE = UUID.randomUUID();

    @Mock InboxPort inbox;
    @Mock JournalEntryService journals;
    @Mock ProductCardLookup productCards;

    private final ObjectMapper json = new ObjectMapper();
    private StockAdjustedHandler handler;

    @BeforeEach
    void setUp() {
        handler = new StockAdjustedHandler(inbox, journals, productCards, json);
    }

    private EventEnvelope event(String direction, BigDecimal qty) {
        UUID eventId = UUID.randomUUID();
        StockAdjusted payload = new StockAdjusted(
            eventId, ADJ, "ADJ-001", WAREHOUSE, WarehouseCodes.MAIN,
            PRODUCT, "SKU", "Widget", direction, qty, "cycle count", Instant.now());
        return new EventEnvelope(
            eventId, InventoryAggregateTypes.STOCK_ADJUSTMENT, ADJ,
            StockAdjusted.EVENT_TYPE, 1, json.writeValueAsString(payload),
            null, null, null, null, Instant.now());
    }

    @Test void gain_posts_journal_valued_at_standard_cost() {
        when(productCards.findStandardCost(PRODUCT)).thenReturn(Optional.of(new BigDecimal("5.00")));

        handler.handle(event(StockAdjusted.DIRECTION_IN, new BigDecimal("10")));

        ArgumentCaptor<BigDecimal> amount = ArgumentCaptor.forClass(BigDecimal.class);
        verify(journals).postStockAdjustment(
            eq(ADJ), eq("ADJ-001"), eq(PRODUCT), amount.capture(), eq(true),
            eq(Currencies.BASE_CURRENCY), any());
        assertThat(amount.getValue()).isEqualByComparingTo("50.00"); // 10 × 5.00
        verify(inbox).recordProcessed(any());
    }

    @Test void loss_posts_with_gain_false() {
        when(productCards.findStandardCost(PRODUCT)).thenReturn(Optional.of(new BigDecimal("5.00")));

        handler.handle(event(StockAdjusted.DIRECTION_OUT, new BigDecimal("4")));

        verify(journals).postStockAdjustment(
            eq(ADJ), eq("ADJ-001"), eq(PRODUCT), any(), eq(false),
            eq(Currencies.BASE_CURRENCY), any());
    }

    @Test void missing_standard_cost_posts_zero_amount() {
        when(productCards.findStandardCost(PRODUCT)).thenReturn(Optional.empty());

        handler.handle(event(StockAdjusted.DIRECTION_IN, new BigDecimal("10")));

        ArgumentCaptor<BigDecimal> amount = ArgumentCaptor.forClass(BigDecimal.class);
        verify(journals).postStockAdjustment(
            any(), any(), any(), amount.capture(), eq(true), any(), any());
        assertThat(amount.getValue()).isEqualByComparingTo("0"); // service skips the GL pair on zero
    }

    @Test void already_processed_short_circuits() {
        EventEnvelope envelope = event(StockAdjusted.DIRECTION_IN, new BigDecimal("1"));
        when(inbox.alreadyProcessed(eq(envelope.eventId()), eq(StockAdjustedHandler.HANDLER_NAME)))
            .thenReturn(true);

        handler.handle(envelope);

        verifyNoInteractions(journals);
        verify(inbox, never()).recordProcessed(any());
    }

    @Test void wrong_event_type_is_no_op() {
        EventEnvelope wrong = new EventEnvelope(
            UUID.randomUUID(), InventoryAggregateTypes.STOCK_ADJUSTMENT, UUID.randomUUID(),
            "inventory.SomethingElse", 1, "{}",
            null, null, null, null, Instant.now());

        handler.handle(wrong);

        verify(inbox, never()).alreadyProcessed(any(), any());
        verifyNoInteractions(journals);
    }
}
