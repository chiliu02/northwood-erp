package com.northwood.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northwood.inventory.domain.events.ShipmentPosted;
import com.northwood.shared.domain.DomainEvent;
import static com.northwood.inventory.domain.WarehouseCodes.MAIN;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ShipmentTest {

    private static final UUID SO = UUID.randomUUID();
    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final UUID WH = UUID.randomUUID();
    private static final UUID SO_LINE = UUID.randomUUID();
    private static final UUID PRODUCT = UUID.randomUUID();

    private static ShipmentLine line(BigDecimal qty, BigDecimal cost) {
        return new ShipmentLine(
            UUID.randomUUID(), SO_LINE,
            PRODUCT, "FG-X", "Finished",
            qty, cost, qty.multiply(cost)
        );
    }

    @Nested
    class Post {
        @Test void rejects_empty_lines() {
            assertThatThrownBy(() -> Shipment.post(
                "SH-001", SO, CUSTOMER, "Cust", WH, MAIN, List.of()
            )).isInstanceOf(IllegalArgumentException.class);
        }

        @Test void rejects_null_sales_order() {
            assertThatThrownBy(() -> Shipment.post(
                "SH-001", null, CUSTOMER, "Cust", WH, MAIN,
                List.of(line(BigDecimal.ONE, BigDecimal.TEN))
            )).isInstanceOf(IllegalArgumentException.class);
        }

        @Test void rejects_null_warehouse_id() {
            assertThatThrownBy(() -> Shipment.post(
                "SH-001", SO, CUSTOMER, "Cust", null, MAIN,
                List.of(line(BigDecimal.ONE, BigDecimal.TEN))
            )).isInstanceOf(IllegalArgumentException.class);
        }

        @Test void posted_status_directly() {
            Shipment s = Shipment.post(
                "SH-001", SO, CUSTOMER, "Cust", WH, MAIN,
                List.of(line(BigDecimal.ONE, BigDecimal.TEN))
            );
            assertThat(s.status()).isEqualTo(Shipment.Status.POSTED);
        }

        @Test void emits_ShipmentPosted_with_lines_and_unit_cost() {
            Shipment s = Shipment.post(
                "SH-001", SO, CUSTOMER, "Cust", WH, MAIN,
                List.of(line(new BigDecimal("3"), new BigDecimal("100.00")))
            );
            ShipmentPosted e = (ShipmentPosted) s.pullPendingEvents().get(0);
            assertThat(e.salesOrderHeaderId()).isEqualTo(SO);
            assertThat(e.customerId()).isEqualTo(CUSTOMER);
            assertThat(e.lines()).hasSize(1);
            assertThat(e.lines().get(0).shippedQuantity()).isEqualByComparingTo(new BigDecimal("3"));
            assertThat(e.lines().get(0).unitCost()).isEqualByComparingTo(new BigDecimal("100.00"));
        }
    }
}
