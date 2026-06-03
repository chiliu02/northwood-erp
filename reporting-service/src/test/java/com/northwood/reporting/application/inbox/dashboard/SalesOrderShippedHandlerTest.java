package com.northwood.reporting.application.inbox.dashboard;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.northwood.reporting.application.inbox.FinancialDashboardProjection;
import com.northwood.sales.domain.SalesAggregateTypes;
import com.northwood.sales.domain.events.SalesOrderShipped;
import com.northwood.sales.domain.events.SalesOrderShipped.ShippedLine;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.EventEnvelope;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class SalesOrderShippedHandlerTest {

    private static final UUID ORDER = UUID.randomUUID();
    private static final Instant SHIPPED_AT = Instant.parse("2026-06-03T02:00:00Z");

    @Mock InboxPort inbox;
    @Mock FinancialDashboardProjection projection;

    private final ObjectMapper json = new ObjectMapper();
    private SalesOrderShippedHandler handler;

    @BeforeEach
    void setUp() {
        handler = new SalesOrderShippedHandler(inbox, projection, json);
    }

    private EventEnvelope event(List<ShippedLine> lines) {
        UUID eventId = UUID.randomUUID();
        SalesOrderShipped payload = new SalesOrderShipped(
            eventId, ORDER, "SO-1", UUID.randomUUID(), "SH-1",
            UUID.randomUUID(), "CUST-001", "Acme",
            LocalDate.of(2026, 6, 3), "AUD", "on_shipment",
            lines, SHIPPED_AT
        );
        return new EventEnvelope(
            eventId, SalesAggregateTypes.SALES_ORDER, ORDER,
            SalesOrderShipped.EVENT_TYPE, 1,
            json.writeValueAsString(payload),
            null, null, null, null, Instant.now()
        );
    }

    private static ShippedLine line(BigDecimal qty, BigDecimal unitPrice, BigDecimal unitCost) {
        return new ShippedLine(UUID.randomUUID(), 1, UUID.randomUUID(), "SKU", "Name",
            qty, unitPrice, BigDecimal.ZERO, unitCost);
    }

    @Test void sums_qty_times_unit_cost_over_priced_lines() {
        handler.handle(event(List.of(
            line(new BigDecimal("2"), new BigDecimal("650.00"), new BigDecimal("320.00")),
            line(new BigDecimal("3"), new BigDecimal("220.00"), new BigDecimal("120.00"))
        )));

        // 2×320 + 3×120 = 640 + 360 = 1000
        verify(projection).recordCostOfGoodsSold(
            argThat(v -> v.compareTo(new BigDecimal("1000.00")) == 0), eq("AUD"), eq(SHIPPED_AT));
        verify(inbox).recordProcessed(any());
    }

    @Test void excludes_free_of_charge_lines() {
        handler.handle(event(List.of(
            line(new BigDecimal("2"), new BigDecimal("650.00"), new BigDecimal("320.00")), // priced → counts
            line(new BigDecimal("1"), BigDecimal.ZERO, new BigDecimal("500.00"))            // free → excluded
        )));

        // only 2×320 = 640; the zero-price line's cost is a promotions expense, not COGS
        verify(projection).recordCostOfGoodsSold(
            argThat(v -> v.compareTo(new BigDecimal("640.00")) == 0), eq("AUD"), eq(SHIPPED_AT));
    }

    @Test void already_processed_short_circuits() {
        EventEnvelope envelope = event(List.of(
            line(new BigDecimal("1"), new BigDecimal("100.00"), new BigDecimal("40.00"))));
        when(inbox.alreadyProcessed(eq(envelope.eventId()), eq(SalesOrderShippedHandler.CONSUMER_NAME)))
            .thenReturn(true);

        handler.handle(envelope);

        verify(projection, never()).recordCostOfGoodsSold(any(), any(), any());
        verify(inbox, never()).recordProcessed(any());
    }
}
