package com.northwood.purchasing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.northwood.purchasing.domain.SupplierProductPrice;
import com.northwood.purchasing.domain.SupplierProductPriceId;
import com.northwood.purchasing.domain.SupplierProductPriceRepository;
import com.northwood.shared.domain.Currencies;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SupplierProductPriceServiceTest {

    private static final UUID SUPPLIER = UUID.randomUUID();
    private static final UUID PRODUCT = UUID.randomUUID();

    @Mock SupplierProductPriceRepository prices;
    @Mock SupplierProductPriceQueryPort priceQueries;

    private SupplierProductPriceService service;

    @BeforeEach
    void setUp() {
        service = new SupplierProductPriceService(prices, priceQueries);
    }

    @Test void first_time_insert_saves_a_newly_registered_aggregate() {
        when(prices.findByKey(SUPPLIER, PRODUCT, Currencies.AUD)).thenReturn(Optional.empty());

        service.setPrice(SUPPLIER, PRODUCT, Currencies.AUD, new BigDecimal("12.50"));

        ArgumentCaptor<SupplierProductPrice> cap = ArgumentCaptor.forClass(SupplierProductPrice.class);
        verify(prices).save(cap.capture());
        SupplierProductPrice saved = cap.getValue();
        assertThat(saved.version()).isZero();
        assertThat(saved.supplierId()).isEqualTo(SUPPLIER);
        assertThat(saved.productId()).isEqualTo(PRODUCT);
        assertThat(saved.unitPrice()).isEqualByComparingTo("12.50");
    }

    @Test void updates_existing_with_changed_price_saves_mutated_aggregate() {
        SupplierProductPrice existing = SupplierProductPrice.reconstitute(
            SupplierProductPriceId.newId(), SUPPLIER, PRODUCT, Currencies.AUD,
            new BigDecimal("10.00"), 1L
        );
        when(prices.findByKey(SUPPLIER, PRODUCT, Currencies.AUD)).thenReturn(Optional.of(existing));

        service.setPrice(SUPPLIER, PRODUCT, Currencies.AUD, new BigDecimal("12.50"));

        verify(prices).save(existing);
        assertThat(existing.unitPrice()).isEqualByComparingTo("12.50");
    }

    @Test void unchanged_price_is_no_op_no_save() {
        SupplierProductPrice existing = SupplierProductPrice.reconstitute(
            SupplierProductPriceId.newId(), SUPPLIER, PRODUCT, Currencies.AUD,
            new BigDecimal("10.00"), 1L
        );
        when(prices.findByKey(SUPPLIER, PRODUCT, Currencies.AUD)).thenReturn(Optional.of(existing));

        // 10.00 vs 10.0 — compareTo treats as equal, no-op suppression at the service level.
        UUID returned = service.setPrice(SUPPLIER, PRODUCT, Currencies.AUD, new BigDecimal("10.0"));

        assertThat(returned).isEqualTo(existing.id().value());
        verify(prices, never()).save(any());
    }

    @Test void null_currency_defaults_to_AUD() {
        when(prices.findByKey(SUPPLIER, PRODUCT, Currencies.AUD)).thenReturn(Optional.empty());

        service.setPrice(SUPPLIER, PRODUCT, null, new BigDecimal("5.00"));

        verify(prices).findByKey(SUPPLIER, PRODUCT, Currencies.AUD);
    }

    @Test void rejects_zero_or_negative_price() {
        assertThatThrownBy(() -> service.setPrice(SUPPLIER, PRODUCT, Currencies.AUD, BigDecimal.ZERO))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("> 0");
        assertThatThrownBy(() -> service.setPrice(SUPPLIER, PRODUCT, Currencies.AUD, new BigDecimal("-1")))
            .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(prices);
    }

    @Test void rejects_null_supplier_or_product() {
        assertThatThrownBy(() -> service.setPrice(null, PRODUCT, Currencies.AUD, new BigDecimal("5")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("supplierId");
        assertThatThrownBy(() -> service.setPrice(SUPPLIER, null, Currencies.AUD, new BigDecimal("5")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("productId");
    }
}
