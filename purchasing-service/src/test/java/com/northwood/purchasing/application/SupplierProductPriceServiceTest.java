package com.northwood.purchasing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.northwood.purchasing.domain.SupplierProductPriceRepository;
import com.northwood.purchasing.domain.SupplierProductPriceRepository.ExistingPrice;
import com.northwood.purchasing.domain.events.SupplierProductPriceChanged;
import com.northwood.shared.application.outbox.OutboxPort;
import com.northwood.shared.application.outbox.OutboxRow;
import com.northwood.shared.application.security.CurrentUserAccessor;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class SupplierProductPriceServiceTest {

    private static final UUID SUPPLIER = UUID.randomUUID();
    private static final UUID PRODUCT = UUID.randomUUID();

    @Mock SupplierProductPriceRepository prices;
    @Mock OutboxPort outbox;
    @Mock CurrentUserAccessor currentUser;

    private final ObjectMapper json = new ObjectMapper();
    private SupplierProductPriceService service;

    @BeforeEach
    void setUp() {
        service = new SupplierProductPriceService(prices, outbox, json, currentUser);
    }

    private SupplierProductPriceChanged capturedEvent() {
        ArgumentCaptor<OutboxRow> cap = ArgumentCaptor.forClass(OutboxRow.class);
        verify(outbox).appendPending(cap.capture());
        return json.readValue(cap.getValue().getPayload(), SupplierProductPriceChanged.class);
    }

    @Test void first_time_insert_emits_with_null_old_price() {
        when(prices.find(SUPPLIER, PRODUCT, "AUD")).thenReturn(Optional.empty());
        when(currentUser.currentUsername()).thenReturn(Optional.empty());

        service.setPrice(SUPPLIER, PRODUCT, "AUD", new BigDecimal("12.50"));

        verify(prices).insert(any(), any(), any(), any(), any());
        verify(prices, never()).updatePrice(any(), any());
        SupplierProductPriceChanged event = capturedEvent();
        assertThat(event.oldUnitPrice()).isNull();
        assertThat(event.newUnitPrice()).isEqualByComparingTo("12.50");
    }

    @Test void updates_existing_with_changed_price_emits_event() {
        UUID existingId = UUID.randomUUID();
        when(prices.find(SUPPLIER, PRODUCT, "AUD")).thenReturn(Optional.of(
            new ExistingPrice(existingId, new BigDecimal("10.00"))
        ));
        when(currentUser.currentUsername()).thenReturn(Optional.empty());

        service.setPrice(SUPPLIER, PRODUCT, "AUD", new BigDecimal("12.50"));

        verify(prices).updatePrice(existingId, new BigDecimal("12.50"));
        verify(prices, never()).insert(any(), any(), any(), any(), any());
        SupplierProductPriceChanged event = capturedEvent();
        assertThat(event.oldUnitPrice()).isEqualByComparingTo("10.00");
        assertThat(event.newUnitPrice()).isEqualByComparingTo("12.50");
    }

    @Test void unchanged_price_is_no_op_no_event() {
        UUID existingId = UUID.randomUUID();
        // 10.00 vs 10.0 — compareTo treats as equal, no-op suppression should fire.
        when(prices.find(SUPPLIER, PRODUCT, "AUD")).thenReturn(Optional.of(
            new ExistingPrice(existingId, new BigDecimal("10.00"))
        ));

        UUID returned = service.setPrice(SUPPLIER, PRODUCT, "AUD", new BigDecimal("10.0"));

        assertThat(returned).isEqualTo(existingId);
        verify(prices, never()).insert(any(), any(), any(), any(), any());
        verify(prices, never()).updatePrice(any(), any());
        verifyNoInteractions(outbox);
    }

    @Test void null_currency_defaults_to_AUD() {
        when(prices.find(SUPPLIER, PRODUCT, "AUD")).thenReturn(Optional.empty());
        when(currentUser.currentUsername()).thenReturn(Optional.empty());

        service.setPrice(SUPPLIER, PRODUCT, null, new BigDecimal("5.00"));

        verify(prices).find(SUPPLIER, PRODUCT, "AUD");
    }

    @Test void rejects_zero_or_negative_price() {
        assertThatThrownBy(() -> service.setPrice(SUPPLIER, PRODUCT, "AUD", BigDecimal.ZERO))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("> 0");
        assertThatThrownBy(() -> service.setPrice(SUPPLIER, PRODUCT, "AUD", new BigDecimal("-1")))
            .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(prices, outbox);
    }

    @Test void rejects_null_supplier_or_product() {
        assertThatThrownBy(() -> service.setPrice(null, PRODUCT, "AUD", new BigDecimal("5")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("supplierId");
        assertThatThrownBy(() -> service.setPrice(SUPPLIER, null, "AUD", new BigDecimal("5")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("productId");
    }
}
