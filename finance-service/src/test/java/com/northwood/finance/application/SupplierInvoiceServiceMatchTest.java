package com.northwood.finance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.northwood.finance.application.dto.RecordSupplierInvoiceCommand;
import com.northwood.finance.application.inbox.PurchaseOrderLineFactsProjection;
import com.northwood.finance.application.inbox.PurchaseOrderLineFactsProjection.LineFacts;
import com.northwood.finance.domain.SupplierInvoice;
import com.northwood.finance.domain.SupplierInvoiceRepository;
import com.northwood.shared.domain.Currencies;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * Service-level tests for the 3-way match logic in
 * {@link SupplierInvoiceService#recordInvoice}. Quantity-only behaviour is
 * covered by {@code SupplierInvoiceTest} (domain). This class focuses on the
 * §1.1 price-variance extension shipped 2026-05-06.
 */
class SupplierInvoiceServiceMatchTest {

    private static final UUID PO_HEADER = UUID.randomUUID();
    private static final UUID PO_LINE = UUID.randomUUID();
    private static final UUID PRODUCT = UUID.randomUUID();
    private static final UUID SUPPLIER = UUID.randomUUID();
    private static final UUID GR_HEADER = UUID.randomUUID();
    private static final UUID GR_LINE = UUID.randomUUID();
    private static final BigDecimal TOLERANCE_2_PERCENT = new BigDecimal("2.0");

    private SupplierInvoiceRepository invoices;
    private PurchaseOrderLineFactsProjection purchaseOrderLineFacts;
    private JournalEntryService journals;
    private SupplierInvoiceService service;

    @BeforeEach
    void setUp() {
        invoices = Mockito.mock(SupplierInvoiceRepository.class);
        purchaseOrderLineFacts = Mockito.mock(PurchaseOrderLineFactsProjection.class);
        journals = Mockito.mock(JournalEntryService.class);
        service = new SupplierInvoiceService(invoices, purchaseOrderLineFacts, journals, TOLERANCE_2_PERCENT);
    }

    private LineFacts factsWith(BigDecimal poUnitPrice, BigDecimal received, BigDecimal alreadyInvoiced) {
        return new LineFacts(
            PO_LINE, PO_HEADER, SUPPLIER, "Acme", Currencies.AUD,
            PRODUCT, "RM-X", "Raw X",
            new BigDecimal("10"),  // ordered
            poUnitPrice,
            received,
            alreadyInvoiced
        );
    }

    private RecordSupplierInvoiceCommand command(BigDecimal qty, BigDecimal invoiceUnitPrice) {
        return new RecordSupplierInvoiceCommand(
            "INV-001", "SUPPLIER-001",
            PO_HEADER, GR_HEADER,
            SUPPLIER, "SUP-001", "Acme",
            Currencies.AUD,
            List.of(new RecordSupplierInvoiceCommand.Line(
                PO_LINE, GR_LINE,
                PRODUCT, "RM-X", "Raw X",
                qty, invoiceUnitPrice,
                BigDecimal.ZERO
            ))
        );
    }

    private SupplierInvoice savedInvoice() {
        ArgumentCaptor<SupplierInvoice> cap = ArgumentCaptor.forClass(SupplierInvoice.class);
        verify(invoices).save(cap.capture());
        return cap.getValue();
    }

    @Nested
    class PriceVariance {

        @Test void exact_price_match_within_tolerance_passes() {
            when(purchaseOrderLineFacts.findByLineId(PO_LINE))
                .thenReturn(factsWith(new BigDecimal("100.00"), new BigDecimal("5"), BigDecimal.ZERO));

            service.recordInvoice(command(new BigDecimal("5"), new BigDecimal("100.00")));

            assertThat(savedInvoice().status()).isEqualTo(SupplierInvoice.Status.APPROVED);
            verify(journals, times(1)).postSupplierInvoiceApproval(any(), any(), any(), any(), any(), any());
        }

        @Test void price_just_under_tolerance_passes() {
            // 1.5% variance < 2.0% → ok
            when(purchaseOrderLineFacts.findByLineId(PO_LINE))
                .thenReturn(factsWith(new BigDecimal("100.00"), new BigDecimal("5"), BigDecimal.ZERO));

            service.recordInvoice(command(new BigDecimal("5"), new BigDecimal("101.50")));

            assertThat(savedInvoice().status()).isEqualTo(SupplierInvoice.Status.APPROVED);
        }

        @Test void price_at_tolerance_boundary_passes() {
            // 2.0% variance == 2.0% → not "outside" tolerance
            when(purchaseOrderLineFacts.findByLineId(PO_LINE))
                .thenReturn(factsWith(new BigDecimal("100.00"), new BigDecimal("5"), BigDecimal.ZERO));

            service.recordInvoice(command(new BigDecimal("5"), new BigDecimal("102.00")));

            assertThat(savedInvoice().status()).isEqualTo(SupplierInvoice.Status.APPROVED);
        }

        @Test void price_just_above_tolerance_fails_match() {
            // 3% variance > 2% → fails match, parks at three_way_match_failed
            when(purchaseOrderLineFacts.findByLineId(PO_LINE))
                .thenReturn(factsWith(new BigDecimal("100.00"), new BigDecimal("5"), BigDecimal.ZERO));

            service.recordInvoice(command(new BigDecimal("5"), new BigDecimal("103.00")));

            assertThat(savedInvoice().status()).isEqualTo(SupplierInvoice.Status.THREE_WAY_MATCH_FAILED);
            verify(journals, never()).postSupplierInvoiceApproval(any(), any(), any(), any(), any(), any());
            verify(purchaseOrderLineFacts, never()).bumpInvoiced(eq(PO_LINE), any());
        }

        @Test void supplier_charges_lower_outside_tolerance_also_fails() {
            // -10% variance > 2% → fails (we use abs)
            when(purchaseOrderLineFacts.findByLineId(PO_LINE))
                .thenReturn(factsWith(new BigDecimal("100.00"), new BigDecimal("5"), BigDecimal.ZERO));

            service.recordInvoice(command(new BigDecimal("5"), new BigDecimal("90.00")));

            assertThat(savedInvoice().status()).isEqualTo(SupplierInvoice.Status.THREE_WAY_MATCH_FAILED);
        }

        @Test void zero_po_unit_price_skips_price_check_quantity_still_runs() {
            // PO unit_price = 0 (e.g. seed data without a price). Price check
            // is skipped; quantity-only check still runs.
            when(purchaseOrderLineFacts.findByLineId(PO_LINE))
                .thenReturn(factsWith(BigDecimal.ZERO, new BigDecimal("5"), BigDecimal.ZERO));

            service.recordInvoice(command(new BigDecimal("5"), new BigDecimal("100.00")));

            assertThat(savedInvoice().status()).isEqualTo(SupplierInvoice.Status.APPROVED);
        }

        @Test void price_outside_tolerance_takes_precedence_over_quantity_pass() {
            when(purchaseOrderLineFacts.findByLineId(PO_LINE))
                .thenReturn(factsWith(new BigDecimal("100.00"), new BigDecimal("10"), BigDecimal.ZERO));

            // qty 5 ≤ received 10 (would pass quantity), but price 110 vs 100 = 10% (fails)
            service.recordInvoice(command(new BigDecimal("5"), new BigDecimal("110.00")));

            assertThat(savedInvoice().status()).isEqualTo(SupplierInvoice.Status.THREE_WAY_MATCH_FAILED);
        }
    }
}
