package com.northwood.sales.application.inbox;

import com.northwood.sales.domain.SalesOrder;

import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.READY_TO_SHIP;
import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.STOCK_RESERVED;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.northwood.inventory.domain.InventoryAggregateTypes;
import com.northwood.inventory.domain.events.StockReserved;
import com.northwood.sales.application.SalesOrderReadyToShipEmitter;
import com.northwood.sales.application.saga.SalesOrderFulfilmentSagaManager;
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
class StockReservedHandlerTest {

    private static final UUID SO = UUID.randomUUID();

    @Mock InboxPort inbox;
    @Mock SalesOrderFulfilmentSagaManager sagaManager;
    @Mock SalesOrderHeaderStatusProjection statusProjection;
    @Mock SalesOrderReadyToShipEmitter readyToShipEmitter;

    private final ObjectMapper json = new ObjectMapper();
    private StockReservedHandler handler;

    @BeforeEach
    void setUp() {
        handler = new StockReservedHandler(inbox, sagaManager, statusProjection, readyToShipEmitter, json);
    }

    private EventEnvelope event(String status, BigDecimal shortage) {
        UUID eventId = UUID.randomUUID();
        StockReserved payload = new StockReserved(
            eventId, SO, SO, UUID.randomUUID(), status,
            List.of(new StockReserved.ReservedLine(
                10, UUID.randomUUID(),
                new BigDecimal("3"),
                shortage.signum() == 0 ? new BigDecimal("3") : new BigDecimal("3").subtract(shortage),
                shortage, status
            )),
            Instant.now()
        );
        return new EventEnvelope(
            eventId, InventoryAggregateTypes.STOCK_RESERVATION, UUID.randomUUID(),
            StockReserved.EVENT_TYPE, 1,
            json.writeValueAsString(payload),
            null, null, null, null, Instant.now()
        );
    }

    @Test void full_reservation_projects_in_fulfilment_and_emits_ready_to_ship() {
        when(sagaManager.applyStockReserved(eq(SO), eq("reserved"), any())).thenReturn(READY_TO_SHIP);

        handler.handle(event("reserved", BigDecimal.ZERO));

        verify(sagaManager).applyStockReserved(eq(SO), eq("reserved"), any());
        verify(statusProjection).markStatus(SO, SalesOrder.Status.IN_FULFILMENT);
        verify(readyToShipEmitter).emitReadyToShip(SO);
        verify(inbox).recordProcessed(any());
    }

    @Test void partial_passes_shortage_map_and_does_not_emit_ready_to_ship() {
        when(sagaManager.applyStockReserved(eq(SO), eq("partially_reserved"), any())).thenReturn(STOCK_RESERVED);

        handler.handle(event("partially_reserved", new BigDecimal("2")));

        verify(sagaManager).applyStockReserved(eq(SO), eq("partially_reserved"), any());
        verify(readyToShipEmitter, never()).emitReadyToShip(any());
    }

    @Test void already_processed_short_circuits() {
        EventEnvelope envelope = event("reserved", BigDecimal.ZERO);
        when(inbox.alreadyProcessed(eq(envelope.eventId()), eq(StockReservedHandler.CONSUMER_NAME)))
            .thenReturn(true);

        handler.handle(envelope);

        verify(sagaManager, never()).applyStockReserved(any(), any(), any());
        verifyNoInteractions(statusProjection);
        verifyNoInteractions(readyToShipEmitter);
        verify(inbox, never()).recordProcessed(any());
    }

    @Test void wrong_event_type_is_no_op() {
        EventEnvelope wrong = new EventEnvelope(
            UUID.randomUUID(), InventoryAggregateTypes.STOCK_RESERVATION, UUID.randomUUID(),
            "inventory.SomethingElse", 1, "{}",
            null, null, null, null, Instant.now()
        );

        handler.handle(wrong);

        verify(inbox, never()).alreadyProcessed(any(), any());
        verify(sagaManager, never()).applyStockReserved(any(), any(), any());
    }
}
