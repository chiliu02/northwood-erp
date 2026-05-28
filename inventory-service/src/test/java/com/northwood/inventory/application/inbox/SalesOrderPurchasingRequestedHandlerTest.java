package com.northwood.inventory.application.inbox;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.northwood.inventory.application.WarehouseLookup;
import com.northwood.inventory.application.replenishment.ReplenishmentDetectionService;
import com.northwood.sales.domain.SalesAggregateTypes;
import com.northwood.sales.domain.events.SalesOrderPurchasingRequested;
import com.northwood.sales.domain.events.SalesOrderPurchasingRequested.RequestedLine;
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
class SalesOrderPurchasingRequestedHandlerTest {

    private static final UUID WAREHOUSE_ID = UUID.randomUUID();
    private static final String WAREHOUSE_CODE = "MAIN";
    private static final UUID SALES_ORDER = UUID.randomUUID();
    private static final UUID LINE_A = UUID.randomUUID();
    private static final UUID LINE_B = UUID.randomUUID();
    private static final UUID PRODUCT_A = UUID.randomUUID();
    private static final UUID PRODUCT_B = UUID.randomUUID();

    @Mock InboxPort inbox;
    @Mock ReplenishmentDetectionService detection;
    @Mock WarehouseLookup warehouses;

    private final ObjectMapper json = new ObjectMapper();
    private SalesOrderPurchasingRequestedHandler handler;

    @BeforeEach
    void setUp() {
        handler = new SalesOrderPurchasingRequestedHandler(inbox, detection, warehouses, json);
    }

    private EventEnvelope event(List<RequestedLine> lines) {
        UUID eventId = UUID.randomUUID();
        SalesOrderPurchasingRequested payload = new SalesOrderPurchasingRequested(
            eventId, SALES_ORDER, SALES_ORDER, WAREHOUSE_CODE, lines, Instant.now()
        );
        return new EventEnvelope(
            eventId, SalesAggregateTypes.SALES_ORDER, SALES_ORDER,
            SalesOrderPurchasingRequested.EVENT_TYPE, 1,
            json.writeValueAsString(payload),
            null, null, null, null, Instant.now()
        );
    }

    @Test void each_line_raises_one_replenishment_with_back_reference() {
        when(warehouses.findIdByCode(WAREHOUSE_CODE)).thenReturn(WAREHOUSE_ID);

        handler.handle(event(List.of(
            new RequestedLine(LINE_A, 10, PRODUCT_A, "SKU-A", "Product A", new BigDecimal("5")),
            new RequestedLine(LINE_B, 20, PRODUCT_B, "SKU-B", "Product B", new BigDecimal("3"))
        )));

        verify(detection).raiseForSalesOrderShortage(
            eq(PRODUCT_A), eq(WAREHOUSE_ID), eq(new BigDecimal("5")), eq(SALES_ORDER), eq(LINE_A)
        );
        verify(detection).raiseForSalesOrderShortage(
            eq(PRODUCT_B), eq(WAREHOUSE_ID), eq(new BigDecimal("3")), eq(SALES_ORDER), eq(LINE_B)
        );
        verify(detection, times(2)).raiseForSalesOrderShortage(any(), any(), any(), any(), any());
    }

    @Test void non_positive_shortage_quantity_is_skipped() {
        when(warehouses.findIdByCode(WAREHOUSE_CODE)).thenReturn(WAREHOUSE_ID);

        handler.handle(event(List.of(
            new RequestedLine(LINE_A, 10, PRODUCT_A, "SKU-A", "Product A", BigDecimal.ZERO),
            new RequestedLine(LINE_B, 20, PRODUCT_B, "SKU-B", "Product B", new BigDecimal("3"))
        )));

        verify(detection, never()).raiseForSalesOrderShortage(
            eq(PRODUCT_A), any(), any(), any(), any()
        );
        verify(detection).raiseForSalesOrderShortage(
            eq(PRODUCT_B), eq(WAREHOUSE_ID), eq(new BigDecimal("3")), eq(SALES_ORDER), eq(LINE_B)
        );
    }

    @Test void empty_lines_no_ops() {
        when(warehouses.findIdByCode(WAREHOUSE_CODE)).thenReturn(WAREHOUSE_ID);

        handler.handle(event(List.of()));

        verify(detection, never()).raiseForSalesOrderShortage(any(), any(), any(), any(), any());
        verify(inbox).recordProcessed(any());
    }
}
