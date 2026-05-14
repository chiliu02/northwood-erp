package com.northwood.finance.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northwood.finance.domain.events.SupplierInvoiceApproved;
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

    private static SupplierInvoice record(String matchOutcome) {
        return SupplierInvoice.record(
            "INT-001", "SUP-001", PO_HEADER, GR_HEADER,
            SUPPLIER, "ACME", "Acme Co",
            "AUD", List.of(line(new BigDecimal("5"), new BigDecimal("80"))),
            matchOutcome
        );
    }

    @Nested
    class Record {
        @Test void matched_outcome_emits_approved_event() {
            SupplierInvoice si = record("matched");
            assertThat(si.status()).isEqualTo("approved");
            assertThat(si.matchStatus()).isEqualTo("matched");
            List<DomainEvent> events = si.pullPendingEvents();
            assertThat(events).hasSize(1).first().isInstanceOf(SupplierInvoiceApproved.class);
        }

        @Test void failed_outcome_emits_no_event() {
            SupplierInvoice si = record("failed");
            assertThat(si.status()).isEqualTo("three_way_match_failed");
            assertThat(si.matchStatus()).isEqualTo("failed");
            assertThat(si.pullPendingEvents()).isEmpty();
        }

        @Test void variance_outcome_emits_no_event() {
            SupplierInvoice si = record("variance");
            assertThat(si.status()).isEqualTo("three_way_match_failed");
            assertThat(si.matchStatus()).isEqualTo("variance");
            assertThat(si.pullPendingEvents()).isEmpty();
        }

        @Test void rejects_empty_lines() {
            assertThatThrownBy(() -> SupplierInvoice.record(
                "INT", "SUP", PO_HEADER, GR_HEADER,
                SUPPLIER, "A", "A", "AUD", List.of(), "matched"
            )).isInstanceOf(IllegalArgumentException.class);
        }

        @Test void rejects_null_purchase_order_header_id() {
            assertThatThrownBy(() -> SupplierInvoice.record(
                "INT", "SUP", null, GR_HEADER,
                SUPPLIER, "A", "A", "AUD",
                List.of(line(new BigDecimal("5"), new BigDecimal("80"))),
                "matched"
            )).isInstanceOf(NullPointerException.class);
        }

        @Test void rejects_null_supplier_id() {
            assertThatThrownBy(() -> SupplierInvoice.record(
                "INT", "SUP", PO_HEADER, GR_HEADER,
                null, "A", "A", "AUD",
                List.of(line(new BigDecimal("5"), new BigDecimal("80"))),
                "matched"
            )).isInstanceOf(NullPointerException.class);
        }

        @Test void rejects_null_supplier_invoice_number() {
            assertThatThrownBy(() -> SupplierInvoice.record(
                "INT", null, PO_HEADER, GR_HEADER,
                SUPPLIER, "A", "A", "AUD",
                List.of(line(new BigDecimal("5"), new BigDecimal("80"))),
                "matched"
            )).isInstanceOf(NullPointerException.class);
        }

        @Test void totals_summed_from_lines() {
            SupplierInvoice si = SupplierInvoice.record(
                "INT", "SUP", PO_HEADER, GR_HEADER,
                SUPPLIER, "A", "A", "AUD",
                List.of(line(new BigDecimal("5"), new BigDecimal("80"))),
                "matched"
            );
            // 5 * 80 = 400
            assertThat(si.subtotalAmount()).isEqualByComparingTo(new BigDecimal("400.00"));
            assertThat(si.totalAmount()).isEqualByComparingTo(new BigDecimal("400.00"));
        }

        @Test void emitted_event_carries_total_amount() {
            SupplierInvoice si = record("matched");
            SupplierInvoiceApproved e = (SupplierInvoiceApproved) si.pullPendingEvents().get(0);
            assertThat(e.totalAmount()).isEqualByComparingTo(new BigDecimal("400.00"));
            assertThat(e.purchaseOrderHeaderId()).isEqualTo(PO_HEADER);
        }
    }

    @Nested
    class ManualApprove {
        @Test void only_allowed_from_three_way_match_failed() {
            SupplierInvoice si = record("matched");
            si.pullPendingEvents();
            assertThatThrownBy(() -> si.manualApprove("override"))
                .isInstanceOf(IllegalStateException.class);
        }

        @Test void flips_status_to_approved_and_emits_event() {
            SupplierInvoice si = record("failed");
            si.manualApprove("price-tolerance OK");
            assertThat(si.status()).isEqualTo("approved");
            assertThat(si.matchStatus()).isEqualTo("matched");
            assertThat(si.pullPendingEvents()).hasSize(1)
                .first().isInstanceOf(SupplierInvoiceApproved.class);
        }

        @Test void approval_event_routes_to_correct_PO() {
            SupplierInvoice si = record("variance");
            si.manualApprove("acceptable variance");
            SupplierInvoiceApproved e = (SupplierInvoiceApproved) si.pullPendingEvents().get(0);
            assertThat(e.purchaseOrderHeaderId()).isEqualTo(PO_HEADER);
            assertThat(e.totalAmount()).isEqualByComparingTo(new BigDecimal("400.00"));
        }
    }

    @Nested
    class ManualReject {
        @Test void only_allowed_from_three_way_match_failed() {
            SupplierInvoice si = record("matched");
            si.pullPendingEvents();
            assertThatThrownBy(() -> si.manualReject("typo"))
                .isInstanceOf(IllegalStateException.class);
        }

        @Test void flips_status_to_cancelled() {
            SupplierInvoice si = record("failed");
            si.manualReject("supplier sent the wrong invoice");
            assertThat(si.status()).isEqualTo("cancelled");
        }

        @Test void emits_no_event() {
            SupplierInvoice si = record("failed");
            si.manualReject("reason");
            assertThat(si.pullPendingEvents()).isEmpty();
        }
    }
}
