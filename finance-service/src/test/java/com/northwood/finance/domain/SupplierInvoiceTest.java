package com.northwood.finance.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northwood.finance.domain.events.SupplierInvoiceApproved;
import com.northwood.finance.domain.events.SupplierInvoiceRejected;
import com.northwood.shared.domain.Currencies;
import com.northwood.shared.domain.DomainEvent;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class SupplierInvoiceTest {

    private static final UUID PO_HEADER = UUID.randomUUID();
    private static final UUID GR_HEADER = UUID.randomUUID();
    private static final UUID SUPPLIER = UUID.randomUUID();
    private static final UUID PO_LINE = UUID.randomUUID();
    private static final UUID PRODUCT = UUID.randomUUID();

    private static SupplierInvoiceLine line(BigDecimal qty, BigDecimal price) {
        BigDecimal subtotal = qty.multiply(price);
        return new SupplierInvoiceLine(
            UUID.randomUUID(), 10,
            PO_LINE, null,
            PRODUCT, "RM-X", "X",
            qty, price,
            BigDecimal.ZERO, BigDecimal.ZERO,
            subtotal
        );
    }

    private static SupplierInvoice record(SupplierInvoice.MatchStatus matchOutcome) {
        return SupplierInvoice.record(
            "INT-001", "SUP-001", PO_HEADER, GR_HEADER,
            SUPPLIER, "ACME", "Acme Co",
            Currencies.AUD, List.of(line(new BigDecimal("5"), new BigDecimal("80"))),
            matchOutcome
        );
    }

    @Nested
    class Record {
        @Test void matched_outcome_emits_approved_event() {
            SupplierInvoice si = record(SupplierInvoice.MatchStatus.MATCHED);
            assertThat(si.status()).isEqualTo(SupplierInvoice.Status.APPROVED);
            assertThat(si.matchStatus()).isEqualTo(SupplierInvoice.MatchStatus.MATCHED);
            List<DomainEvent> events = si.pullPendingEvents();
            assertThat(events).hasSize(1).first().isInstanceOf(SupplierInvoiceApproved.class);
        }

        @Test void failed_outcome_emits_no_event() {
            SupplierInvoice si = record(SupplierInvoice.MatchStatus.FAILED);
            assertThat(si.status()).isEqualTo(SupplierInvoice.Status.THREE_WAY_MATCH_FAILED);
            assertThat(si.matchStatus()).isEqualTo(SupplierInvoice.MatchStatus.FAILED);
            assertThat(si.pullPendingEvents()).isEmpty();
        }

        @Test void variance_outcome_emits_no_event() {
            SupplierInvoice si = record(SupplierInvoice.MatchStatus.VARIANCE);
            assertThat(si.status()).isEqualTo(SupplierInvoice.Status.THREE_WAY_MATCH_FAILED);
            assertThat(si.matchStatus()).isEqualTo(SupplierInvoice.MatchStatus.VARIANCE);
            assertThat(si.pullPendingEvents()).isEmpty();
        }

        @Test void rejects_empty_lines() {
            assertThatThrownBy(() -> SupplierInvoice.record(
                "INT", "SUP", PO_HEADER, GR_HEADER,
                SUPPLIER, "A", "A", Currencies.AUD, List.of(), SupplierInvoice.MatchStatus.MATCHED
            )).isInstanceOf(IllegalArgumentException.class);
        }

        @Test void rejects_null_purchase_order_header_id() {
            assertThatThrownBy(() -> SupplierInvoice.record(
                "INT", "SUP", null, GR_HEADER,
                SUPPLIER, "A", "A", Currencies.AUD,
                List.of(line(new BigDecimal("5"), new BigDecimal("80"))),
                SupplierInvoice.MatchStatus.MATCHED
            )).isInstanceOf(IllegalArgumentException.class);
        }

        @Test void rejects_null_supplier_id() {
            assertThatThrownBy(() -> SupplierInvoice.record(
                "INT", "SUP", PO_HEADER, GR_HEADER,
                null, "A", "A", Currencies.AUD,
                List.of(line(new BigDecimal("5"), new BigDecimal("80"))),
                SupplierInvoice.MatchStatus.MATCHED
            )).isInstanceOf(IllegalArgumentException.class);
        }

        @Test void rejects_null_supplier_invoice_number() {
            assertThatThrownBy(() -> SupplierInvoice.record(
                "INT", null, PO_HEADER, GR_HEADER,
                SUPPLIER, "A", "A", Currencies.AUD,
                List.of(line(new BigDecimal("5"), new BigDecimal("80"))),
                SupplierInvoice.MatchStatus.MATCHED
            )).isInstanceOf(IllegalArgumentException.class);
        }

        @Test void totals_summed_from_lines() {
            SupplierInvoice si = SupplierInvoice.record(
                "INT", "SUP", PO_HEADER, GR_HEADER,
                SUPPLIER, "A", "A", Currencies.AUD,
                List.of(line(new BigDecimal("5"), new BigDecimal("80"))),
                SupplierInvoice.MatchStatus.MATCHED
            );
            // 5 * 80 = 400
            assertThat(si.subtotalAmount()).isEqualByComparingTo(new BigDecimal("400.00"));
            assertThat(si.totalAmount()).isEqualByComparingTo(new BigDecimal("400.00"));
        }

        @Test void zero_total_matched_is_not_auto_approved_and_parks_for_review() {
            // A zero-value matched invoice must NOT auto-approve + post a zero GL
            // entry; it lands at three_way_match_failed for manual review (where
            // assertApprovable then blocks approval until it's priced).
            SupplierInvoice si = SupplierInvoice.record(
                "INT", "SUP", PO_HEADER, GR_HEADER,
                SUPPLIER, "A", "A", Currencies.AUD,
                List.of(line(new BigDecimal("5"), BigDecimal.ZERO)),
                SupplierInvoice.MatchStatus.MATCHED
            );
            assertThat(si.status()).isEqualTo(SupplierInvoice.Status.THREE_WAY_MATCH_FAILED);
            assertThat(si.pullPendingEvents()).isEmpty();   // no SupplierInvoiceApproved / GL post
        }

        @Test void emitted_event_carries_total_amount() {
            SupplierInvoice si = record(SupplierInvoice.MatchStatus.MATCHED);
            SupplierInvoiceApproved e = (SupplierInvoiceApproved) si.pullPendingEvents().get(0);
            assertThat(e.totalAmount()).isEqualByComparingTo(new BigDecimal("400.00"));
            assertThat(e.purchaseOrderHeaderId()).isEqualTo(PO_HEADER);
        }
    }

    @Nested
    class ManualApprove {
        @Test void only_allowed_from_three_way_match_failed() {
            SupplierInvoice si = record(SupplierInvoice.MatchStatus.MATCHED);
            si.pullPendingEvents();
            assertThatThrownBy(() -> si.manualApprove("override"))
                .isInstanceOf(IllegalStateException.class);
        }

        @Test void flips_status_to_approved_and_emits_event() {
            SupplierInvoice si = record(SupplierInvoice.MatchStatus.FAILED);
            si.manualApprove("price-tolerance OK");
            assertThat(si.status()).isEqualTo(SupplierInvoice.Status.APPROVED);
            assertThat(si.matchStatus()).isEqualTo(SupplierInvoice.MatchStatus.MATCHED);
            assertThat(si.pullPendingEvents()).hasSize(1)
                .first().isInstanceOf(SupplierInvoiceApproved.class);
        }

        @Test void approval_event_routes_to_correct_PO() {
            SupplierInvoice si = record(SupplierInvoice.MatchStatus.VARIANCE);
            si.manualApprove("acceptable variance");
            SupplierInvoiceApproved e = (SupplierInvoiceApproved) si.pullPendingEvents().get(0);
            assertThat(e.purchaseOrderHeaderId()).isEqualTo(PO_HEADER);
            assertThat(e.totalAmount()).isEqualByComparingTo(new BigDecimal("400.00"));
        }
    }

    @Nested
    class AssertConsistent {
        private SupplierInvoice failed(BigDecimal subtotal, BigDecimal tax, BigDecimal total, List<SupplierInvoiceLine> lines) {
            return SupplierInvoice.reconstitute(
                SupplierInvoiceId.newId(), "INT-001", "SUP-001",
                PO_HEADER, GR_HEADER, SUPPLIER, "ACME", "Acme Co",
                Currencies.AUD, subtotal, tax, total,
                SupplierInvoice.Status.THREE_WAY_MATCH_FAILED, SupplierInvoice.MatchStatus.FAILED,
                lines, 0L
            );
        }

        @Test void manual_approve_rejects_zero_total_without_posting() {
            SupplierInvoice si = failed(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                List.of(line(new BigDecimal("5"), BigDecimal.ZERO)));
            assertThatThrownBy(() -> si.manualApprove("override"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("total amount is");
            assertThat(si.pullPendingEvents()).isEmpty();   // no SupplierInvoiceApproved / GL post
        }

        @Test void manual_approve_rejects_subtotal_drifted_from_lines() {
            // Lines sum to 400 but header subtotal says 0 — the stale-header shape.
            SupplierInvoice si = failed(BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("400.00"),
                List.of(line(new BigDecimal("5"), new BigDecimal("80"))));
            assertThatThrownBy(() -> si.manualApprove("override"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sum of line totals");
        }

        @Test void passes_for_a_consistent_invoice() {
            SupplierInvoice si = failed(new BigDecimal("400.00"), BigDecimal.ZERO, new BigDecimal("400.00"),
                List.of(line(new BigDecimal("5"), new BigDecimal("80"))));
            org.assertj.core.api.Assertions.assertThatCode(si::assertConsistent).doesNotThrowAnyException();
        }
    }

    @Nested
    class ManualReject {
        @Test void only_allowed_from_three_way_match_failed() {
            SupplierInvoice si = record(SupplierInvoice.MatchStatus.MATCHED);
            si.pullPendingEvents();
            assertThatThrownBy(() -> si.manualReject("typo"))
                .isInstanceOf(IllegalStateException.class);
        }

        @Test void flips_status_to_cancelled() {
            SupplierInvoice si = record(SupplierInvoice.MatchStatus.FAILED);
            si.manualReject("supplier sent the wrong invoice");
            assertThat(si.status()).isEqualTo(SupplierInvoice.Status.CANCELLED);
        }

        @Test void emits_rejected_event() {
            SupplierInvoice si = record(SupplierInvoice.MatchStatus.FAILED);
            si.manualReject("supplier sent the wrong invoice");
            List<DomainEvent> events = si.pullPendingEvents();
            assertThat(events).hasSize(1).first().isInstanceOf(SupplierInvoiceRejected.class);
            SupplierInvoiceRejected e = (SupplierInvoiceRejected) events.get(0);
            assertThat(e.purchaseOrderHeaderId()).isEqualTo(PO_HEADER);
            assertThat(e.reason()).isEqualTo("supplier sent the wrong invoice");
        }
    }
}
