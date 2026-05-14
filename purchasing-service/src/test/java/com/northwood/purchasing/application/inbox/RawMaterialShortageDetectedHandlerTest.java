package com.northwood.purchasing.application.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.northwood.manufacturing.domain.events.RawMaterialShortageDetected;
import com.northwood.purchasing.application.PurchaseRequisitionService;
import com.northwood.purchasing.application.dto.WorkOrderShortageCommand;
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
class RawMaterialShortageDetectedHandlerTest {

    private static final UUID WO = UUID.randomUUID();

    @Mock InboxPort inbox;
    @Mock PurchaseRequisitionService requisitions;

    private final ObjectMapper json = new ObjectMapper();
    private RawMaterialShortageDetectedHandler handler;

    @BeforeEach
    void setUp() {
        handler = new RawMaterialShortageDetectedHandler(inbox, requisitions, json);
    }

    private EventEnvelope event(List<RawMaterialShortageDetected.ShortageComponent> components) {
        UUID eventId = UUID.randomUUID();
        RawMaterialShortageDetected payload = new RawMaterialShortageDetected(
            eventId, WO, WO, UUID.randomUUID(), UUID.randomUUID(), "MAIN",
            components, Instant.now()
        );
        return new EventEnvelope(
            eventId, "WorkOrder", WO,
            RawMaterialShortageDetected.EVENT_TYPE, 1,
            json.writeValueAsString(payload),
            null, null, null, null, Instant.now()
        );
    }

    private RawMaterialShortageDetected.ShortageComponent component(String sku, String qty) {
        return new RawMaterialShortageDetected.ShortageComponent(
            UUID.randomUUID(), UUID.randomUUID(), sku, "Material " + sku, new BigDecimal(qty)
        );
    }

    @Test void shortage_creates_requisition_with_one_line_per_component() {
        handler.handle(event(List.of(component("RM-1", "5"), component("RM-2", "3"))));

        ArgumentCaptor<WorkOrderShortageCommand> cap = ArgumentCaptor.forClass(WorkOrderShortageCommand.class);
        verify(requisitions).createForWorkOrderShortage(cap.capture());
        WorkOrderShortageCommand cmd = cap.getValue();
        assertThat(cmd.workOrderId()).isEqualTo(WO);
        assertThat(cmd.lines()).hasSize(2);
        assertThat(cmd.requisitionNumber()).startsWith("PR-");
        verify(inbox).recordProcessed(any());
    }

    @Test void empty_components_list_skips_requisition_but_still_records_processed() {
        handler.handle(event(List.of()));

        verify(requisitions, never()).createForWorkOrderShortage(any());
        verify(inbox).recordProcessed(any());
    }

    @Test void already_processed_short_circuits() {
        EventEnvelope envelope = event(List.of(component("RM-1", "5")));
        when(inbox.alreadyProcessed(eq(envelope.eventId()),
            eq(RawMaterialShortageDetectedHandler.CONSUMER_NAME))).thenReturn(true);

        handler.handle(envelope);

        verifyNoInteractions(requisitions);
        verify(inbox, never()).recordProcessed(any());
    }

    @Test void wrong_event_type_is_no_op() {
        EventEnvelope wrong = new EventEnvelope(
            UUID.randomUUID(), "WorkOrder", UUID.randomUUID(),
            "manufacturing.SomethingElse", 1, "{}",
            null, null, null, null, Instant.now()
        );

        handler.handle(wrong);

        verify(inbox, never()).alreadyProcessed(any(), any());
        verifyNoInteractions(requisitions);
    }
}
