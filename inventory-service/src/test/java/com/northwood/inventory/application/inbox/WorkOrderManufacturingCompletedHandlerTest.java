package com.northwood.inventory.application.inbox;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.northwood.inventory.application.StockBalanceWriter;
import com.northwood.inventory.application.StockMovementWriter;
import com.northwood.inventory.application.WarehouseLookup;
import com.northwood.inventory.application.WipBalanceWriter;
import com.northwood.inventory.domain.StockMovementDirection;
import com.northwood.inventory.domain.StockMovementSourceTypes;
import com.northwood.inventory.domain.StockMovementType;
import com.northwood.manufacturing.domain.ManufacturingAggregateTypes;
import com.northwood.manufacturing.domain.events.WorkOrderManufacturingCompleted;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.EventEnvelope;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class WorkOrderManufacturingCompletedHandlerTest {

    @Mock InboxPort inbox;
    @Mock StockBalanceWriter stockBalances;
    @Mock WipBalanceWriter wipBalances;
    @Mock WarehouseLookup warehouses;
    @Mock StockMovementWriter movements;

    private final ObjectMapper json = new ObjectMapper();
    private WorkOrderManufacturingCompletedHandler handler;

    private static final UUID WAREHOUSE = UUID.randomUUID();
    private static final UUID PRODUCT = UUID.randomUUID();
    private static final UUID WO = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        handler = new WorkOrderManufacturingCompletedHandler(
            inbox, json, stockBalances, wipBalances, warehouses, movements
        );
    }

    private EventEnvelope event(UUID parentWorkOrderId) {
        UUID eventId = UUID.randomUUID();
        WorkOrderManufacturingCompleted payload = new WorkOrderManufacturingCompleted(
            eventId, WO, "WO-001",
            UUID.randomUUID(), UUID.randomUUID(), parentWorkOrderId,
            PRODUCT, "FG-001", new BigDecimal("5"), Instant.now()
        );
        return new EventEnvelope(
            eventId, ManufacturingAggregateTypes.WORK_ORDER, WO,
            WorkOrderManufacturingCompleted.EVENT_TYPE, 1,
            json.writeValueAsString(payload),
            null, null, null, null, Instant.now()
        );
    }

    @Test void top_level_wo_bumps_stock_balance_and_records_movement() {
        when(warehouses.findIdByCode("MAIN")).thenReturn(WAREHOUSE);

        handler.handle(event(null));

        verify(stockBalances).bump(WAREHOUSE, PRODUCT, new BigDecimal("5"));
        verify(movements).record(
            eq(WAREHOUSE), eq(PRODUCT), eq("FG-001"), eq("FG-001"),
            eq(StockMovementType.FINISHED_GOODS_RECEIPT), eq(StockMovementDirection.IN),
            eq(new BigDecimal("5")), eq(null),
            eq(StockMovementSourceTypes.WORK_ORDER), eq(WO), eq(null)
        );
        verifyNoInteractions(wipBalances);
    }

    @Test void sub_assembly_child_bumps_wip_not_stock() {
        when(warehouses.findIdByCode("MAIN")).thenReturn(WAREHOUSE);

        handler.handle(event(UUID.randomUUID()));  // non-null parent → sub-assembly

        verify(wipBalances).bump(WAREHOUSE, PRODUCT, new BigDecimal("5"));
        verifyNoInteractions(stockBalances);
        verifyNoInteractions(movements);
    }

    @Test void already_processed_short_circuits() {
        EventEnvelope envelope = event(null);
        when(inbox.alreadyProcessed(eq(envelope.eventId()),
            eq(WorkOrderManufacturingCompletedHandler.CONSUMER_NAME))).thenReturn(true);

        handler.handle(envelope);

        verifyNoInteractions(stockBalances, wipBalances, movements, warehouses);
        verify(inbox, never()).recordProcessed(any());
    }

    @Test void wrong_event_type_is_no_op() {
        EventEnvelope wrong = new EventEnvelope(
            UUID.randomUUID(), ManufacturingAggregateTypes.WORK_ORDER, UUID.randomUUID(),
            "manufacturing.SomethingElse", 1, "{}",
            null, null, null, null, Instant.now()
        );

        handler.handle(wrong);

        verify(inbox, never()).alreadyProcessed(any(), any());
        verifyNoInteractions(stockBalances, wipBalances, warehouses);
    }
}
