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
import com.northwood.manufacturing.domain.events.WorkOrderManufacturingCompleted;
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
class WorkOrderManufacturingCompletedWipHandlerTest {

    private static final UUID WORK_ORDER = UUID.randomUUID();
    private static final UUID FG = UUID.randomUUID();

    @Mock InboxPort inbox;
    @Mock JournalEntryService journals;
    @Mock ProductCardLookup productCards;
    @Mock WorkOrderWipProjection workOrderWip;

    private final ObjectMapper json = new ObjectMapper();
    private WorkOrderManufacturingCompletedWipHandler handler;

    @BeforeEach
    void setUp() {
        handler = new WorkOrderManufacturingCompletedWipHandler(inbox, journals, productCards, workOrderWip, json);
    }

    private EventEnvelope event(String completedQty) {
        UUID eventId = UUID.randomUUID();
        WorkOrderManufacturingCompleted payload = new WorkOrderManufacturingCompleted(
            eventId, WORK_ORDER, "WO-001",
            null, null, null, null,
            FG, "FG-001", new BigDecimal(completedQty), Instant.now());
        return new EventEnvelope(
            eventId, "WorkOrder", WORK_ORDER,
            WorkOrderManufacturingCompleted.EVENT_TYPE, 1, json.writeValueAsString(payload),
            null, null, null, null, Instant.now());
    }

    @Test void completion_settles_wip_dr_fg_cr_wip_at_standard_cost() {
        when(productCards.findStandardCost(FG)).thenReturn(Optional.of(new BigDecimal("21.00")));
        when(workOrderWip.markCompleted(WORK_ORDER, FG)).thenReturn(true);

        handler.handle(event("10"));   // 10 × 21.00 = 210.00

        ArgumentCaptor<BigDecimal> amount = ArgumentCaptor.forClass(BigDecimal.class);
        verify(journals).postWorkOrderCompletion(
            eq(WORK_ORDER), eq("WO-001"), eq(FG), amount.capture(), eq(Currencies.BASE_CURRENCY), any());
        assertThat(amount.getValue()).isEqualByComparingTo("210.00");
        verify(inbox).recordProcessed(any());
    }

    @Test void already_settled_skips_the_post() {
        when(productCards.findStandardCost(FG)).thenReturn(Optional.of(new BigDecimal("21.00")));
        when(workOrderWip.markCompleted(WORK_ORDER, FG)).thenReturn(false);

        handler.handle(event("10"));

        verify(journals, never()).postWorkOrderCompletion(any(), any(), any(), any(), any(), any());
    }

    @Test void zero_standard_cost_skips_settlement() {
        when(productCards.findStandardCost(FG)).thenReturn(Optional.empty());

        handler.handle(event("10"));

        verify(workOrderWip, never()).markCompleted(any(), any());
        verifyNoInteractions(journals);
    }

    @Test void already_processed_short_circuits() {
        EventEnvelope envelope = event("10");
        when(inbox.alreadyProcessed(eq(envelope.eventId()), eq(WorkOrderManufacturingCompletedWipHandler.HANDLER_NAME)))
            .thenReturn(true);

        handler.handle(envelope);

        verifyNoInteractions(journals);
        verifyNoInteractions(workOrderWip);
        verify(inbox, never()).recordProcessed(any());
    }
}
