package com.northwood.inventory.domain.replenishment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northwood.inventory.domain.events.ReplenishmentRequested;
import com.northwood.inventory.domain.replenishment.ReplenishmentRequest.Reason;
import com.northwood.inventory.domain.replenishment.ReplenishmentRequest.Status;
import com.northwood.inventory.domain.replenishment.ReplenishmentRequest.TargetService;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ReplenishmentRequestTest {

    private static final UUID PRODUCT = UUID.randomUUID();
    private static final UUID WAREHOUSE = UUID.randomUUID();
    private static final BigDecimal QTY = new BigDecimal("50");

    @Nested
    class Factory {

        @Test void requested_status_directly() {
            ReplenishmentRequest r = ReplenishmentRequest.request(
                PRODUCT, WAREHOUSE, QTY,
                TargetService.MANUFACTURING, Reason.REORDER_POINT_BREACH
            );
            assertThat(r.status()).isEqualTo(Status.REQUESTED);
            assertThat(r.productId()).isEqualTo(PRODUCT);
            assertThat(r.warehouseId()).isEqualTo(WAREHOUSE);
            assertThat(r.requestedQuantity()).isEqualByComparingTo(QTY);
            assertThat(r.targetService()).isEqualTo(TargetService.MANUFACTURING);
            assertThat(r.reason()).isEqualTo(Reason.REORDER_POINT_BREACH);
            assertThat(r.version()).isZero();
        }

        @Test void emits_ReplenishmentRequested_with_wire_format_strings() {
            ReplenishmentRequest r = ReplenishmentRequest.request(
                PRODUCT, WAREHOUSE, QTY,
                TargetService.PURCHASING, Reason.WORK_ORDER_SHORTAGE
            );
            ReplenishmentRequested e = (ReplenishmentRequested) r.pullPendingEvents().get(0);
            assertThat(e.aggregateId()).isEqualTo(r.id().value());
            assertThat(e.productId()).isEqualTo(PRODUCT);
            assertThat(e.warehouseId()).isEqualTo(WAREHOUSE);
            assertThat(e.quantity()).isEqualByComparingTo(QTY);
            assertThat(e.targetService()).isEqualTo("purchasing");
            assertThat(e.reason()).isEqualTo("work_order_shortage");
        }

        @Test void pullPendingEvents_drains_once() {
            ReplenishmentRequest r = ReplenishmentRequest.request(
                PRODUCT, WAREHOUSE, QTY,
                TargetService.MANUFACTURING, Reason.REORDER_POINT_BREACH
            );
            assertThat(r.pullPendingEvents()).hasSize(1);
            assertThat(r.pullPendingEvents()).isEmpty();
        }

        @Test void rejects_null_product() {
            assertThatThrownBy(() -> ReplenishmentRequest.request(
                null, WAREHOUSE, QTY,
                TargetService.MANUFACTURING, Reason.REORDER_POINT_BREACH
            )).isInstanceOf(IllegalArgumentException.class);
        }

        @Test void rejects_null_warehouse() {
            assertThatThrownBy(() -> ReplenishmentRequest.request(
                PRODUCT, null, QTY,
                TargetService.MANUFACTURING, Reason.REORDER_POINT_BREACH
            )).isInstanceOf(IllegalArgumentException.class);
        }

        @Test void rejects_null_quantity() {
            assertThatThrownBy(() -> ReplenishmentRequest.request(
                PRODUCT, WAREHOUSE, null,
                TargetService.MANUFACTURING, Reason.REORDER_POINT_BREACH
            )).isInstanceOf(IllegalArgumentException.class);
        }

        @Test void rejects_zero_quantity() {
            assertThatThrownBy(() -> ReplenishmentRequest.request(
                PRODUCT, WAREHOUSE, BigDecimal.ZERO,
                TargetService.MANUFACTURING, Reason.REORDER_POINT_BREACH
            )).isInstanceOf(IllegalArgumentException.class);
        }

        @Test void rejects_negative_quantity() {
            assertThatThrownBy(() -> ReplenishmentRequest.request(
                PRODUCT, WAREHOUSE, new BigDecimal("-1"),
                TargetService.MANUFACTURING, Reason.REORDER_POINT_BREACH
            )).isInstanceOf(IllegalArgumentException.class);
        }

        @Test void rejects_null_target_service() {
            assertThatThrownBy(() -> ReplenishmentRequest.request(
                PRODUCT, WAREHOUSE, QTY, null, Reason.REORDER_POINT_BREACH
            )).isInstanceOf(IllegalArgumentException.class);
        }

        @Test void rejects_null_reason() {
            assertThatThrownBy(() -> ReplenishmentRequest.request(
                PRODUCT, WAREHOUSE, QTY, TargetService.MANUFACTURING, null
            )).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class WireFormat {

        @Test void status_round_trips() {
            for (Status s : Status.values()) {
                assertThat(Status.fromDb(s.dbValue())).isEqualTo(s);
            }
        }

        @Test void target_service_round_trips() {
            for (TargetService t : TargetService.values()) {
                assertThat(TargetService.fromDb(t.dbValue())).isEqualTo(t);
            }
        }

        @Test void reason_round_trips() {
            for (Reason r : Reason.values()) {
                assertThat(Reason.fromDb(r.dbValue())).isEqualTo(r);
            }
        }

        @Test void status_fromDb_rejects_unknown() {
            assertThatThrownBy(() -> Status.fromDb("bogus"))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
