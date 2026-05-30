package com.northwood.finance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.northwood.finance.application.JournalEntryService.LineCost;
import com.northwood.finance.domain.CustomerInvoice;
import com.northwood.finance.domain.JournalEntry;
import com.northwood.finance.domain.JournalEntryId;
import com.northwood.finance.domain.JournalEntryLine;
import com.northwood.finance.domain.JournalEntryRepository;
import com.northwood.finance.application.GlAccountLookup;
import com.northwood.finance.application.GlAccountLookup.GlAccount;
import com.northwood.product.domain.ValuationClass;
import com.northwood.shared.domain.Currencies;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class JournalEntryServicePostingsTest {

    private static final UUID PRODUCT_RM = UUID.randomUUID();
    private static final UUID PRODUCT_FG = UUID.randomUUID();
    private static final UUID PRODUCT_UNCLASSIFIED = UUID.randomUUID();
    private static final LocalDate POSTING_DATE = LocalDate.of(2026, 6, 1);

    @Mock JournalEntryRepository journals;
    @Mock JournalEntrySummaryQueryPort summaries;
    @Mock GlAccountLookup glAccounts;
    @Mock ProductCardLookup productCards;

    private JournalEntryService service;

    @BeforeEach
    void setUp() {
        service = new JournalEntryService(journals, summaries, glAccounts, productCards);
        // Default GL-account resolution; lenient so tests that never post (zero-total
        // skips, reverse-entry rejections) don't trigger UnnecessaryStubbingException.
        lenient().when(glAccounts.byCode(any())).thenAnswer(inv -> {
            String code = inv.getArgument(0);
            return new GlAccount(UUID.randomUUID(), code, "Account " + code);
        });
    }

    private JournalEntry capturedSave() {
        ArgumentCaptor<JournalEntry> cap = ArgumentCaptor.forClass(JournalEntry.class);
        verify(journals).save(cap.capture());
        return cap.getValue();
    }

    /** Sum of line.debitAmount() for lines whose accountCode equals {@code code}. */
    private BigDecimal debitFor(JournalEntry entry, String code) {
        return entry.lines().stream()
            .filter(l -> code.equals(l.accountCode()))
            .map(JournalEntryLine::debitAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal creditFor(JournalEntry entry, String code) {
        return entry.lines().stream()
            .filter(l -> code.equals(l.accountCode()))
            .map(JournalEntryLine::creditAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Nested
    class TwoLinePostings {

        @Test void supplier_invoice_approval_posts_dr_grni_cr_ap() {
            UUID invoiceId = UUID.randomUUID();
            service.postSupplierInvoiceApproval(
                invoiceId, "Acme Supplies", "INV-001",
                new BigDecimal("1100.00"), Currencies.AUD, POSTING_DATE
            );

            JournalEntry entry = capturedSave();
            assertThat(entry.sourceDocumentType()).isEqualTo(JournalEntry.SourceDocumentType.SUPPLIER_INVOICE);
            assertThat(entry.sourceDocumentId()).isEqualTo(invoiceId);
            assertThat(entry.lines()).hasSize(2);
            assertThat(debitFor(entry, "1300")).isEqualByComparingTo("1100.00");
            assertThat(creditFor(entry, "2100")).isEqualByComparingTo("1100.00");
        }

        @Test void supplier_payment_posts_dr_ap_cr_bank() {
            UUID paymentId = UUID.randomUUID();
            service.postSupplierPayment(
                paymentId, "Acme Supplies", "PMT-001",
                new BigDecimal("1100.00"), Currencies.AUD, POSTING_DATE
            );

            JournalEntry entry = capturedSave();
            assertThat(entry.sourceDocumentType()).isEqualTo(JournalEntry.SourceDocumentType.SUPPLIER_PAYMENT);
            assertThat(debitFor(entry, "2100")).isEqualByComparingTo("1100.00");
            assertThat(creditFor(entry, "1000")).isEqualByComparingTo("1100.00");
        }

        @Test void customer_invoice_creation_posts_dr_ar_cr_revenue() {
            UUID invoiceId = UUID.randomUUID();
            service.postCustomerInvoiceCreation(
                invoiceId, "Globex Ltd", "CINV-001",
                new BigDecimal("550.00"), Currencies.AUD, POSTING_DATE
            );

            JournalEntry entry = capturedSave();
            assertThat(entry.sourceDocumentType()).isEqualTo(JournalEntry.SourceDocumentType.CUSTOMER_INVOICE);
            assertThat(debitFor(entry, "1100")).isEqualByComparingTo("550.00");
            assertThat(creditFor(entry, "4000")).isEqualByComparingTo("550.00");
        }

        @Test void customer_payment_commercial_posts_dr_bank_cr_ar() {
            UUID paymentId = UUID.randomUUID();
            service.postCustomerPayment(
                paymentId, "Globex Ltd", "PMT-002",
                new BigDecimal("550.00"), Currencies.AUD, POSTING_DATE,
                CustomerInvoice.InvoiceType.COMMERCIAL
            );

            JournalEntry entry = capturedSave();
            assertThat(entry.sourceDocumentType()).isEqualTo(JournalEntry.SourceDocumentType.CUSTOMER_PAYMENT);
            assertThat(debitFor(entry, "1000")).isEqualByComparingTo("550.00");
            assertThat(creditFor(entry, "1100")).isEqualByComparingTo("550.00");
        }

        @Test void customer_payment_prepayment_posts_dr_bank_cr_customer_deposits() {
            UUID paymentId = UUID.randomUUID();
            service.postCustomerPayment(
                paymentId, "Globex Ltd", "PMT-003",
                new BigDecimal("550.00"), Currencies.AUD, POSTING_DATE,
                CustomerInvoice.InvoiceType.PREPAYMENT
            );

            JournalEntry entry = capturedSave();
            assertThat(entry.sourceDocumentType()).isEqualTo(JournalEntry.SourceDocumentType.CUSTOMER_PAYMENT);
            assertThat(debitFor(entry, "1000")).isEqualByComparingTo("550.00");
            assertThat(creditFor(entry, "2110")).isEqualByComparingTo("550.00");
        }

        // §2.31 Slice C: deferred-revenue recognition at shipment for a
        // prepayment invoice. The journal is Dr 2110 Customer Deposits / Cr
        // 4000 Sales Revenue at total amount (tax-inclusive).
        @Test void prepayment_revenue_recognition_posts_dr_customer_deposits_cr_revenue() {
            UUID invoiceId = UUID.randomUUID();
            service.postPrepaymentRevenueRecognition(
                invoiceId, "Globex Ltd", "INV-PREPAY-001",
                new BigDecimal("550.00"), Currencies.AUD, POSTING_DATE
            );

            JournalEntry entry = capturedSave();
            assertThat(entry.sourceDocumentType()).isEqualTo(JournalEntry.SourceDocumentType.CUSTOMER_INVOICE);
            assertThat(debitFor(entry, "2110")).isEqualByComparingTo("550.00");
            assertThat(creditFor(entry, "4000")).isEqualByComparingTo("550.00");
        }

        // §2.34: refund on a cancelled prepayment/deposit order — the inverse
        // of the original payment receipt (Dr 2110 Customer Deposits / Cr 1000 Bank).
        @Test void customer_refund_posts_dr_customer_deposits_cr_bank() {
            UUID invoiceId = UUID.randomUUID();
            service.postCustomerRefund(
                invoiceId, "Globex Ltd", "INV-DEP-001",
                new BigDecimal("150.00"), Currencies.AUD, POSTING_DATE
            );

            JournalEntry entry = capturedSave();
            assertThat(entry.sourceDocumentType()).isEqualTo(JournalEntry.SourceDocumentType.CUSTOMER_REFUND);
            assertThat(entry.sourceDocumentId()).isEqualTo(invoiceId);
            assertThat(debitFor(entry, "2110")).isEqualByComparingTo("150.00");
            assertThat(creditFor(entry, "1000")).isEqualByComparingTo("150.00");
        }

        @Test void customer_refund_zero_amount_skips_save() {
            service.postCustomerRefund(
                UUID.randomUUID(), "Globex Ltd", "INV-ZERO",
                BigDecimal.ZERO, Currencies.AUD, POSTING_DATE
            );

            verify(journals, never()).save(any());
        }

        @Test void posting_defaults_currency_to_AUD_when_null() {
            service.postSupplierPayment(
                UUID.randomUUID(), "Acme", "PMT-X",
                new BigDecimal("100.00"), null, POSTING_DATE
            );

            assertThat(capturedSave().currencyCode()).isEqualTo(Currencies.AUD);
        }
    }

    @Nested
    class GoodsReceivedMultiDebit {

        @Test void single_class_posts_dr_rm_inventory_cr_grni() {
            when(productCards.findValuationClass(PRODUCT_RM)).thenReturn(Optional.of(ValuationClass.RAW_MATERIALS));

            service.postGoodsReceived(
                UUID.randomUUID(), "GR-001",
                List.of(new LineCost(PRODUCT_RM, new BigDecimal("400.00"))),
                Currencies.AUD, POSTING_DATE
            );

            JournalEntry entry = capturedSave();
            assertThat(debitFor(entry, "1210")).isEqualByComparingTo("400.00");
            assertThat(creditFor(entry, "1300")).isEqualByComparingTo("400.00");
            assertThat(debitFor(entry, "1200")).isEqualByComparingTo("0");
        }

        @Test void multi_class_produces_one_debit_per_inventory_account_one_grni_credit() {
            when(productCards.findValuationClass(PRODUCT_RM)).thenReturn(Optional.of(ValuationClass.RAW_MATERIALS));
            when(productCards.findValuationClass(PRODUCT_FG)).thenReturn(Optional.of(ValuationClass.FINISHED_GOODS));

            service.postGoodsReceived(
                UUID.randomUUID(), "GR-002",
                List.of(
                    new LineCost(PRODUCT_RM, new BigDecimal("300.00")),
                    new LineCost(PRODUCT_FG, new BigDecimal("700.00"))
                ),
                Currencies.AUD, POSTING_DATE
            );

            JournalEntry entry = capturedSave();
            assertThat(entry.lines()).hasSize(3);  // 2 debits + 1 credit
            assertThat(debitFor(entry, "1210")).isEqualByComparingTo("300.00");
            assertThat(debitFor(entry, "1220")).isEqualByComparingTo("700.00");
            assertThat(creditFor(entry, "1300")).isEqualByComparingTo("1000.00");
        }

        @Test void missing_valuation_class_falls_back_to_generic_inventory_1200() {
            when(productCards.findValuationClass(PRODUCT_UNCLASSIFIED)).thenReturn(Optional.empty());

            service.postGoodsReceived(
                UUID.randomUUID(), "GR-003",
                List.of(new LineCost(PRODUCT_UNCLASSIFIED, new BigDecimal("200.00"))),
                Currencies.AUD, POSTING_DATE
            );

            JournalEntry entry = capturedSave();
            assertThat(debitFor(entry, "1200")).isEqualByComparingTo("200.00");
            assertThat(creditFor(entry, "1300")).isEqualByComparingTo("200.00");
        }

        @Test void zero_total_skips_save_entirely() {
            service.postGoodsReceived(
                UUID.randomUUID(), "GR-EMPTY",
                List.of(new LineCost(PRODUCT_RM, BigDecimal.ZERO)),
                Currencies.AUD, POSTING_DATE
            );

            verify(journals, never()).save(any());
        }
    }

    @Nested
    class ShipmentCostMultiDebitMultiCredit {

        @Test void per_class_split_dr_cogs_cr_inventory_pair_per_class() {
            when(productCards.findValuationClass(PRODUCT_RM)).thenReturn(Optional.of(ValuationClass.RAW_MATERIALS));
            when(productCards.findValuationClass(PRODUCT_FG)).thenReturn(Optional.of(ValuationClass.FINISHED_GOODS));

            service.postShipmentCost(
                UUID.randomUUID(), "SHP-001",
                List.of(
                    new LineCost(PRODUCT_RM, new BigDecimal("100.00")),
                    new LineCost(PRODUCT_FG, new BigDecimal("400.00"))
                ),
                Currencies.AUD, POSTING_DATE
            );

            JournalEntry entry = capturedSave();
            assertThat(entry.lines()).hasSize(4);  // 2 cogs debits + 2 inventory credits
            assertThat(debitFor(entry, "5200")).isEqualByComparingTo("100.00");  // RM COGS
            assertThat(debitFor(entry, "5000")).isEqualByComparingTo("400.00");  // FG COGS
            assertThat(creditFor(entry, "1210")).isEqualByComparingTo("100.00"); // RM inventory
            assertThat(creditFor(entry, "1220")).isEqualByComparingTo("400.00"); // FG inventory
        }

        @Test void unclassified_product_falls_back_to_5000_and_1200() {
            when(productCards.findValuationClass(PRODUCT_UNCLASSIFIED)).thenReturn(Optional.empty());

            service.postShipmentCost(
                UUID.randomUUID(), "SHP-002",
                List.of(new LineCost(PRODUCT_UNCLASSIFIED, new BigDecimal("250.00"))),
                Currencies.AUD, POSTING_DATE
            );

            JournalEntry entry = capturedSave();
            assertThat(debitFor(entry, "5000")).isEqualByComparingTo("250.00");
            assertThat(creditFor(entry, "1200")).isEqualByComparingTo("250.00");
        }

        @Test void zero_total_skips_save_entirely() {
            service.postShipmentCost(
                UUID.randomUUID(), "SHP-EMPTY",
                List.of(new LineCost(PRODUCT_FG, BigDecimal.ZERO)),
                Currencies.AUD, POSTING_DATE
            );

            verify(journals, never()).save(any());
        }
    }

    @Nested
    class ReverseEntry {

        private JournalEntry posted(JournalEntryId id, JournalEntry.Status status, BigDecimal amount) {
            return JournalEntry.reconstitute(
                id, "JE-X", POSTING_DATE,
                JournalEntry.SourceModule.FINANCE, JournalEntry.SourceDocumentType.SUPPLIER_INVOICE, UUID.randomUUID(),
                "test", status, Currencies.AUD, BigDecimal.ONE, Instant.now(),
                List.of(
                    JournalEntryLine.debit(10, UUID.randomUUID(), "5000", "COGS", amount, "d", POSTING_DATE),
                    JournalEntryLine.credit(20, UUID.randomUUID(), "2100", "AP", amount, "c", POSTING_DATE)
                ),
                1L
            );
        }

        @Test void posts_swapped_lines_and_marks_original_reversed() {
            JournalEntryId id = JournalEntryId.of(UUID.randomUUID());
            when(journals.findById(id)).thenReturn(Optional.of(posted(id, JournalEntry.Status.POSTED, new BigDecimal("100.00"))));

            UUID reversalId = service.reverseEntry(id.value(), "test reason", POSTING_DATE);

            assertThat(reversalId).isNotEqualTo(id.value());
            ArgumentCaptor<JournalEntry> cap = ArgumentCaptor.forClass(JournalEntry.class);
            verify(journals).save(cap.capture());
            JournalEntry reversal = cap.getValue();
            assertThat(reversal.sourceDocumentType()).isEqualTo(JournalEntry.SourceDocumentType.JOURNAL_REVERSAL);
            assertThat(reversal.sourceDocumentId()).isEqualTo(id.value());
            // Lines are swapped: original Dr 5000 / Cr 2100 → Cr 5000 / Dr 2100
            assertThat(creditFor(reversal, "5000")).isEqualByComparingTo("100.00");
            assertThat(debitFor(reversal, "2100")).isEqualByComparingTo("100.00");
            verify(journals).markReversed(id);
        }

        @Test void rejects_non_posted_status() {
            JournalEntryId id = JournalEntryId.of(UUID.randomUUID());
            when(journals.findById(id)).thenReturn(Optional.of(posted(id, JournalEntry.Status.REVERSED, new BigDecimal("100.00"))));

            assertThatThrownBy(() -> service.reverseEntry(id.value(), "test", POSTING_DATE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be posted");
            verify(journals, never()).save(any());
            verify(journals, never()).markReversed(any());
        }

        @Test void rejects_when_id_unknown() {
            JournalEntryId id = JournalEntryId.of(UUID.randomUUID());
            when(journals.findById(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.reverseEntry(id.value(), "test", POSTING_DATE))
                .isInstanceOf(IllegalArgumentException.class);
            verify(journals, never()).save(any());
        }
    }

    @Nested
    class StockAdjustmentPosting {

        @Test void gain_posts_dr_inventory_cr_inventory_adjustment() {
            when(productCards.findValuationClass(PRODUCT_RM)).thenReturn(Optional.of(ValuationClass.RAW_MATERIALS));

            service.postStockAdjustment(
                UUID.randomUUID(), "ADJ-001", PRODUCT_RM,
                new BigDecimal("50.00"), true, Currencies.AUD, POSTING_DATE);

            JournalEntry entry = capturedSave();
            assertThat(entry.sourceDocumentType()).isEqualTo(JournalEntry.SourceDocumentType.STOCK_ADJUSTMENT);
            assertThat(debitFor(entry, "1210")).isEqualByComparingTo("50.00");   // gain Dr's RM inventory
            assertThat(creditFor(entry, "5400")).isEqualByComparingTo("50.00");  // Cr Inventory Adjustment
        }

        @Test void loss_posts_dr_inventory_adjustment_cr_inventory() {
            when(productCards.findValuationClass(PRODUCT_FG)).thenReturn(Optional.of(ValuationClass.FINISHED_GOODS));

            service.postStockAdjustment(
                UUID.randomUUID(), "ADJ-002", PRODUCT_FG,
                new BigDecimal("30.00"), false, Currencies.AUD, POSTING_DATE);

            JournalEntry entry = capturedSave();
            assertThat(debitFor(entry, "5400")).isEqualByComparingTo("30.00");   // loss Dr's Inventory Adjustment
            assertThat(creditFor(entry, "1220")).isEqualByComparingTo("30.00");  // Cr FG inventory
        }

        @Test void unclassified_product_falls_back_to_generic_inventory_1200() {
            when(productCards.findValuationClass(PRODUCT_UNCLASSIFIED)).thenReturn(Optional.empty());

            service.postStockAdjustment(
                UUID.randomUUID(), "ADJ-003", PRODUCT_UNCLASSIFIED,
                new BigDecimal("20.00"), true, Currencies.AUD, POSTING_DATE);

            JournalEntry entry = capturedSave();
            assertThat(debitFor(entry, "1200")).isEqualByComparingTo("20.00");
            assertThat(creditFor(entry, "5400")).isEqualByComparingTo("20.00");
        }

        @Test void zero_amount_skips_save_entirely() {
            service.postStockAdjustment(
                UUID.randomUUID(), "ADJ-ZERO", PRODUCT_RM,
                BigDecimal.ZERO, true, Currencies.AUD, POSTING_DATE);

            verify(journals, never()).save(any());
        }
    }

    // §2.42 Perpetual WIP — the three new legs.
    @Nested
    class WorkInProgressPostings {

        @Test void raw_materials_issued_posts_dr_wip_cr_rm_inventory() {
            when(productCards.findValuationClass(PRODUCT_RM)).thenReturn(Optional.of(ValuationClass.RAW_MATERIALS));

            service.postWorkInProgressCharge(
                UUID.randomUUID(), "WO-1",
                List.of(new LineCost(PRODUCT_RM, new BigDecimal("120.00"))),
                Currencies.AUD, POSTING_DATE);

            JournalEntry entry = capturedSave();
            assertThat(entry.sourceDocumentType()).isEqualTo(JournalEntry.SourceDocumentType.WORK_ORDER_WIP);
            assertThat(debitFor(entry, "1230")).isEqualByComparingTo("120.00");
            assertThat(creditFor(entry, "1210")).isEqualByComparingTo("120.00");
        }

        @Test void sub_assemblies_consumed_posts_dr_wip_cr_fg_inventory() {
            when(productCards.findValuationClass(PRODUCT_FG)).thenReturn(Optional.of(ValuationClass.SEMI_FINISHED_GOODS));

            service.postSubAssemblyConsumption(
                UUID.randomUUID(), "WO-PARENT",
                List.of(new LineCost(PRODUCT_FG, new BigDecimal("90.00"))),
                Currencies.AUD, POSTING_DATE);

            JournalEntry entry = capturedSave();
            assertThat(entry.sourceDocumentType()).isEqualTo(JournalEntry.SourceDocumentType.WORK_ORDER_WIP);
            assertThat(debitFor(entry, "1230")).isEqualByComparingTo("90.00");
            assertThat(creditFor(entry, "1220")).isEqualByComparingTo("90.00");
        }

        @Test void work_order_completion_posts_dr_fg_inventory_cr_wip() {
            when(productCards.findValuationClass(PRODUCT_FG)).thenReturn(Optional.of(ValuationClass.FINISHED_GOODS));

            service.postWorkOrderCompletion(
                UUID.randomUUID(), "WO-1", PRODUCT_FG,
                new BigDecimal("210.00"), Currencies.AUD, POSTING_DATE);

            JournalEntry entry = capturedSave();
            assertThat(entry.sourceDocumentType()).isEqualTo(JournalEntry.SourceDocumentType.WORK_ORDER_COMPLETION);
            assertThat(debitFor(entry, "1220")).isEqualByComparingTo("210.00");
            assertThat(creditFor(entry, "1230")).isEqualByComparingTo("210.00");
        }

        @Test void wip_legs_net_to_zero_across_charge_consume_complete() {
            when(productCards.findValuationClass(PRODUCT_RM)).thenReturn(Optional.of(ValuationClass.RAW_MATERIALS));
            when(productCards.findValuationClass(PRODUCT_FG)).thenReturn(Optional.of(ValuationClass.FINISHED_GOODS));
            UUID wo = UUID.randomUUID();

            // Dr WIP 120 (materials) + Dr WIP 90 (sub-assemblies) ...
            service.postWorkInProgressCharge(wo, "WO-1",
                List.of(new LineCost(PRODUCT_RM, new BigDecimal("120.00"))), Currencies.AUD, POSTING_DATE);
            service.postSubAssemblyConsumption(wo, "WO-1",
                List.of(new LineCost(PRODUCT_FG, new BigDecimal("90.00"))), Currencies.AUD, POSTING_DATE);
            // ... Cr WIP 210 (completion at FG standard cost = rolled-up materials).
            service.postWorkOrderCompletion(wo, "WO-1", PRODUCT_FG,
                new BigDecimal("210.00"), Currencies.AUD, POSTING_DATE);

            ArgumentCaptor<JournalEntry> cap = ArgumentCaptor.forClass(JournalEntry.class);
            verify(journals, times(3)).save(cap.capture());
            BigDecimal wipDr = cap.getAllValues().stream()
                .map(e -> debitFor(e, "1230")).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal wipCr = cap.getAllValues().stream()
                .map(e -> creditFor(e, "1230")).reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(wipDr).isEqualByComparingTo("210.00");
            assertThat(wipCr).isEqualByComparingTo("210.00");
            assertThat(wipDr.subtract(wipCr)).isEqualByComparingTo("0");  // WIP nets to zero
        }

        @Test void zero_total_charge_skips_save() {
            service.postWorkInProgressCharge(
                UUID.randomUUID(), "WO-EMPTY",
                List.of(new LineCost(PRODUCT_RM, BigDecimal.ZERO)),
                Currencies.AUD, POSTING_DATE);

            verify(journals, never()).save(any());
        }

        @Test void zero_completion_amount_skips_save() {
            service.postWorkOrderCompletion(
                UUID.randomUUID(), "WO-EMPTY", PRODUCT_FG,
                BigDecimal.ZERO, Currencies.AUD, POSTING_DATE);

            verify(journals, never()).save(any());
        }
    }
}
