package com.northwood.finance.application.inbox;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.northwood.finance.application.JournalEntryService;
import com.northwood.manufacturing.domain.events.WorkOrderConversionApplied;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.EventEnvelope;
import com.northwood.shared.domain.Currencies;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class WorkOrderConversionAppliedHandlerTest {

    private static final UUID WORK_ORDER = UUID.randomUUID();
    private static final UUID FG = UUID.randomUUID();

    @Mock InboxPort inbox;
    @Mock JournalEntryService journals;

    private final ObjectMapper json = new ObjectMapper();
    private WorkOrderConversionAppliedHandler handler;

    @BeforeEach
    void setUp() {
        handler = new WorkOrderConversionAppliedHandler(inbox, journals, json);
    }

    private EventEnvelope event(BigDecimal actualConversion, BigDecimal standardConversion) {
        UUID eventId = UUID.randomUUID();
        WorkOrderConversionApplied payload = new WorkOrderConversionApplied(
            eventId, WORK_ORDER, "WO-001", FG, actualConversion, standardConversion, Currencies.AUD, Instant.now());
        return new EventEnvelope(
            eventId, "WorkOrder", WORK_ORDER,
            WorkOrderConversionApplied.EVENT_TYPE, 1, json.writeValueAsString(payload),
            null, null, null, null, Instant.now());
    }

    @Test void charges_wip_at_actual_and_clears_efficiency_variance() {
        handler.handle(event(new BigDecimal("150.00"), new BigDecimal("135.00")));

        // WIP charged at actual conversion (Dr 1230 / Cr 5250).
        verify(journals).postConversionCharge(
            eq(WORK_ORDER), eq("WO-001"), eq(new BigDecimal("150.00")), eq(Currencies.AUD), any(LocalDate.class));
        // Efficiency variance = actual 150 − standard 135 = 15 cleared to 5100.
        verify(journals).postProductionVariance(
            eq(WORK_ORDER), eq("WO-001"), eq(new BigDecimal("15.00")), eq(Currencies.AUD), any(LocalDate.class));
        verify(inbox).recordProcessed(any());
    }

    @Test void zero_variance_when_actual_equals_standard() {
        handler.handle(event(new BigDecimal("135.00"), new BigDecimal("135.00")));

        verify(journals).postConversionCharge(
            eq(WORK_ORDER), eq("WO-001"), eq(new BigDecimal("135.00")), eq(Currencies.AUD), any(LocalDate.class));
        // postProductionVariance is still called (it no-ops on zero); variance = 0.00.
        verify(journals).postProductionVariance(
            eq(WORK_ORDER), eq("WO-001"), eq(new BigDecimal("0.00")), eq(Currencies.AUD), any(LocalDate.class));
        verify(inbox).recordProcessed(any());
    }

    @Test void already_processed_short_circuits() {
        EventEnvelope envelope = event(new BigDecimal("135.00"), new BigDecimal("135.00"));
        when(inbox.alreadyProcessed(eq(envelope.eventId()), eq(WorkOrderConversionAppliedHandler.CONSUMER_NAME)))
            .thenReturn(true);

        handler.handle(envelope);

        verifyNoInteractions(journals);
        verify(inbox, never()).recordProcessed(any());
    }
}
