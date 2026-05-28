package com.northwood.inventory.application.inbox;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.northwood.inventory.application.WarehouseLookup;
import com.northwood.inventory.application.replenishment.ReplenishmentDetectionService;
import com.northwood.inventory.domain.replenishment.ReplenishmentRequest.Reason;
import com.northwood.manufacturing.domain.ManufacturingAggregateTypes;
import com.northwood.manufacturing.domain.events.RawMaterialShortageDetected;
import com.northwood.manufacturing.domain.events.RawMaterialShortageDetected.ShortageComponent;
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
class RawMaterialShortageDetectedHandlerTest {

    private static final UUID WAREHOUSE_ID = UUID.randomUUID();
    private static final String WAREHOUSE_CODE = "WH-MAIN";
    private static final UUID WORK_ORDER = UUID.randomUUID();
    private static final UUID COMPONENT_A = UUID.randomUUID();
    private static final UUID COMPONENT_B = UUID.randomUUID();

    @Mock InboxPort inbox;
    @Mock ReplenishmentDetectionService detection;
    @Mock WarehouseLookup warehouses;

    private final ObjectMapper json = new ObjectMapper();
    private RawMaterialShortageDetectedHandler handler;

    @BeforeEach
    void setUp() {
        handler = new RawMaterialShortageDetectedHandler(inbox, detection, warehouses, json);
    }

    private EventEnvelope event(List<ShortageComponent> components) {
        UUID eventId = UUID.randomUUID();
        RawMaterialShortageDetected payload = new RawMaterialShortageDetected(
            eventId, WORK_ORDER, WORK_ORDER,
            UUID.randomUUID(), UUID.randomUUID(),
            WAREHOUSE_CODE, components, Instant.now()
        );
        return new EventEnvelope(
            eventId, ManufacturingAggregateTypes.WORK_ORDER, WORK_ORDER,
            RawMaterialShortageDetected.EVENT_TYPE, 1,
            json.writeValueAsString(payload),
            null, null, null, null, Instant.now()
        );
    }

    @Test void each_shortage_component_raises_one_replenishment_request() {
        when(warehouses.findIdByCode(WAREHOUSE_CODE)).thenReturn(WAREHOUSE_ID);

        handler.handle(event(List.of(
            new ShortageComponent(UUID.randomUUID(), COMPONENT_A, "RM-A", "Raw A", new BigDecimal("5")),
            new ShortageComponent(UUID.randomUUID(), COMPONENT_B, "RM-B", "Raw B", new BigDecimal("3"))
        )));

        verify(detection).raiseIfNoneOpen(
            eq(COMPONENT_A), eq(WAREHOUSE_ID), eq(new BigDecimal("5")), eq(Reason.WORK_ORDER_SHORTAGE)
        );
        verify(detection).raiseIfNoneOpen(
            eq(COMPONENT_B), eq(WAREHOUSE_ID), eq(new BigDecimal("3")), eq(Reason.WORK_ORDER_SHORTAGE)
        );
        verify(detection, times(2)).raiseIfNoneOpen(any(), any(), any(), any());
    }

    @Test void empty_components_no_ops() {
        when(warehouses.findIdByCode(WAREHOUSE_CODE)).thenReturn(WAREHOUSE_ID);

        handler.handle(event(List.of()));

        verify(detection, never()).raiseIfNoneOpen(any(), any(), any(), any());
        verify(inbox).recordProcessed(any());
    }

    @Test void non_positive_shortage_quantity_is_skipped() {
        when(warehouses.findIdByCode(WAREHOUSE_CODE)).thenReturn(WAREHOUSE_ID);

        handler.handle(event(List.of(
            new ShortageComponent(UUID.randomUUID(), COMPONENT_A, "RM-A", "Raw A", BigDecimal.ZERO),
            new ShortageComponent(UUID.randomUUID(), COMPONENT_B, "RM-B", "Raw B", new BigDecimal("3"))
        )));

        verify(detection, never()).raiseIfNoneOpen(
            eq(COMPONENT_A), any(), any(), any()
        );
        verify(detection).raiseIfNoneOpen(
            eq(COMPONENT_B), eq(WAREHOUSE_ID), eq(new BigDecimal("3")), eq(Reason.WORK_ORDER_SHORTAGE)
        );
    }
}
