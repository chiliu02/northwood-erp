package com.northwood.purchasing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northwood.purchasing.domain.events.PurchaseOrderApproved;
import com.northwood.purchasing.domain.events.PurchaseOrderCreated;
import com.northwood.shared.domain.DomainEvent;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PurchaseOrderTest {

    private static final UUID PR_HEADER = UUID.randomUUID();
    private static final UUID PR_LINE = UUID.randomUUID();
    private static final UUID PRODUCT = UUID.randomUUID();

    private static Supplier supplier() {
        return new Supplier(SupplierId.of(UUID.randomUUID()), "SUP-001", "Acme Co", "active");
    }

    private static PurchaseOrderLine line(BigDecimal qty, BigDecimal unitPrice) {
        BigDecimal total = qty.multiply(unitPrice);
        return new PurchaseOrderLine(
            UUID.randomUUID(), 10, PR_LINE,
            PRODUCT, "RM-X", "X",
            qty, unitPrice,
            BigDecimal.ZERO, BigDecimal.ZERO,
            total, PurchaseOrder.LineStatus.OPEN
        );
    }

    @Nested
    class FromRequisition_AutoApprove {
        @Test void rejects_empty_lines() {
            assertThatThrownBy(() -> PurchaseOrder.fromRequisition(
                "PO-001", supplier(), PR_HEADER, null, "AUD", List.of(), true
            )).isInstanceOf(IllegalArgumentException.class);
        }

        @Test void rejects_null_supplier() {
            assertThatThrownBy(() -> PurchaseOrder.fromRequisition(
                "PO-001", null, PR_HEADER, null, "AUD",
                List.of(line(BigDecimal.TEN, new BigDecimal("80"))), true
            )).isInstanceOf(NullPointerException.class);
        }

        @Test void rejects_null_purchase_requisition_header_id() {
            assertThatThrownBy(() -> PurchaseOrder.fromRequisition(
                "PO-001", supplier(), null, null, "AUD",
                List.of(line(BigDecimal.TEN, new BigDecimal("80"))), true
            )).isInstanceOf(NullPointerException.class);
        }

        @Test void initial_status_is_sent() {
            PurchaseOrder po = PurchaseOrder.fromRequisition(
                "PO-001", supplier(), PR_HEADER, null, "AUD",
                List.of(line(BigDecimal.TEN, new BigDecimal("80"))), true
            );
            assertThat(po.status()).isEqualTo(PurchaseOrder.Status.SENT);
        }

        @Test void totals_summed_from_line_totals() {
            PurchaseOrder po = PurchaseOrder.fromRequisition(
                "PO-001", supplier(), PR_HEADER, null, "AUD",
                List.of(
                    line(new BigDecimal("5"), new BigDecimal("80")),    // 400
                    line(new BigDecimal("10"), new BigDecimal("25"))    // 250
                ), true
            );
            assertThat(po.subtotalAmount()).isEqualByComparingTo(new BigDecimal("650.00"));
            assertThat(po.totalAmount()).isEqualByComparingTo(new BigDecimal("650.00"));
        }

        @Test void emits_both_created_and_approved_events() {
            PurchaseOrder po = PurchaseOrder.fromRequisition(
                "PO-001", supplier(), PR_HEADER, null, "AUD",
                List.of(line(new BigDecimal("5"), new BigDecimal("80"))), true
            );
            List<DomainEvent> events = po.pullPendingEvents();
            assertThat(events).hasSize(2);
            assertThat(events.get(0)).isInstanceOf(PurchaseOrderCreated.class);
            assertThat(events.get(1)).isInstanceOf(PurchaseOrderApproved.class);
        }

        @Test void created_event_carries_status_sent() {
            PurchaseOrder po = PurchaseOrder.fromRequisition(
                "PO-001", supplier(), PR_HEADER, null, "AUD",
                List.of(line(new BigDecimal("5"), new BigDecimal("80"))), true
            );
            PurchaseOrderCreated e = (PurchaseOrderCreated) po.pullPendingEvents().get(0);
            assertThat(e.status()).isEqualTo("sent");
            assertThat(e.totalAmount()).isEqualByComparingTo(new BigDecimal("400.00"));
        }

        @Test void approved_event_carries_system_approver() {
            PurchaseOrder po = PurchaseOrder.fromRequisition(
                "PO-001", supplier(), PR_HEADER, null, "AUD",
                List.of(line(BigDecimal.ONE, BigDecimal.TEN)), true
            );
            PurchaseOrderApproved e = (PurchaseOrderApproved) po.pullPendingEvents().get(1);
            assertThat(e.approver()).isEqualTo("system");
        }

        @Test void carries_supplier_identity_into_event() {
            Supplier s = supplier();
            PurchaseOrder po = PurchaseOrder.fromRequisition(
                "PO-001", s, PR_HEADER, null, "AUD",
                List.of(line(BigDecimal.ONE, BigDecimal.TEN)), true
            );
            PurchaseOrderCreated e = (PurchaseOrderCreated) po.pullPendingEvents().get(0);
            assertThat(e.supplierId()).isEqualTo(s.id().value());
            assertThat(e.supplierCode()).isEqualTo("SUP-001");
            assertThat(e.supplierName()).isEqualTo("Acme Co");
        }

        @Test void defaults_currency_to_AUD_when_null() {
            PurchaseOrder po = PurchaseOrder.fromRequisition(
                "PO-001", supplier(), PR_HEADER, null, null,
                List.of(line(BigDecimal.ONE, BigDecimal.TEN)), true
            );
            assertThat(po.currencyCode()).isEqualTo("AUD");
        }
    }

    @Nested
    class FromRequisition_Draft {
        @Test void initial_status_is_draft() {
            PurchaseOrder po = PurchaseOrder.fromRequisition(
                "PO-001", supplier(), PR_HEADER, null, "AUD",
                List.of(line(BigDecimal.TEN, new BigDecimal("80"))), false
            );
            assertThat(po.status()).isEqualTo(PurchaseOrder.Status.DRAFT);
        }

        @Test void emits_only_created_event_with_status_draft() {
            PurchaseOrder po = PurchaseOrder.fromRequisition(
                "PO-001", supplier(), PR_HEADER, null, "AUD",
                List.of(line(BigDecimal.TEN, new BigDecimal("80"))), false
            );
            List<DomainEvent> events = po.pullPendingEvents();
            assertThat(events).hasSize(1).first().isInstanceOf(PurchaseOrderCreated.class);
            PurchaseOrderCreated e = (PurchaseOrderCreated) events.get(0);
            assertThat(e.status()).isEqualTo("draft");
        }
    }

    @Nested
    class Approve {
        @Test void flips_draft_to_sent_and_emits_approved() {
            PurchaseOrder po = PurchaseOrder.fromRequisition(
                "PO-001", supplier(), PR_HEADER, null, "AUD",
                List.of(line(BigDecimal.TEN, new BigDecimal("80"))), false
            );
            po.pullPendingEvents();  // drain the Created event

            po.approve("alice", "looks good");

            assertThat(po.status()).isEqualTo(PurchaseOrder.Status.SENT);
            List<DomainEvent> events = po.pullPendingEvents();
            assertThat(events).hasSize(1).first().isInstanceOf(PurchaseOrderApproved.class);
            PurchaseOrderApproved approved = (PurchaseOrderApproved) events.get(0);
            assertThat(approved.approver()).isEqualTo("alice");
            assertThat(approved.reason()).isEqualTo("looks good");
        }

        @Test void rejected_when_already_sent() {
            PurchaseOrder po = PurchaseOrder.fromRequisition(
                "PO-001", supplier(), PR_HEADER, null, "AUD",
                List.of(line(BigDecimal.TEN, new BigDecimal("80"))), true
            );
            assertThatThrownBy(() -> po.approve("alice", "double-approve"))
                .isInstanceOf(PurchaseOrder.PoNotApprovableException.class)
                .hasMessageContaining("'sent'");
        }
    }
}
