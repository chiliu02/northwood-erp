package com.northwood.sales.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.northwood.sales.application.CustomerLookup.CustomerSummary;
import com.northwood.sales.application.ProductCardLookup.CatalogPrice;
import com.northwood.sales.application.SalesOrderService.ProductDiscontinuedException;
import com.northwood.sales.application.SalesOrderService.UnknownPriceException;
import com.northwood.sales.application.dto.PlaceOrderCommand;
import com.northwood.sales.application.dto.PlaceOrderCommand.OrderLine;
import com.northwood.sales.application.saga.SalesOrderFulfilmentSagaManager;
import com.northwood.sales.domain.Customer;
import com.northwood.sales.domain.PaymentTerms;
import com.northwood.sales.domain.SalesOrder;
import com.northwood.sales.domain.SalesOrderRepository;
import com.northwood.shared.domain.Currencies;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SalesOrderServicePlaceOrderTest {

    private static final UUID CUSTOMER_ID = UUID.randomUUID();
    private static final UUID PRODUCT_ID = UUID.randomUUID();

    @Mock SalesOrderRepository orders;
    @Mock SalesOrderFulfilmentSagaManager sagaManager;
    @Mock CustomerLookup customers;
    @Mock ProductCardLookup productCards;

    private SalesOrderService service;

    @BeforeEach
    void setUp() {
        service = new SalesOrderService(orders, sagaManager, customers, productCards);
        // Lenient so nested @Nested classes can stub a different customer code
        // without tripping strict-mode UnnecessaryStubbing on this shared setup.
        Mockito.lenient().when(customers.findByCode("CUST-1")).thenReturn(Optional.of(
            new CustomerSummary(CUSTOMER_ID, "CUST-1", "Customer One", Customer.Status.ACTIVE, PaymentTerms.ON_SHIPMENT)
        ));
    }

    private PlaceOrderCommand commandWithUnitPrice(BigDecimal unitPrice) {
        return new PlaceOrderCommand(
            "SO-1001", "CUST-1", LocalDate.now().plusDays(7), Currencies.AUD, null,
            List.of(new OrderLine(
                PRODUCT_ID, "SKU-1", "Widget",
                new BigDecimal("2"), unitPrice, BigDecimal.ZERO
            ))
        );
    }

    @Test void placeOrder_rejects_discontinued_product_even_when_caller_supplies_unitPrice() {
        when(productCards.findByProductId(PRODUCT_ID)).thenReturn(Optional.of(
            new CatalogPrice(new BigDecimal("10.00"), Currencies.AUD, Instant.parse("2026-05-14T03:15:00Z"))
        ));

        assertThatThrownBy(() -> service.placeOrder(commandWithUnitPrice(new BigDecimal("99.99"))))
            .isInstanceOf(ProductDiscontinuedException.class)
            .hasMessageContaining("SKU-1")
            .hasMessageContaining("2026-05-14T03:15:00Z");

        verify(orders, never()).save(any());
        verify(sagaManager, never()).insertStarted(any(), any());
    }

    @Test void placeOrder_rejects_discontinued_product_when_using_catalog_unitPrice() {
        when(productCards.findByProductId(PRODUCT_ID)).thenReturn(Optional.of(
            new CatalogPrice(new BigDecimal("10.00"), Currencies.AUD, Instant.parse("2026-05-14T03:15:00Z"))
        ));

        assertThatThrownBy(() -> service.placeOrder(commandWithUnitPrice(null)))
            .isInstanceOf(ProductDiscontinuedException.class);

        verify(orders, never()).save(any());
    }

    @Test void placeOrder_accepts_live_product() {
        when(productCards.findByProductId(PRODUCT_ID)).thenReturn(Optional.of(
            new CatalogPrice(new BigDecimal("10.00"), Currencies.AUD, null)
        ));

        service.placeOrder(commandWithUnitPrice(null));

        verify(orders).save(any());
        verify(sagaManager).insertStarted(any(), any());
    }

    @Test void placeOrder_rejects_unpriced_stub_when_no_unitPrice_supplied() {
        // ProductCreated seeded a stub but SalesPriceChanged hasn't fired yet —
        // or the product is a raw material that's never sold (NULL price for
        // its lifetime). Without a caller-supplied unitPrice, the line is
        // unsellable, same outcome as catalog.isEmpty().
        when(productCards.findByProductId(PRODUCT_ID)).thenReturn(Optional.of(
            new CatalogPrice(null, null, null)
        ));

        assertThatThrownBy(() -> service.placeOrder(commandWithUnitPrice(null)))
            .isInstanceOf(UnknownPriceException.class)
            .hasMessageContaining("SKU-1");

        verify(orders, never()).save(any());
        verify(sagaManager, never()).insertStarted(any(), any());
    }

    @Test void placeOrder_accepts_unpriced_stub_when_caller_supplies_unitPrice() {
        // Override price path: stub row exists but has no catalog currency to
        // compare against, so the unitPrice is accepted without a currency
        // assertion (same race-tolerance the empty-catalog path provides).
        when(productCards.findByProductId(PRODUCT_ID)).thenReturn(Optional.of(
            new CatalogPrice(null, null, null)
        ));

        service.placeOrder(commandWithUnitPrice(new BigDecimal("42.00")));

        verify(orders).save(any());
        verify(sagaManager).insertStarted(any(), any());
    }

    /**
     * Per-order {@code payment_terms} flows from customer default through the snapshot,
     * with a command-level override that wins when supplied. No behavior
     * change to the saga yet (Slice B+ hangs branching on it).
     */
    @Nested class PaymentTermsSnapshot {

        @BeforeEach void priceLiveProduct() {
            when(productCards.findByProductId(PRODUCT_ID)).thenReturn(Optional.of(
                new CatalogPrice(new BigDecimal("10.00"), Currencies.AUD, null)
            ));
        }

        @Test void snapshots_customer_default_when_command_has_no_override() {
            when(customers.findByCode("CUST-PREPAY")).thenReturn(Optional.of(
                new CustomerSummary(CUSTOMER_ID, "CUST-PREPAY", "Prepay Co",
                    Customer.Status.ACTIVE, PaymentTerms.PREPAYMENT)
            ));

            service.placeOrder(new PlaceOrderCommand(
                "SO-PT-1", "CUST-PREPAY", LocalDate.now().plusDays(7), Currencies.AUD, null,
                List.of(new OrderLine(PRODUCT_ID, "SKU-1", "Widget",
                    new BigDecimal("1"), null, BigDecimal.ZERO))
            ));

            ArgumentCaptor<SalesOrder> cap = ArgumentCaptor.forClass(SalesOrder.class);
            verify(orders).save(cap.capture());
            assertThat(cap.getValue().paymentTerms()).isEqualTo(PaymentTerms.PREPAYMENT);
        }

        @Test void command_override_wins_over_customer_default() {
            // Customer's default is on_shipment (the existing CUST-1 mock from
            // setUp); command overrides to prepayment.
            service.placeOrder(new PlaceOrderCommand(
                "SO-PT-2", "CUST-1", LocalDate.now().plusDays(7), Currencies.AUD,
                PaymentTerms.PREPAYMENT.dbValue(),
                List.of(new OrderLine(PRODUCT_ID, "SKU-1", "Widget",
                    new BigDecimal("1"), null, BigDecimal.ZERO))
            ));

            ArgumentCaptor<SalesOrder> cap = ArgumentCaptor.forClass(SalesOrder.class);
            verify(orders).save(cap.capture());
            assertThat(cap.getValue().paymentTerms()).isEqualTo(PaymentTerms.PREPAYMENT);
        }

        @Test void rejects_unknown_payment_terms_wire_value() {
            assertThatThrownBy(() -> service.placeOrder(new PlaceOrderCommand(
                "SO-PT-3", "CUST-1", LocalDate.now().plusDays(7), Currencies.AUD,
                "letter_of_credit",   // not in the enum
                List.of(new OrderLine(PRODUCT_ID, "SKU-1", "Widget",
                    new BigDecimal("1"), null, BigDecimal.ZERO))
            )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payment_terms")
                .hasMessageContaining("letter_of_credit");

            verify(orders, never()).save(any());
        }
    }
}
