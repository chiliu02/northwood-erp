package com.northwood.purchasing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northwood.purchasing.domain.events.SupplierProductPriceChanged;
import com.northwood.shared.domain.Currencies;
import com.northwood.shared.domain.DomainEvent;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SupplierProductPriceTest {

    private static final UUID SUPPLIER = UUID.randomUUID();
    private static final UUID PRODUCT = UUID.randomUUID();

    // ============================================================ register

    @Test void register_emits_PriceChanged_with_null_oldPrice() {
        SupplierProductPrice price = SupplierProductPrice.register(
            SUPPLIER, PRODUCT, Currencies.AUD, new BigDecimal("12.50")
        );
        assertThat(price.version()).isZero();
        assertThat(price.unitPrice()).isEqualByComparingTo("12.50");

        List<DomainEvent> events = price.pullPendingEvents();
        assertThat(events).hasSize(1);
        SupplierProductPriceChanged e = (SupplierProductPriceChanged) events.get(0);
        assertThat(e.oldUnitPrice()).isNull();
        assertThat(e.newUnitPrice()).isEqualByComparingTo("12.50");
        assertThat(e.supplierId()).isEqualTo(SUPPLIER);
        assertThat(e.productId()).isEqualTo(PRODUCT);
        assertThat(e.currencyCode()).isEqualTo(Currencies.AUD);
        assertThat(e.aggregateId()).isEqualTo(price.id().value());
    }

    @Test void register_rejects_null_supplierId() {
        assertThatThrownBy(() -> SupplierProductPrice.register(null, PRODUCT, Currencies.AUD, BigDecimal.ONE))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("supplierId");
    }

    @Test void register_rejects_null_productId() {
        assertThatThrownBy(() -> SupplierProductPrice.register(SUPPLIER, null, Currencies.AUD, BigDecimal.ONE))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("productId");
    }

    @Test void register_rejects_null_currencyCode() {
        assertThatThrownBy(() -> SupplierProductPrice.register(SUPPLIER, PRODUCT, null, BigDecimal.ONE))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("currencyCode");
    }

    @Test void register_rejects_null_unitPrice() {
        assertThatThrownBy(() -> SupplierProductPrice.register(SUPPLIER, PRODUCT, Currencies.AUD, null))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unitPrice");
    }

    @Test void register_rejects_blank_currencyCode() {
        assertThatThrownBy(() -> SupplierProductPrice.register(SUPPLIER, PRODUCT, "  ", BigDecimal.ONE))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("currencyCode");
    }

    @Test void register_rejects_zero_unitPrice() {
        assertThatThrownBy(() -> SupplierProductPrice.register(SUPPLIER, PRODUCT, Currencies.AUD, BigDecimal.ZERO))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("> 0");
    }

    @Test void register_rejects_negative_unitPrice() {
        assertThatThrownBy(() -> SupplierProductPrice.register(SUPPLIER, PRODUCT, Currencies.AUD, new BigDecimal("-1")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("> 0");
    }

    // ======================================================== updatePrice

    @Test void updatePrice_emits_PriceChanged_with_old_and_new() {
        SupplierProductPrice price = SupplierProductPrice.reconstitute(
            SupplierProductPriceId.newId(), SUPPLIER, PRODUCT, Currencies.AUD,
            new BigDecimal("10.00"), 1L
        );
        price.updatePrice(new BigDecimal("12.50"));

        List<DomainEvent> events = price.pullPendingEvents();
        assertThat(events).hasSize(1);
        SupplierProductPriceChanged e = (SupplierProductPriceChanged) events.get(0);
        assertThat(e.oldUnitPrice()).isEqualByComparingTo("10.00");
        assertThat(e.newUnitPrice()).isEqualByComparingTo("12.50");
        assertThat(price.unitPrice()).isEqualByComparingTo("12.50");
    }

    @Test void no_op_on_unchanged_price_emits_nothing() {
        SupplierProductPrice price = SupplierProductPrice.reconstitute(
            SupplierProductPriceId.newId(), SUPPLIER, PRODUCT, Currencies.AUD,
            new BigDecimal("10.00"), 1L
        );
        // 10.00 vs 10.0 — compareTo treats as equal.
        price.updatePrice(new BigDecimal("10.0"));

        assertThat(price.pullPendingEvents()).isEmpty();
        assertThat(price.unitPrice()).isEqualByComparingTo("10.00");
    }

    @Test void updatePrice_rejects_null_newUnitPrice() {
        SupplierProductPrice price = SupplierProductPrice.reconstitute(
            SupplierProductPriceId.newId(), SUPPLIER, PRODUCT, Currencies.AUD,
            new BigDecimal("10.00"), 1L
        );
        assertThatThrownBy(() -> price.updatePrice(null))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("newUnitPrice");
    }

    @Test void updatePrice_rejects_zero_newUnitPrice() {
        SupplierProductPrice price = SupplierProductPrice.reconstitute(
            SupplierProductPriceId.newId(), SUPPLIER, PRODUCT, Currencies.AUD,
            new BigDecimal("10.00"), 1L
        );
        assertThatThrownBy(() -> price.updatePrice(BigDecimal.ZERO))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("> 0");
    }

    @Test void updatePrice_rejects_negative_newUnitPrice() {
        SupplierProductPrice price = SupplierProductPrice.reconstitute(
            SupplierProductPriceId.newId(), SUPPLIER, PRODUCT, Currencies.AUD,
            new BigDecimal("10.00"), 1L
        );
        assertThatThrownBy(() -> price.updatePrice(new BigDecimal("-1")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("> 0");
    }

    // ============================================================== reconstitute

    @Test void reconstitute_preserves_state_and_emits_nothing() {
        SupplierProductPriceId id = SupplierProductPriceId.newId();
        SupplierProductPrice price = SupplierProductPrice.reconstitute(
            id, SUPPLIER, PRODUCT, Currencies.AUD, new BigDecimal("10.00"), 5L
        );
        assertThat(price.id()).isEqualTo(id);
        assertThat(price.version()).isEqualTo(5L);
        assertThat(price.pullPendingEvents()).isEmpty();
    }
}
