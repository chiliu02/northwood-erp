package com.northwood.finance.application.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.northwood.finance.application.JournalEntryService;
import com.northwood.finance.application.JournalEntryService.LineCost;
import com.northwood.finance.application.ProductCardLookup;
import com.northwood.manufacturing.domain.events.SubAssembliesConsumed;
import com.northwood.manufacturing.domain.events.SubAssembliesConsumed.ConsumedItem;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class SubAssembliesConsumedWipHandlerTest {

    private static final UUID PARENT_WO = UUID.randomUUID();
    private static final UUID SUB_A = UUID.randomUUID();

    @Mock InboxPort inbox;
    @Mock JournalEntryService journals;
    @Mock ProductCardLookup productCards;
    @Mock WorkOrderWipProjection workOrderWip;

    private final ObjectMapper json = new ObjectMapper();
    private SubAssembliesConsumedWipHandler handler;

    @BeforeEach
    void setUp() {
        handler = new SubAssembliesConsumedWipHandler(inbox, journals, productCards, workOrderWip, json);
    }

    private EventEnvelope event(ConsumedItem... items) {
        UUID eventId = UUID.randomUUID();
        SubAssembliesConsumed payload = new SubAssembliesConsumed(
            eventId, PARENT_WO, List.of(items), Instant.now());
        return new EventEnvelope(
            eventId, "WorkOrder", PARENT_WO,
            SubAssembliesConsumed.EVENT_TYPE, 1, json.writeValueAsString(payload),
            null, null, null, null, Instant.now());
    }

    @Test void consumption_rolls_value_into_parent_wip_and_posts() {
        when(productCards.findStandardCost(SUB_A)).thenReturn(Optional.of(new BigDecimal("30.00")));
        ConsumedItem item = new ConsumedItem(UUID.randomUUID(), SUB_A, new BigDecimal("3"));

        handler.handle(event(item));   // 3 × 30.00 = 90.00

        ArgumentCaptor<BigDecimal> rolled = ArgumentCaptor.forClass(BigDecimal.class);
        verify(workOrderWip).rollInSubAssemblies(eq(PARENT_WO), rolled.capture());
        assertThat(rolled.getValue()).isEqualByComparingTo("90.00");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LineCost>> lineCosts = ArgumentCaptor.forClass(List.class);
        verify(journals).postSubAssemblyConsumption(
            eq(PARENT_WO), any(), lineCosts.capture(), eq(Currencies.BASE_CURRENCY), any());
        BigDecimal total = lineCosts.getValue().stream()
            .map(LineCost::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(total).isEqualByComparingTo("90.00");
        verify(inbox).recordProcessed(any());
    }

    @Test void zero_standard_cost_skips_roll_in_and_post() {
        when(productCards.findStandardCost(SUB_A)).thenReturn(Optional.empty());
        ConsumedItem item = new ConsumedItem(UUID.randomUUID(), SUB_A, new BigDecimal("3"));

        handler.handle(event(item));

        verify(workOrderWip, never()).rollInSubAssemblies(any(), any());
        verifyNoInteractions(journals);
    }

    @Test void already_processed_short_circuits() {
        ConsumedItem item = new ConsumedItem(UUID.randomUUID(), SUB_A, new BigDecimal("1"));
        EventEnvelope envelope = event(item);
        when(inbox.alreadyProcessed(eq(envelope.eventId()), eq(SubAssembliesConsumedWipHandler.CONSUMER_NAME)))
            .thenReturn(true);

        handler.handle(envelope);

        verifyNoInteractions(journals);
        verifyNoInteractions(workOrderWip);
        verify(inbox, never()).recordProcessed(any());
    }
}
