package com.northwood.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northwood.inventory.domain.events.GoodsReceived;
import com.northwood.shared.domain.DomainEvent;
import static com.northwood.inventory.domain.WarehouseCodes.MAIN;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class GoodsReceiptTest {

    private static final UUID PO = UUID.randomUUID();
    private static final UUID SUPPLIER = UUID.randomUUID();
    private static final UUID WH = UUID.randomUUID();
    private static final UUID PO_LINE = UUID.randomUUID();
    private static final UUID PRODUCT = UUID.randomUUID();

    private static GoodsReceiptLine line(BigDecimal qty, BigDecimal cost) {
        return new GoodsReceiptLine(
            UUID.randomUUID(), PO_LINE,
            PRODUCT, "RM-X", "X",
            qty, cost, qty.multiply(cost)
        );
    }

    @Nested
    class Post {
        @Test void rejects_empty_lines() {
            assertThatThrownBy(() -> GoodsReceipt.post(
                "GR-001", PO, "PO-001", SUPPLIER, "Acme", WH, MAIN, List.of()
            )).isInstanceOf(IllegalArgumentException.class);
        }

        @Test void rejects_null_PO() {
            assertThatThrownBy(() -> GoodsReceipt.post(
                "GR-001", null, "PO-001", SUPPLIER, "Acme", WH, MAIN,
                List.of(line(BigDecimal.TEN, BigDecimal.ONE))
            )).isInstanceOf(IllegalArgumentException.class);
        }

        @Test void rejects_null_warehouse() {
            assertThatThrownBy(() -> GoodsReceipt.post(
                "GR-001", PO, "PO-001", SUPPLIER, "Acme", null, MAIN,
                List.of(line(BigDecimal.TEN, BigDecimal.ONE))
            )).isInstanceOf(IllegalArgumentException.class);
        }

        @Test void posted_status_directly() {
            GoodsReceipt gr = GoodsReceipt.post(
                "GR-001", PO, "PO-001", SUPPLIER, "Acme", WH, MAIN,
                List.of(line(BigDecimal.TEN, BigDecimal.ONE))
            );
            assertThat(gr.status()).isEqualTo(GoodsReceipt.Status.POSTED);
        }

        @Test void emits_GoodsReceived_carrying_PO_and_lines() {
            GoodsReceipt gr = GoodsReceipt.post(
                "GR-001", PO, "PO-001", SUPPLIER, "Acme", WH, MAIN,
                List.of(line(BigDecimal.TEN, new BigDecimal("80.00")))
            );
            List<DomainEvent> events = gr.pullPendingEvents();
            assertThat(events).hasSize(1).first().isInstanceOf(GoodsReceived.class);
            GoodsReceived e = (GoodsReceived) events.get(0);
            assertThat(e.purchaseOrderHeaderId()).isEqualTo(PO);
            assertThat(e.lines()).hasSize(1);
            assertThat(e.lines().get(0).receivedQuantity()).isEqualByComparingTo(BigDecimal.TEN);
            assertThat(e.lines().get(0).unitCost()).isEqualByComparingTo(new BigDecimal("80.00"));
        }
    }
}
