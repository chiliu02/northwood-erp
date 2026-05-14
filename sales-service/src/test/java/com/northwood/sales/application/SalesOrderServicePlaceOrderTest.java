package com.northwood.sales.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.northwood.sales.application.CustomerLookup.CustomerSummary;
import com.northwood.sales.application.ProductPricingLookup.CatalogPrice;
import com.northwood.sales.application.SalesOrderService.ProductDiscontinuedException;
import com.northwood.sales.application.dto.PlaceOrderCommand;
import com.northwood.sales.application.dto.PlaceOrderCommand.OrderLine;
import com.northwood.sales.application.saga.SalesOrderFulfilmentSagaManager;
import com.northwood.sales.domain.Customer;
import com.northwood.sales.domain.SalesOrderRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SalesOrderServicePlaceOrderTest {

    private static final UUID CUSTOMER_ID = UUID.randomUUID();
    private static final UUID PRODUCT_ID = UUID.randomUUID();

    @Mock SalesOrderRepository orders;
    @Mock SalesOrderFulfilmentSagaManager sagaManager;
    @Mock CustomerLookup customers;
    @Mock ProductPricingLookup productPricing;

    private SalesOrderService service;

    @BeforeEach
    void setUp() {
        service = new SalesOrderService(orders, sagaManager, customers, productPricing);
        when(customers.findByCode("CUST-1")).thenReturn(Optional.of(
            new CustomerSummary(CUSTOMER_ID, "CUST-1", "Customer One", Customer.Status.ACTIVE)
        ));
    }

    private PlaceOrderCommand commandWithUnitPrice(BigDecimal unitPrice) {
        return new PlaceOrderCommand(
            "SO-1001", "CUST-1", LocalDate.now().plusDays(7), "AUD",
            List.of(new OrderLine(
                PRODUCT_ID, "SKU-1", "Widget",
                new BigDecimal("2"), unitPrice, BigDecimal.ZERO
            ))
        );
    }

    @Test void placeOrder_rejects_discontinued_product_even_when_caller_supplies_unitPrice() {
        when(productPricing.findByProductId(PRODUCT_ID)).thenReturn(Optional.of(
            new CatalogPrice(new BigDecimal("10.00"), "AUD", Instant.parse("2026-05-14T03:15:00Z"))
        ));

        assertThatThrownBy(() -> service.placeOrder(commandWithUnitPrice(new BigDecimal("99.99"))))
            .isInstanceOf(ProductDiscontinuedException.class)
            .hasMessageContaining("SKU-1")
            .hasMessageContaining("2026-05-14T03:15:00Z");

        verify(orders, never()).save(any());
        verify(sagaManager, never()).insertStarted(any(), any());
    }

    @Test void placeOrder_rejects_discontinued_product_when_using_catalog_unitPrice() {
        when(productPricing.findByProductId(PRODUCT_ID)).thenReturn(Optional.of(
            new CatalogPrice(new BigDecimal("10.00"), "AUD", Instant.parse("2026-05-14T03:15:00Z"))
        ));

        assertThatThrownBy(() -> service.placeOrder(commandWithUnitPrice(null)))
            .isInstanceOf(ProductDiscontinuedException.class);

        verify(orders, never()).save(any());
    }

    @Test void placeOrder_accepts_live_product() {
        when(productPricing.findByProductId(PRODUCT_ID)).thenReturn(Optional.of(
            new CatalogPrice(new BigDecimal("10.00"), "AUD", null)
        ));

        service.placeOrder(commandWithUnitPrice(null));

        verify(orders).save(any());
        verify(sagaManager).insertStarted(any(), any());
    }
}
