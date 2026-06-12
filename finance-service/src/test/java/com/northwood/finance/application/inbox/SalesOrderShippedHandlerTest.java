package com.northwood.finance.application.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.northwood.finance.application.CustomerInvoiceService;
import com.northwood.finance.application.JournalEntryService;
import com.northwood.finance.application.PaymentService;
import com.northwood.finance.application.ProductCardLookup;
import com.northwood.finance.domain.CustomerInvoiceId;
import com.northwood.sales.domain.PaymentTerms;
import com.northwood.sales.domain.SalesAggregateTypes;
import com.northwood.sales.domain.events.SalesOrderShipped;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.EventEnvelope;
import com.northwood.shared.domain.Currencies;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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
class SalesOrderShippedHandlerTest {

    private static final UUID SO = UUID.randomUUID();

    @Mock InboxPort inbox;
    @Mock CustomerInvoiceService invoices;
    @Mock PaymentService payments;
    @Mock JournalEntryService journals;
    @Mock ProductCardLookup productCards;

    private final ObjectMapper json = new ObjectMapper();
    private SalesOrderShippedHandler handler;

    @BeforeEach
    void setUp() {
        handler = new SalesOrderShippedHandler(inbox, invoices, payments, journals, productCards, json);
    }

    private EventEnvelope event(String paymentTerms) {
        return event(paymentTerms, new BigDecimal("100.00"));
    }

    private EventEnvelope event(String paymentTerms, BigDecimal unitPrice) {
        UUID eventId = UUID.randomUUID();
        SalesOrderShipped payload = new SalesOrderShipped(
            eventId, SO, "SO-001", UUID.randomUUID(), "SHP-001",
            UUID.randomUUID(), "CUST-001", "Acme",
            LocalDate.now(), Currencies.AUD, paymentTerms,
            List.of(new SalesOrderShipped.ShippedLine(
                UUID.randomUUID(), 10, UUID.randomUUID(), "SKU", "Product",
                new BigDecimal("2"), unitPrice, new BigDecimal("0.10"), new BigDecimal("60.00")
            )),
            true,
            Instant.now()
        );
        return new EventEnvelope(
            eventId, SalesAggregateTypes.SALES_ORDER, SO,
            SalesOrderShipped.EVENT_TYPE, 1,
            json.writeValueAsString(payload),
            null, null, null, null, Instant.now()
        );
    }

    @Test void on_shipment_invoices_only_no_auto_payment() {
        handler.handle(event(PaymentTerms.ON_SHIPMENT.code()));

        verify(invoices).createFromShippedOrder(any(SalesOrderShipped.class));
        verify(payments, never()).recordCashOnDeliveryPayment(any(), any());
        verify(inbox).recordProcessed(any());
    }

    @Test void cod_auto_records_full_payment_against_the_new_invoice() {
        CustomerInvoiceId invoiceId = CustomerInvoiceId.newId();
        when(invoices.createFromShippedOrder(any(SalesOrderShipped.class))).thenReturn(invoiceId);

        handler.handle(event(PaymentTerms.CASH_ON_DELIVERY.code()));

        verify(invoices).createFromShippedOrder(any(SalesOrderShipped.class));
        verify(payments).recordCashOnDeliveryPayment(eq(invoiceId.value()), any(LocalDate.class));
        verify(inbox).recordProcessed(any());
    }

    @SuppressWarnings("unchecked")
    @Test void posts_cogs_for_a_paid_line_not_free_of_charge() {
        // standard_cost projection unstubbed → Optional.empty() → falls back to
        // the shipment-stamped unitCost (60.00); qty 2 → cost 120.00.
        handler.handle(event(PaymentTerms.ON_SHIPMENT.code(), new BigDecimal("100.00")));

        ArgumentCaptor<List<JournalEntryService.LineCost>> cap = ArgumentCaptor.forClass(List.class);
        verify(journals).postShipmentCost(any(), any(), cap.capture(), any(), any());
        List<JournalEntryService.LineCost> costs = cap.getValue();
        assertThat(costs).hasSize(1);
        assertThat(costs.get(0).amount()).isEqualByComparingTo("120.00");
        assertThat(costs.get(0).freeOfCharge()).isFalse();
    }

    @SuppressWarnings("unchecked")
    @Test void posts_cogs_as_free_of_charge_for_a_zero_price_line() {
        // A zero sale-price line is free-of-charge: its cost still posts (the
        // goods left stock) but routes to Promotions, not COGS.
        handler.handle(event(PaymentTerms.ON_SHIPMENT.code(), BigDecimal.ZERO));

        ArgumentCaptor<List<JournalEntryService.LineCost>> cap = ArgumentCaptor.forClass(List.class);
        verify(journals).postShipmentCost(any(), any(), cap.capture(), any(), any());
        assertThat(cap.getValue().get(0).freeOfCharge()).isTrue();
        assertThat(cap.getValue().get(0).amount()).isEqualByComparingTo("120.00");
    }

    @Test void already_processed_short_circuits() {
        EventEnvelope envelope = event(PaymentTerms.ON_SHIPMENT.code());
        when(inbox.alreadyProcessed(eq(envelope.eventId()),
            eq(SalesOrderShippedHandler.CONSUMER_NAME))).thenReturn(true);

        handler.handle(envelope);

        verifyNoInteractions(invoices);
        verifyNoInteractions(payments);
        verify(inbox, never()).recordProcessed(any());
    }

    @Test void wrong_event_type_is_no_op() {
        EventEnvelope wrong = new EventEnvelope(
            UUID.randomUUID(), SalesAggregateTypes.SALES_ORDER, UUID.randomUUID(),
            "sales.SomethingElse", 1, "{}",
            null, null, null, null, Instant.now()
        );

        handler.handle(wrong);

        verify(inbox, never()).alreadyProcessed(any(), any());
        verifyNoInteractions(invoices);
        verifyNoInteractions(payments);
    }
}
