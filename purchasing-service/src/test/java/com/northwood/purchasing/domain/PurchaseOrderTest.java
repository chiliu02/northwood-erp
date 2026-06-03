package com.northwood.purchasing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northwood.purchasing.domain.events.PurchaseOrderApproved;
import com.northwood.purchasing.domain.events.PurchaseOrderCancelled;
import com.northwood.purchasing.domain.events.PurchaseOrderCreated;
import com.northwood.shared.domain.Currencies;
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
                "PO-001", supplier(), PR_HEADER, null, Currencies.AUD, List.of(), true
            )).isInstanceOf(IllegalArgumentException.class);
        }

        @Test void rejects_null_supplier() {
            assertThatThrownBy(() -> PurchaseOrder.fromRequisition(
                "PO-001", null, PR_HEADER, null, Currencies.AUD,
                List.of(line(BigDecimal.TEN, new BigDecimal("80"))), true
            )).isInstanceOf(IllegalArgumentException.class);
        }

        @Test void rejects_null_purchase_requisition_header_id() {
            assertThatThrownBy(() -> PurchaseOrder.fromRequisition(
                "PO-001", supplier(), null, null, Currencies.AUD,
                List.of(line(BigDecimal.TEN, new BigDecimal("80"))), true
            )).isInstanceOf(IllegalArgumentException.class);
        }

        @Test void initial_status_is_sent() {
            PurchaseOrder po = PurchaseOrder.fromRequisition(
                "PO-001", supplier(), PR_HEADER, null, Currencies.AUD,
                List.of(line(BigDecimal.TEN, new BigDecimal("80"))), true
            );
            assertThat(po.status()).isEqualTo(PurchaseOrder.Status.SENT);
        }

        @Test void totals_summed_from_line_totals() {
            PurchaseOrder po = PurchaseOrder.fromRequisition(
                "PO-001", supplier(), PR_HEADER, null, Currencies.AUD,
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
                "PO-001", supplier(), PR_HEADER, null, Currencies.AUD,
                List.of(line(new BigDecimal("5"), new BigDecimal("80"))), true
            );
            List<DomainEvent> events = po.pullPendingEvents();
            assertThat(events).hasSize(2);
            assertThat(events.get(0)).isInstanceOf(PurchaseOrderCreated.class);
            assertThat(events.get(1)).isInstanceOf(PurchaseOrderApproved.class);
        }

        @Test void created_event_carries_status_sent() {
            PurchaseOrder po = PurchaseOrder.fromRequisition(
                "PO-001", supplier(), PR_HEADER, null, Currencies.AUD,
                List.of(line(new BigDecimal("5"), new BigDecimal("80"))), true
            );
            PurchaseOrderCreated e = (PurchaseOrderCreated) po.pullPendingEvents().get(0);
            assertThat(e.status()).isEqualTo("sent");
            assertThat(e.totalAmount()).isEqualByComparingTo(new BigDecimal("400.00"));
        }

        @Test void approved_event_carries_system_approver() {
            PurchaseOrder po = PurchaseOrder.fromRequisition(
                "PO-001", supplier(), PR_HEADER, null, Currencies.AUD,
                List.of(line(BigDecimal.ONE, BigDecimal.TEN)), true
            );
            PurchaseOrderApproved e = (PurchaseOrderApproved) po.pullPendingEvents().get(1);
            assertThat(e.approver()).isEqualTo("system");
        }

        @Test void carries_supplier_identity_into_event() {
            Supplier s = supplier();
            PurchaseOrder po = PurchaseOrder.fromRequisition(
                "PO-001", s, PR_HEADER, null, Currencies.AUD,
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
            assertThat(po.currencyCode()).isEqualTo(Currencies.AUD);
        }

        @Test void zero_total_is_not_auto_approved_and_lands_draft() {
            // Every line fell back to a missing supplier price → total 0. Even with
            // autoApprove=true the PO must land at 'draft' (no PurchaseOrderApproved),
            // not auto-send a zero-value PO that would wedge the to-pay flow.
            PurchaseOrder po = PurchaseOrder.fromRequisition(
                "PO-001", supplier(), PR_HEADER, null, Currencies.AUD,
                List.of(line(BigDecimal.TEN, BigDecimal.ZERO)), true
            );
            assertThat(po.status()).isEqualTo(PurchaseOrder.Status.DRAFT);
            List<DomainEvent> events = po.pullPendingEvents();
            assertThat(events).hasSize(1).first().isInstanceOf(PurchaseOrderCreated.class);
            assertThat(((PurchaseOrderCreated) events.get(0)).status()).isEqualTo("draft");
        }
    }

    @Nested
    class FromRequisition_Draft {
        @Test void initial_status_is_draft() {
            PurchaseOrder po = PurchaseOrder.fromRequisition(
                "PO-001", supplier(), PR_HEADER, null, Currencies.AUD,
                List.of(line(BigDecimal.TEN, new BigDecimal("80"))), false
            );
            assertThat(po.status()).isEqualTo(PurchaseOrder.Status.DRAFT);
        }

        @Test void emits_only_created_event_with_status_draft() {
            PurchaseOrder po = PurchaseOrder.fromRequisition(
                "PO-001", supplier(), PR_HEADER, null, Currencies.AUD,
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
                "PO-001", supplier(), PR_HEADER, null, Currencies.AUD,
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
                "PO-001", supplier(), PR_HEADER, null, Currencies.AUD,
                List.of(line(BigDecimal.TEN, new BigDecimal("80"))), true
            );
            assertThatThrownBy(() -> po.approve("alice", "double-approve"))
                .isInstanceOf(PurchaseOrder.PoNotApprovableException.class)
                .hasMessageContaining("'sent'");
        }
    }

    @Nested
    class Reject {
        @Test void flips_draft_to_cancelled_and_emits_cancelled() {
            PurchaseOrder po = PurchaseOrder.fromRequisition(
                "PO-001", supplier(), PR_HEADER, null, Currencies.AUD,
                List.of(line(BigDecimal.TEN, new BigDecimal("80"))), false
            );
            po.pullPendingEvents();  // drain the Created event

            po.reject("priya", "wrong supplier");

            assertThat(po.status()).isEqualTo(PurchaseOrder.Status.CANCELLED);
            List<DomainEvent> events = po.pullPendingEvents();
            assertThat(events).hasSize(1).first().isInstanceOf(PurchaseOrderCancelled.class);
            PurchaseOrderCancelled cancelled = (PurchaseOrderCancelled) events.get(0);
            assertThat(cancelled.previousStatus()).isEqualTo("draft");
            assertThat(cancelled.cancelledBy()).isEqualTo("priya");
            assertThat(cancelled.reason()).isEqualTo("wrong supplier");
        }

        @Test void rejected_when_not_draft() {
            PurchaseOrder po = PurchaseOrder.fromRequisition(
                "PO-001", supplier(), PR_HEADER, null, Currencies.AUD,
                List.of(line(BigDecimal.TEN, new BigDecimal("80"))), true  // auto-approved → 'sent'
            );
            assertThatThrownBy(() -> po.reject("priya", "too late"))
                .isInstanceOf(PurchaseOrder.PoNotRejectableException.class)
                .hasMessageContaining("'sent'");
        }
    }

    /**
     * Consistency guard: a draft PO is only approvable when it has a positive
     * total that equals subtotal + tax, with subtotal/tax equal to the line sums.
     * Uses {@link PurchaseOrder#reconstitute} to inject drifted header totals (the
     * shape produced by editing a line price without recomputing the header).
     */
    @Nested
    class AssertApprovable {
        private PurchaseOrder draft(BigDecimal subtotal, BigDecimal tax, BigDecimal total, java.util.List<PurchaseOrderLine> lines) {
            return PurchaseOrder.reconstitute(
                PurchaseOrderId.of(UUID.randomUUID()), "PO-001",
                UUID.randomUUID(), "SUP-001", "Acme Co",
                PR_HEADER, Currencies.AUD,
                subtotal, tax, total,
                PurchaseOrder.Status.DRAFT,
                lines, 0L
            );
        }

        @Test void passes_when_consistent() {
            PurchaseOrder po = draft(new BigDecimal("800.00"), BigDecimal.ZERO, new BigDecimal("800.00"),
                List.of(line(BigDecimal.TEN, new BigDecimal("80"))));   // 10 × 80 = 800
            org.assertj.core.api.Assertions.assertThatCode(po::assertApprovable).doesNotThrowAnyException();
        }

        @Test void rejects_zero_total() {
            // The reported bug: header total 0 (supplier price defaulted to 0),
            // line later priced by hand. Approval must be blocked.
            PurchaseOrder po = draft(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                List.of(line(BigDecimal.TEN, BigDecimal.ZERO)));
            assertThatThrownBy(po::assertApprovable)
                .isInstanceOf(PurchaseOrder.PoNotApprovableException.class)
                .hasMessageContaining("total amount is");
        }

        @Test void rejects_subtotal_drifted_from_lines() {
            // Lines sum to 800 but header subtotal says 0 — the stale-header shape.
            PurchaseOrder po = draft(BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("800.00"),
                List.of(line(BigDecimal.TEN, new BigDecimal("80"))));
            assertThatThrownBy(po::assertApprovable)
                .isInstanceOf(PurchaseOrder.PoNotApprovableException.class)
                .hasMessageContaining("sum of line totals");
        }

        @Test void rejects_total_not_equal_subtotal_plus_tax() {
            PurchaseOrder po = draft(new BigDecimal("800.00"), BigDecimal.ZERO, new BigDecimal("999.00"),
                List.of(line(BigDecimal.TEN, new BigDecimal("80"))));
            assertThatThrownBy(po::assertApprovable)
                .isInstanceOf(PurchaseOrder.PoNotApprovableException.class)
                .hasMessageContaining("does not equal subtotal + tax");
        }
    }
}
