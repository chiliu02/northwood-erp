package com.northwood.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northwood.inventory.domain.ReplenishmentRequest.DispatchedAggregateKind;
import com.northwood.inventory.domain.ReplenishmentRequest.Reason;
import com.northwood.inventory.domain.ReplenishmentRequest.Status;
import com.northwood.inventory.domain.ReplenishmentRequest.TargetService;
import com.northwood.inventory.domain.events.ReplenishmentCancelled;
import com.northwood.inventory.domain.events.ReplenishmentFulfilled;
import com.northwood.inventory.domain.events.ReplenishmentRequested;
import com.northwood.shared.domain.DomainEvent;
import java.util.List;
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

        @Test void request_rejects_sales_order_shortage_reason() {
            assertThatThrownBy(() -> ReplenishmentRequest.request(
                PRODUCT, WAREHOUSE, QTY, TargetService.PURCHASING, Reason.SALES_ORDER_SHORTAGE
            ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requestForSalesOrderShortage");
        }

        @Test void requestForSalesOrderShortage_stamps_back_reference() {
            UUID salesOrderHeaderId = UUID.randomUUID();
            UUID salesOrderLineId = UUID.randomUUID();
            ReplenishmentRequest r = ReplenishmentRequest.requestForSalesOrderShortage(
                PRODUCT, WAREHOUSE, QTY, TargetService.PURCHASING, salesOrderHeaderId, salesOrderLineId
            );
            assertThat(r.status()).isEqualTo(Status.REQUESTED);
            assertThat(r.reason()).isEqualTo(Reason.SALES_ORDER_SHORTAGE);
            assertThat(r.sourceSalesOrderHeaderId()).isEqualTo(salesOrderHeaderId);
            assertThat(r.sourceSalesOrderLineId()).isEqualTo(salesOrderLineId);
            assertThat(r.targetService()).isEqualTo(TargetService.PURCHASING);
        }

        @Test void requestForSalesOrderShortage_rejects_null_header_id() {
            assertThatThrownBy(() -> ReplenishmentRequest.requestForSalesOrderShortage(
                PRODUCT, WAREHOUSE, QTY, TargetService.PURCHASING, null, UUID.randomUUID()
            )).isInstanceOf(IllegalArgumentException.class);
        }

        @Test void requestForSalesOrderShortage_rejects_null_line_id() {
            assertThatThrownBy(() -> ReplenishmentRequest.requestForSalesOrderShortage(
                PRODUCT, WAREHOUSE, QTY, TargetService.PURCHASING, UUID.randomUUID(), null
            )).isInstanceOf(IllegalArgumentException.class);
        }

        @Test void request_rejects_order_pegged_reason() {
            assertThatThrownBy(() -> ReplenishmentRequest.request(
                PRODUCT, WAREHOUSE, QTY, TargetService.MANUFACTURING, Reason.ORDER_PEGGED
            ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requestForOrderPegged");
        }

        @Test void requestForOrderPegged_stamps_back_reference_and_reason() {
            UUID salesOrderHeaderId = UUID.randomUUID();
            UUID salesOrderLineId = UUID.randomUUID();
            ReplenishmentRequest r = ReplenishmentRequest.requestForOrderPegged(
                PRODUCT, WAREHOUSE, QTY, TargetService.MANUFACTURING, salesOrderHeaderId, salesOrderLineId
            );
            assertThat(r.status()).isEqualTo(Status.REQUESTED);
            assertThat(r.reason()).isEqualTo(Reason.ORDER_PEGGED);
            assertThat(r.sourceSalesOrderHeaderId()).isEqualTo(salesOrderHeaderId);
            assertThat(r.sourceSalesOrderLineId()).isEqualTo(salesOrderLineId);
        }

        @Test void requestForOrderPegged_rejects_null_header_id() {
            assertThatThrownBy(() -> ReplenishmentRequest.requestForOrderPegged(
                PRODUCT, WAREHOUSE, QTY, TargetService.MANUFACTURING, null, UUID.randomUUID()
            )).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class MarkDispatched {

        @Test void manufacturing_target_dispatched_to_work_order() {
            ReplenishmentRequest r = ReplenishmentRequest.request(
                PRODUCT, WAREHOUSE, QTY, TargetService.MANUFACTURING, Reason.REORDER_POINT_BREACH
            );
            r.pullPendingEvents();

            UUID workOrderId = UUID.randomUUID();
            r.markDispatched(DispatchedAggregateKind.WORK_ORDER, workOrderId);

            assertThat(r.status()).isEqualTo(Status.DISPATCHED);
            assertThat(r.dispatchedAggregateKind()).isEqualTo(DispatchedAggregateKind.WORK_ORDER);
            assertThat(r.dispatchedAggregateId()).isEqualTo(workOrderId);
            assertThat(r.dispatchedAt()).isNotNull();
            assertThat(r.pullPendingEvents()).isEmpty();
        }

        @Test void purchasing_target_dispatched_to_purchase_requisition() {
            ReplenishmentRequest r = ReplenishmentRequest.request(
                PRODUCT, WAREHOUSE, QTY, TargetService.PURCHASING, Reason.WORK_ORDER_SHORTAGE
            );
            r.pullPendingEvents();

            r.markDispatched(DispatchedAggregateKind.PURCHASE_REQUISITION, UUID.randomUUID());
            assertThat(r.status()).isEqualTo(Status.DISPATCHED);
        }

        @Test void same_kind_and_id_is_idempotent() {
            ReplenishmentRequest r = ReplenishmentRequest.request(
                PRODUCT, WAREHOUSE, QTY, TargetService.MANUFACTURING, Reason.REORDER_POINT_BREACH
            );
            r.pullPendingEvents();
            UUID wo = UUID.randomUUID();

            r.markDispatched(DispatchedAggregateKind.WORK_ORDER, wo);
            r.markDispatched(DispatchedAggregateKind.WORK_ORDER, wo);  // no-op
            assertThat(r.status()).isEqualTo(Status.DISPATCHED);
        }

        @Test void rejects_dispatch_kind_that_doesnt_match_target_service() {
            ReplenishmentRequest r = ReplenishmentRequest.request(
                PRODUCT, WAREHOUSE, QTY, TargetService.MANUFACTURING, Reason.REORDER_POINT_BREACH
            );
            r.pullPendingEvents();
            assertThatThrownBy(() -> r.markDispatched(
                DispatchedAggregateKind.PURCHASE_REQUISITION, UUID.randomUUID()
            )).isInstanceOf(IllegalStateException.class);
        }

        @Test void cannot_dispatch_from_fulfilled() {
            ReplenishmentRequest r = ReplenishmentRequest.request(
                PRODUCT, WAREHOUSE, QTY, TargetService.MANUFACTURING, Reason.REORDER_POINT_BREACH
            );
            r.pullPendingEvents();
            r.markDispatched(DispatchedAggregateKind.WORK_ORDER, UUID.randomUUID());
            r.markFulfilled();

            assertThatThrownBy(() -> r.markDispatched(
                DispatchedAggregateKind.WORK_ORDER, UUID.randomUUID()
            )).isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    class LinkPurchaseOrder {

        @Test void stamps_linked_po_for_purchasing_dispatched_request() {
            ReplenishmentRequest r = ReplenishmentRequest.request(
                PRODUCT, WAREHOUSE, QTY, TargetService.PURCHASING, Reason.REORDER_POINT_BREACH
            );
            r.pullPendingEvents();
            r.markDispatched(DispatchedAggregateKind.PURCHASE_REQUISITION, UUID.randomUUID());

            UUID poId = UUID.randomUUID();
            r.linkPurchaseOrder(poId);
            assertThat(r.linkedPurchaseOrderId()).isEqualTo(poId);
        }

        @Test void rejects_link_before_dispatch() {
            ReplenishmentRequest r = ReplenishmentRequest.request(
                PRODUCT, WAREHOUSE, QTY, TargetService.PURCHASING, Reason.REORDER_POINT_BREACH
            );
            r.pullPendingEvents();
            assertThatThrownBy(() -> r.linkPurchaseOrder(UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class);
        }

        @Test void rejects_link_for_manufacturing_dispatched_request() {
            ReplenishmentRequest r = ReplenishmentRequest.request(
                PRODUCT, WAREHOUSE, QTY, TargetService.MANUFACTURING, Reason.REORDER_POINT_BREACH
            );
            r.pullPendingEvents();
            r.markDispatched(DispatchedAggregateKind.WORK_ORDER, UUID.randomUUID());

            assertThatThrownBy(() -> r.linkPurchaseOrder(UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class);
        }

        @Test void rejects_relink_to_different_po() {
            ReplenishmentRequest r = ReplenishmentRequest.request(
                PRODUCT, WAREHOUSE, QTY, TargetService.PURCHASING, Reason.REORDER_POINT_BREACH
            );
            r.pullPendingEvents();
            r.markDispatched(DispatchedAggregateKind.PURCHASE_REQUISITION, UUID.randomUUID());
            r.linkPurchaseOrder(UUID.randomUUID());
            assertThatThrownBy(() -> r.linkPurchaseOrder(UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    class MarkFulfilled {

        @Test void emits_ReplenishmentFulfilled_and_stamps_fulfilled_at() {
            ReplenishmentRequest r = ReplenishmentRequest.request(
                PRODUCT, WAREHOUSE, QTY, TargetService.MANUFACTURING, Reason.REORDER_POINT_BREACH
            );
            r.pullPendingEvents();
            r.markDispatched(DispatchedAggregateKind.WORK_ORDER, UUID.randomUUID());

            r.markFulfilled();

            assertThat(r.status()).isEqualTo(Status.FULFILLED);
            assertThat(r.fulfilledAt()).isNotNull();
            List<DomainEvent> events = r.pullPendingEvents();
            assertThat(events).hasSize(1).first().isInstanceOf(ReplenishmentFulfilled.class);
            ReplenishmentFulfilled e = (ReplenishmentFulfilled) events.get(0);
            assertThat(e.aggregateId()).isEqualTo(r.id().value());
        }

        @Test void is_idempotent_when_already_fulfilled() {
            ReplenishmentRequest r = ReplenishmentRequest.request(
                PRODUCT, WAREHOUSE, QTY, TargetService.MANUFACTURING, Reason.REORDER_POINT_BREACH
            );
            r.pullPendingEvents();
            r.markDispatched(DispatchedAggregateKind.WORK_ORDER, UUID.randomUUID());
            r.markFulfilled();
            r.pullPendingEvents();

            r.markFulfilled();  // second call: no-op, no events
            assertThat(r.pullPendingEvents()).isEmpty();
        }

        @Test void rejects_fulfil_before_dispatch() {
            ReplenishmentRequest r = ReplenishmentRequest.request(
                PRODUCT, WAREHOUSE, QTY, TargetService.MANUFACTURING, Reason.REORDER_POINT_BREACH
            );
            r.pullPendingEvents();
            assertThatThrownBy(r::markFulfilled).isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    class MarkCancelled {

        @Test void cancel_from_requested_emits_event_and_stamps_cancelled_at() {
            ReplenishmentRequest r = ReplenishmentRequest.request(
                PRODUCT, WAREHOUSE, QTY, TargetService.MANUFACTURING, Reason.REORDER_POINT_BREACH
            );
            r.pullPendingEvents();

            r.markCancelled("no_active_bom");

            assertThat(r.status()).isEqualTo(Status.CANCELLED);
            assertThat(r.cancelledAt()).isNotNull();
            List<DomainEvent> events = r.pullPendingEvents();
            assertThat(events).hasSize(1).first().isInstanceOf(ReplenishmentCancelled.class);
            ReplenishmentCancelled e = (ReplenishmentCancelled) events.get(0);
            assertThat(e.aggregateId()).isEqualTo(r.id().value());
            assertThat(e.productId()).isEqualTo(PRODUCT);
            assertThat(e.reason()).isEqualTo("no_active_bom");
            assertThat(e.sourceSalesOrderHeaderId()).isNull();
            assertThat(e.sourceSalesOrderLineId()).isNull();
        }

        @Test void cancel_from_dispatched_is_allowed() {
            ReplenishmentRequest r = ReplenishmentRequest.request(
                PRODUCT, WAREHOUSE, QTY, TargetService.MANUFACTURING, Reason.REORDER_POINT_BREACH
            );
            r.pullPendingEvents();
            r.markDispatched(DispatchedAggregateKind.WORK_ORDER, UUID.randomUUID());

            r.markCancelled("downstream_failed");
            assertThat(r.status()).isEqualTo(Status.CANCELLED);
        }

        @Test void cancel_propagates_sales_order_back_reference() {
            UUID headerId = UUID.randomUUID();
            UUID lineId = UUID.randomUUID();
            ReplenishmentRequest r = ReplenishmentRequest.requestForSalesOrderShortage(
                PRODUCT, WAREHOUSE, QTY, TargetService.PURCHASING, headerId, lineId
            );
            r.pullPendingEvents();

            r.markCancelled("unsourceable");
            ReplenishmentCancelled e = (ReplenishmentCancelled) r.pullPendingEvents().get(0);
            assertThat(e.sourceSalesOrderHeaderId()).isEqualTo(headerId);
            assertThat(e.sourceSalesOrderLineId()).isEqualTo(lineId);
        }

        @Test void is_idempotent_when_already_cancelled() {
            ReplenishmentRequest r = ReplenishmentRequest.request(
                PRODUCT, WAREHOUSE, QTY, TargetService.MANUFACTURING, Reason.REORDER_POINT_BREACH
            );
            r.pullPendingEvents();
            r.markCancelled("x");
            r.pullPendingEvents();

            r.markCancelled("x");  // second call: no-op, no events
            assertThat(r.pullPendingEvents()).isEmpty();
        }

        @Test void rejects_cancel_from_fulfilled() {
            ReplenishmentRequest r = ReplenishmentRequest.request(
                PRODUCT, WAREHOUSE, QTY, TargetService.MANUFACTURING, Reason.REORDER_POINT_BREACH
            );
            r.pullPendingEvents();
            r.markDispatched(DispatchedAggregateKind.WORK_ORDER, UUID.randomUUID());
            r.markFulfilled();

            assertThatThrownBy(() -> r.markCancelled("too_late"))
                .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    class WireFormat {

        @Test void status_round_trips() {
            for (Status s : Status.values()) {
                assertThat(Status.fromCode(s.code())).isEqualTo(s);
            }
        }

        @Test void target_service_round_trips() {
            for (TargetService t : TargetService.values()) {
                assertThat(TargetService.fromCode(t.code())).isEqualTo(t);
            }
        }

        @Test void reason_round_trips() {
            for (Reason r : Reason.values()) {
                assertThat(Reason.fromCode(r.code())).isEqualTo(r);
            }
        }

        @Test void status_fromDb_rejects_unknown() {
            assertThatThrownBy(() -> Status.fromCode("bogus"))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
