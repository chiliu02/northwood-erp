package com.northwood.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northwood.inventory.domain.events.StockAdjusted;
import com.northwood.shared.domain.DomainEvent;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StockAdjustmentTest {

    private static final UUID WAREHOUSE = UUID.randomUUID();
    private static final UUID PRODUCT = UUID.randomUUID();

    private static StockAdjustment post(StockMovementDirection direction, BigDecimal quantity) {
        return StockAdjustment.post(
            "ADJ-001", WAREHOUSE, "MAIN", PRODUCT, "SKU-1", "Widget",
            direction, quantity, "cycle count");
    }

    @Test
    void post_goes_to_posted_and_emits_one_StockAdjusted() {
        StockAdjustment a = post(StockMovementDirection.IN, new BigDecimal("25"));

        assertThat(a.status()).isEqualTo(StockAdjustment.Status.POSTED);
        assertThat(a.direction()).isEqualTo(StockMovementDirection.IN);
        assertThat(a.quantity()).isEqualByComparingTo("25");

        List<DomainEvent> events = a.pullPendingEvents();
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(StockAdjusted.class);
        StockAdjusted e = (StockAdjusted) events.get(0);
        assertThat(e.aggregateId()).isEqualTo(a.id().value());
        assertThat(e.direction()).isEqualTo(StockAdjusted.DIRECTION_IN);
        assertThat(e.quantity()).isEqualByComparingTo("25");
        assertThat(e.productId()).isEqualTo(PRODUCT);
        assertThat(e.reason()).isEqualTo("cycle count");
    }

    @Test
    void post_out_emits_direction_out() {
        StockAdjusted e = (StockAdjusted) post(StockMovementDirection.OUT, new BigDecimal("4"))
            .pullPendingEvents().get(0);
        assertThat(e.direction()).isEqualTo(StockAdjusted.DIRECTION_OUT);
    }

    @Test
    void pullPendingEvents_is_idempotent() {
        StockAdjustment a = post(StockMovementDirection.IN, new BigDecimal("1"));
        assertThat(a.pullPendingEvents()).hasSize(1);
        assertThat(a.pullPendingEvents()).isEmpty();
    }

    @Test
    void reconstitute_emits_no_events() {
        StockAdjustment a = StockAdjustment.reconstitute(
            StockAdjustmentId.newId(), "ADJ-9", WAREHOUSE, "MAIN", PRODUCT, "SKU", "W",
            StockMovementDirection.IN, new BigDecimal("3"), "count", StockAdjustment.Status.POSTED, 1L);
        assertThat(a.pullPendingEvents()).isEmpty();
    }

    @Test
    void post_rejects_zero_or_negative_quantity() {
        assertThatThrownBy(() -> post(StockMovementDirection.IN, BigDecimal.ZERO))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> post(StockMovementDirection.OUT, new BigDecimal("-1")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void post_rejects_null_product_and_warehouse_and_blank_reason() {
        assertThatThrownBy(() -> StockAdjustment.post(
            "ADJ-1", WAREHOUSE, "MAIN", null, "SKU", "W",
            StockMovementDirection.IN, BigDecimal.ONE, "r"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StockAdjustment.post(
            "ADJ-1", null, "MAIN", PRODUCT, "SKU", "W",
            StockMovementDirection.IN, BigDecimal.ONE, "r"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StockAdjustment.post(
            "ADJ-1", WAREHOUSE, "MAIN", PRODUCT, "SKU", "W",
            StockMovementDirection.IN, BigDecimal.ONE, "  "))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
