package com.northwood.purchasing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northwood.purchasing.domain.events.PurchaseRequisitionCreated;
import com.northwood.purchasing.domain.events.ReplenishmentDispatched;
import com.northwood.shared.domain.DomainEvent;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PurchaseRequisitionTest {

    private static final UUID PRODUCT = UUID.randomUUID();
    private static final UUID WO = UUID.randomUUID();
    private static final UUID SUPPLIER = UUID.randomUUID();

    private static PurchaseRequisitionLine line() {
        return new PurchaseRequisitionLine(
            UUID.randomUUID(), 10,
            PRODUCT, "RM-X", "X",
            BigDecimal.TEN, null,
            SUPPLIER, "Acme", PurchaseRequisition.LineStatus.OPEN
        );
    }

    @Nested
    class Create {
        @Test void rejects_empty_lines() {
            assertThatThrownBy(() -> PurchaseRequisition.create(
                "PR-001", PurchaseRequisition.SourceType.MANUAL, null, null, "buyer", List.of()
            )).isInstanceOf(IllegalArgumentException.class);
        }

        @Test void manual_must_not_have_source_ids() {
            assertThatThrownBy(() -> PurchaseRequisition.create(
                "PR-001", PurchaseRequisition.SourceType.MANUAL, WO, null, "buyer", List.of(line())
            )).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> PurchaseRequisition.create(
                "PR-001", PurchaseRequisition.SourceType.MANUAL, null, PRODUCT, "buyer", List.of(line())
            )).isInstanceOf(IllegalArgumentException.class);
        }

        @Test void low_stock_requires_source_product_only() {
            assertThatThrownBy(() -> PurchaseRequisition.create(
                "PR-001", PurchaseRequisition.SourceType.LOW_STOCK, null, null, "system", List.of(line())
            )).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> PurchaseRequisition.create(
                "PR-001", PurchaseRequisition.SourceType.LOW_STOCK, WO, PRODUCT, "system", List.of(line())
            )).isInstanceOf(IllegalArgumentException.class);
        }

        @Test void work_order_shortage_requires_source_work_order_only() {
            assertThatThrownBy(() -> PurchaseRequisition.create(
                "PR-001", PurchaseRequisition.SourceType.WORK_ORDER_SHORTAGE, null, null, "system", List.of(line())
            )).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> PurchaseRequisition.create(
                "PR-001", PurchaseRequisition.SourceType.WORK_ORDER_SHORTAGE, WO, PRODUCT, "system", List.of(line())
            )).isInstanceOf(IllegalArgumentException.class);
        }

        @Test void rejects_unknown_source_type_at_db_boundary() {
            // SourceType.fromCode is the wire→enum boundary; the enum parameter
            // on create() makes unknown values a compile-time impossibility.
            assertThatThrownBy(() -> PurchaseRequisition.SourceType.fromCode("weird_source"))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test void manual_requisition_auto_approved() {
            PurchaseRequisition pr = PurchaseRequisition.create(
                "PR-001", PurchaseRequisition.SourceType.MANUAL, null, null, "buyer", List.of(line())
            );
            assertThat(pr.status()).isEqualTo(PurchaseRequisition.Status.APPROVED);
        }

        @Test void emits_PurchaseRequisitionCreated_with_full_lines() {
            PurchaseRequisition pr = PurchaseRequisition.create(
                "PR-001", PurchaseRequisition.SourceType.WORK_ORDER_SHORTAGE, WO, null, "system",
                List.of(line(), line())
            );
            List<DomainEvent> events = pr.pullPendingEvents();
            assertThat(events).hasSize(1).first().isInstanceOf(PurchaseRequisitionCreated.class);
            PurchaseRequisitionCreated e = (PurchaseRequisitionCreated) events.get(0);
            assertThat(e.lines()).hasSize(2);
            assertThat(e.sourceType()).isEqualTo("work_order_shortage");
            assertThat(e.sourceWorkOrderId()).isEqualTo(WO);
        }

        @Test void create_rejects_STOCK_REPLENISHMENT_with_factory_redirect() {
            // STOCK_REPLENISHMENT must go through createForStockReplenishment so
            // the sibling ReplenishmentDispatched event is also emitted.
            assertThatThrownBy(() -> PurchaseRequisition.create(
                "PR-001", PurchaseRequisition.SourceType.STOCK_REPLENISHMENT, null, null, "buyer", List.of(line())
            )).isInstanceOf(IllegalArgumentException.class)
              .hasMessageContaining("createForStockReplenishment");
        }
    }

    @Nested
    class CreateForStockReplenishment {
        @Test void emits_both_PurchaseRequisitionCreated_and_ReplenishmentDispatched() {
            UUID replenishmentRequestId = UUID.randomUUID();
            PurchaseRequisition pr = PurchaseRequisition.createForStockReplenishment(
                "PR-REPL-001", replenishmentRequestId,
                "inventory.replenishment-dispatcher", List.of(line())
            );

            assertThat(pr.sourceType()).isEqualTo(PurchaseRequisition.SourceType.STOCK_REPLENISHMENT);
            assertThat(pr.sourceReplenishmentRequestId()).isEqualTo(replenishmentRequestId);
            assertThat(pr.sourceWorkOrderId()).isNull();
            assertThat(pr.sourceProductId()).isNull();
            assertThat(pr.status()).isEqualTo(PurchaseRequisition.Status.APPROVED);

            List<DomainEvent> events = pr.pullPendingEvents();
            assertThat(events).hasSize(2);
            assertThat(events.get(0)).isInstanceOf(PurchaseRequisitionCreated.class);
            assertThat(events.get(1)).isInstanceOf(ReplenishmentDispatched.class);

            PurchaseRequisitionCreated created = (PurchaseRequisitionCreated) events.get(0);
            assertThat(created.sourceType()).isEqualTo("stock_replenishment");
            assertThat(created.sourceReplenishmentRequestId()).isEqualTo(replenishmentRequestId);
            assertThat(created.sourceWorkOrderId()).isNull();
            assertThat(created.sourceProductId()).isNull();

            ReplenishmentDispatched dispatched = (ReplenishmentDispatched) events.get(1);
            assertThat(dispatched.aggregateId()).isEqualTo(pr.id().value());
            assertThat(dispatched.replenishmentRequestId()).isEqualTo(replenishmentRequestId);
        }

        @Test void rejects_null_replenishmentRequestId() {
            assertThatThrownBy(() -> PurchaseRequisition.createForStockReplenishment(
                "PR", null, "buyer", List.of(line())
            )).isInstanceOf(IllegalArgumentException.class);
        }

        @Test void rejects_empty_lines() {
            assertThatThrownBy(() -> PurchaseRequisition.createForStockReplenishment(
                "PR", UUID.randomUUID(), "buyer", List.of()
            )).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class MarkConverted {
        @Test void flips_approved_to_converted() {
            PurchaseRequisition pr = PurchaseRequisition.create(
                "PR", PurchaseRequisition.SourceType.MANUAL, null, null, "buyer", List.of(line())
            );
            pr.pullPendingEvents();
            pr.markConverted();
            assertThat(pr.status()).isEqualTo(PurchaseRequisition.Status.CONVERTED);
        }

        @Test void is_idempotent_when_already_converted() {
            PurchaseRequisition pr = PurchaseRequisition.create(
                "PR", PurchaseRequisition.SourceType.MANUAL, null, null, "buyer", List.of(line())
            );
            pr.pullPendingEvents();
            pr.markConverted();
            pr.markConverted();   // second call should be a no-op
            assertThat(pr.status()).isEqualTo(PurchaseRequisition.Status.CONVERTED);
        }

        @Test void rejects_when_in_terminal_states() {
            PurchaseRequisition pr = PurchaseRequisition.reconstitute(
                PurchaseRequisitionId.newId(),
                "PR", PurchaseRequisition.SourceType.MANUAL, null, null, null,
                PurchaseRequisition.Status.REJECTED, "buyer", List.of(line()), 3L
            );
            assertThatThrownBy(pr::markConverted).isInstanceOf(IllegalStateException.class);
        }
    }
}
