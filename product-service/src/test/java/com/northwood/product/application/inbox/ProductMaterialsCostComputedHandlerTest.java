package com.northwood.product.application.inbox;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.northwood.manufacturing.domain.events.ProductMaterialsCostComputed;
import com.northwood.product.domain.ProductAggregateTypes;
import com.northwood.product.application.ProductService;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.EventEnvelope;
import com.northwood.shared.domain.Currencies;
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
class ProductMaterialsCostComputedHandlerTest {

    private static final UUID PRODUCT = UUID.randomUUID();
    private static final BigDecimal COST = new BigDecimal("42.50");
    private static final String CURRENCY = Currencies.AUD;

    @Mock InboxPort inbox;
    @Mock ProductService productService;

    private final ObjectMapper json = new ObjectMapper();
    private ProductMaterialsCostComputedHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ProductMaterialsCostComputedHandler(inbox, productService, json);
    }

    private EventEnvelope event(BigDecimal cost, String currency) {
        UUID eventId = UUID.randomUUID();
        ProductMaterialsCostComputed payload = new ProductMaterialsCostComputed(
            eventId, PRODUCT, cost, currency, "supplier_price_change", Instant.now()
        );
        return new EventEnvelope(
            eventId, ProductAggregateTypes.PRODUCT, PRODUCT,
            ProductMaterialsCostComputed.EVENT_TYPE, 1,
            json.writeValueAsString(payload),
            null, null, null, null, Instant.now()
        );
    }

    @Test void happy_path_stamps_standard_cost() {
        handler.handle(event(COST, CURRENCY));

        verify(productService).changeStandardCost(eq(PRODUCT), eq(COST), eq(CURRENCY));
        verify(inbox).recordProcessed(any());
    }

    @Test void null_materials_cost_skips_mutation_but_records_processed() {
        handler.handle(event(null, CURRENCY));

        verify(productService, never()).changeStandardCost(any(), any(), any());
        verify(inbox).recordProcessed(any());
    }

    @Test void null_currency_skips_mutation_but_records_processed() {
        handler.handle(event(COST, null));

        verify(productService, never()).changeStandardCost(any(), any(), any());
        verify(inbox).recordProcessed(any());
    }

    @Test void already_processed_short_circuits() {
        EventEnvelope envelope = event(COST, CURRENCY);
        when(inbox.alreadyProcessed(eq(envelope.eventId()), eq(ProductMaterialsCostComputedHandler.CONSUMER_NAME)))
            .thenReturn(true);

        handler.handle(envelope);

        verify(productService, never()).changeStandardCost(any(), any(), any());
        verify(inbox, never()).recordProcessed(any());
    }
}
