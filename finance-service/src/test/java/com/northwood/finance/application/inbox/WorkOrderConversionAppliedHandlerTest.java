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

    private EventEnvelope event(BigDecimal conversionCost) {
        UUID eventId = UUID.randomUUID();
        WorkOrderConversionApplied payload = new WorkOrderConversionApplied(
            eventId, WORK_ORDER, "WO-001", FG, conversionCost, Currencies.AUD, Instant.now());
        return new EventEnvelope(
            eventId, "WorkOrder", WORK_ORDER,
            WorkOrderConversionApplied.EVENT_TYPE, 1, json.writeValueAsString(payload),
            null, null, null, null, Instant.now());
    }

    @Test void posts_conversion_charge_dr_wip_cr_conversion_applied() {
        handler.handle(event(new BigDecimal("135.00")));

        verify(journals).postConversionCharge(
            eq(WORK_ORDER), eq("WO-001"), eq(new BigDecimal("135.00")), eq(Currencies.AUD), any(LocalDate.class));
        verify(inbox).recordProcessed(any());
    }

    @Test void already_processed_short_circuits() {
        EventEnvelope envelope = event(new BigDecimal("135.00"));
        when(inbox.alreadyProcessed(eq(envelope.eventId()), eq(WorkOrderConversionAppliedHandler.CONSUMER_NAME)))
            .thenReturn(true);

        handler.handle(envelope);

        verifyNoInteractions(journals);
        verify(inbox, never()).recordProcessed(any());
    }
}
