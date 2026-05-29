package com.northwood.finance.application;

import com.northwood.finance.application.GlAccountLookup.GlAccount;
import com.northwood.finance.application.dto.JournalEntryView;
import com.northwood.finance.domain.CustomerInvoice;
import com.northwood.finance.domain.JournalEntry;
import com.northwood.finance.domain.JournalEntryId;
import com.northwood.finance.domain.JournalEntryLine;
import com.northwood.finance.domain.JournalEntryRepository;
import com.northwood.product.domain.ValuationClass;
import com.northwood.shared.domain.Assert;
import com.northwood.shared.domain.Currencies;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for journal-entry posting. Phase 5b wires four
 * GL postings into the existing AP/AR flows, all balanced debit-equals-
 * credit pairs:
 *
 * <ul>
 *   <li>{@link #postSupplierInvoiceApproval}: Dr 5000 (COGS), Cr 2100 (AP)
 *       at the invoice's total amount.</li>
 *   <li>{@link #postSupplierPayment}: Dr 2100 (AP), Cr 1000 (Bank) at the
 *       payment's allocated amount.</li>
 *   <li>{@link #postCustomerInvoiceCreation}: Dr 1100 (AR), Cr 4000
 *       (Revenue) at the invoice's total amount.</li>
 *   <li>{@link #postCustomerPayment}: Dr 1000 (Bank), Cr 1100 (AR) at the
 *       payment's allocated amount.</li>
 * </ul>
 *
 * <p>Phase 5b simplifications: tax-on-purchase / tax-on-sales is folded
 * into the single debit/credit pair (the COGS / Revenue accounts absorb
 * the tax-inclusive total). A future slice can split out GST-input + GST-
 * output to dedicated tax accounts. Multi-currency journals stamp the
 * source's exchange rate on the header but post in the source currency;
 * GL consolidation to the company's base currency is a future slice.
 *
 * <p>Each posting runs in its calling service's transaction. The DB-level
 * deferred trigger {@code finance.enforce_journal_balance} verifies
 * debit-sum == credit-sum at COMMIT.
 */
@Service
public class JournalEntryService {

    /** Per-line cost attribution input for the multi-debit journals (§3.2). */
    public record LineCost(UUID productId, BigDecimal amount) {}

    private static final Logger log = LoggerFactory.getLogger(JournalEntryService.class);

    // GL account-code aliases for this service's posting policy live in
    // FinanceAccountCodes — reference-data identifiers, not status enums.
    // See docs/conventions.md → "What still uses string literals".

    private final JournalEntryRepository journalEntries;
    private final JournalEntrySummaryQueryPort journalEntrySummaries;
    private final GlAccountLookup glAccounts;
    private final ProductCardLookup productCards;

    public JournalEntryService(
        JournalEntryRepository journalEntries,
        JournalEntrySummaryQueryPort journalEntrySummaries,
        GlAccountLookup glAccounts,
        ProductCardLookup productCards
    ) {
        this.journalEntries = journalEntries;
        this.journalEntrySummaries = journalEntrySummaries;
        this.glAccounts = glAccounts;
        this.productCards = productCards;
    }

    @Transactional(readOnly = true)
    public Optional<JournalEntryView> findById(UUID journalEntryId) {
        return journalEntries.findById(JournalEntryId.of(journalEntryId)).map(JournalEntryView::from);
    }

    @Transactional(readOnly = true)
    public List<JournalEntrySummaryQueryPort.JournalEntrySummary> findRecent(
        int limit, Optional<String> sourceDocumentType
    ) {
        return journalEntrySummaries.findRecent(limit, sourceDocumentType);
    }

    /**
     * Inventory account code for a product, resolved by its valuation class:
     * {@link ValuationClass#RAW_MATERIALS} → 1210,
     * {@link ValuationClass#FINISHED_GOODS} / {@link ValuationClass#SEMI_FINISHED_GOODS} → 1220.
     * The switch is exhaustive over the enum — the schema CHECK on
     * {@code finance.product_card.valuation_class} keeps the producer-side
     * set aligned, so an unknown wire value is a data-integrity failure that
     * surfaces from {@code ValuationClass.fromDb} on read, not a fallback
     * here.
     *
     * <p><b>Silent-fallback contract on missing valuation-class projection.</b>
     * {@code finance.product_card.valuation_class} is an inbox-driven
     * column on the consolidated finance-side projection of Product master
     * facts (seeded on {@code ProductCreated}, populated on
     * {@code ValuationClassChanged}). The projection may not have caught up
     * at the moment a journal posts (event-stream race during burst-receive
     * on a fresh-volume boot, or a product was created but never assigned a
     * valuation class). Rather than blocking the journal entry — which would
     * freeze every receipt, shipment, payment, and customer-invoice flow —
     * the fallback posts to the generic 1200 Inventory account and emits a
     * DEBUG log naming the product. Per standard accounting reconciliation,
     * generic-1200 postings get reclassified to 1210/1220 once the
     * projection catches up; this is the order-tolerant trade-off captured
     * in the `*Projection` package convention. Throwing or hard-failing here
     * is rejected because the journal posting is the side effect of an
     * authoritative business action that already succeeded — refusing to
     * post the GL pair would leave the operational tables out of sync with
     * the GL.
     */
    private String inventoryAccountForProduct(UUID productId) {
        return productCards.findValuationClass(productId)
            .map(c -> switch (c) {
                case RAW_MATERIALS -> FinanceAccountCodes.RM_INVENTORY;
                case FINISHED_GOODS, SEMI_FINISHED_GOODS -> FinanceAccountCodes.FG_INVENTORY;
            })
            .orElseGet(() -> {
                log.debug("inventoryAccountForProduct product_id={} has no valuation-class projection row yet; "
                    + "falling back to generic Inventory account {} (projection-order-tolerant)", productId, FinanceAccountCodes.INVENTORY);
                return FinanceAccountCodes.INVENTORY;
            });
    }

    /**
     * COGS account code for a product, resolved by its valuation class:
     * {@link ValuationClass#RAW_MATERIALS} → 5200,
     * {@link ValuationClass#FINISHED_GOODS} / {@link ValuationClass#SEMI_FINISHED_GOODS} → 5000.
     * Mirrors {@link #inventoryAccountForProduct} — see that method's Javadoc
     * for the full silent-fallback rationale on missing projection
     * (projection-order-tolerant; DEBUG log on trigger; reclassification
     * later). Switch is exhaustive over the enum.
     */
    private String cogsAccountForProduct(UUID productId) {
        return productCards.findValuationClass(productId)
            .map(c -> switch (c) {
                case RAW_MATERIALS -> FinanceAccountCodes.MATERIALS_COGS;
                case FINISHED_GOODS, SEMI_FINISHED_GOODS -> FinanceAccountCodes.COGS;
            })
            .orElseGet(() -> {
                log.debug("cogsAccountForProduct product_id={} has no valuation-class projection row yet; "
                    + "falling back to generic COGS account {} (projection-order-tolerant)", productId, FinanceAccountCodes.COGS);
                return FinanceAccountCodes.COGS;
            });
    }

    @Transactional
    public void postSupplierInvoiceApproval(
        UUID supplierInvoiceHeaderId,
        String supplierName,
        String supplierInvoiceNumber,
        BigDecimal totalAmount,
        String currencyCode,
        LocalDate postingDate
    ) {
        // Perpetual-inventory accounting: at goods-receipt time the cost was
        // taken into 1200 Inventory with a corresponding 1300 GRNI accrual.
        // Invoice approval clears that accrual: Dr 1300 GRNI / Cr 2100 AP.
        // Pre-perpetual demos posted Dr 5000 COGS here; switching to GRNI
        // produces a temporally-correct income statement (COGS only hits at
        // shipment, not at invoice approval).
        post(
            JournalEntry.NUMBER_PREFIX + journalSuffix(),
            postingDate,
            JournalEntry.SourceModule.FINANCE,
            JournalEntry.SourceDocumentType.SUPPLIER_INVOICE,
            supplierInvoiceHeaderId,
            "Supplier invoice " + supplierInvoiceNumber + " (" + supplierName + ")",
            currencyCode,
            FinanceAccountCodes.GRNI,
            "Clear GRNI for " + supplierName + " invoice " + supplierInvoiceNumber,
            FinanceAccountCodes.AP,
            "Payable to " + supplierName,
            totalAmount,
            postingDate
        );
    }

    /**
     * Goods receipt → Dr 1200 Inventory / Cr 1300 GRNI for the total
     * receipt cost (sum of {@code receivedQuantity * unitCost} across the
     * receipt lines). The inventory account carries stock on the balance
     * sheet from this point until shipment; the GRNI account holds the
     * "we've received the goods but the invoice hasn't arrived" accrual
     * until invoice approval clears it.
     */
    @Transactional
    public void postGoodsReceived(
        UUID goodsReceiptHeaderId,
        String goodsReceiptNumber,
        List<LineCost> lineCosts,
        String currencyCode,
        LocalDate postingDate
    ) {
        BigDecimal totalCost = lineCosts.stream()
            .map(LineCost::amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalCost.signum() <= 0) {
            log.debug("skip GL post for receipt {} — totalCost {} is zero/negative",
                goodsReceiptNumber, totalCost);
            return;
        }

        // Group line costs by per-product inventory account. Most receipts
        // are single-class; multi-class produces a multi-debit journal that
        // still nets balanced against the single GRNI credit.
        java.util.Map<String, BigDecimal> debitsByAccount = new java.util.LinkedHashMap<>();
        for (LineCost lc : lineCosts) {
            if (lc.amount().signum() <= 0) continue;
            String accountCode = inventoryAccountForProduct(lc.productId());
            debitsByAccount.merge(accountCode, lc.amount(), BigDecimal::add);
        }

        postMultiDebit(
            JournalEntry.NUMBER_PREFIX + journalSuffix(),
            postingDate,
            JournalEntry.SourceModule.FINANCE,
            JournalEntry.SourceDocumentType.GOODS_RECEIPT,
            goodsReceiptHeaderId,
            "Goods receipt " + goodsReceiptNumber,
            currencyCode,
            debitsByAccount,
            "Stock received via " + goodsReceiptNumber,
            FinanceAccountCodes.GRNI,
            "GRNI accrual for " + goodsReceiptNumber
        );
    }

    /**
     * Shipment → Dr 5000 COGS / Cr 1200 Inventory for the cost of goods
     * shipped. This is the moment cost actually hits the income statement
     * under perpetual inventory; the matching revenue lands when the
     * customer invoice is created (Dr 1100 AR / Cr 4000 Revenue).
     */
    @Transactional
    public void postShipmentCost(
        UUID shipmentHeaderId,
        String shipmentNumber,
        List<LineCost> lineCosts,
        String currencyCode,
        LocalDate postingDate
    ) {
        BigDecimal totalCost = lineCosts.stream()
            .map(LineCost::amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalCost.signum() <= 0) {
            log.debug("skip GL post for shipment {} — totalCost {} is zero/negative",
                shipmentNumber, totalCost);
            return;
        }

        // Per-class split: one Dr COGS / Cr Inventory pair per valuation
        // class. Each pair is independently balanced; the journal as a whole
        // remains balanced because every line has its match. Most shipments
        // are single-class.
        java.util.Map<String, BigDecimal> cogsByAccount = new java.util.LinkedHashMap<>();
        java.util.Map<String, BigDecimal> inventoryByAccount = new java.util.LinkedHashMap<>();
        for (LineCost lc : lineCosts) {
            if (lc.amount().signum() <= 0) continue;
            String cogsCode = cogsAccountForProduct(lc.productId());
            String invCode = inventoryAccountForProduct(lc.productId());
            cogsByAccount.merge(cogsCode, lc.amount(), BigDecimal::add);
            inventoryByAccount.merge(invCode, lc.amount(), BigDecimal::add);
        }

        postMultiDebitMultiCredit(
            JournalEntry.NUMBER_PREFIX + journalSuffix(),
            postingDate,
            JournalEntry.SourceModule.FINANCE,
            JournalEntry.SourceDocumentType.SHIPMENT_COST,
            shipmentHeaderId,
            "Shipment " + shipmentNumber + " — cost of goods sold",
            currencyCode,
            cogsByAccount,
            "COGS for " + shipmentNumber,
            inventoryByAccount,
            "Stock issued via " + shipmentNumber
        );
    }

    /**
     * Stock adjustment → an inventory gain or loss against 5400 Inventory
     * Adjustment, valued at the product's standard cost ({@code amount} is the
     * positive valued magnitude, computed by the caller). A {@code gain}
     * (on-hand increased) Dr's the product's inventory account (1210/1220 via
     * valuation class) / Cr's 5400; a loss Dr's 5400 / Cr's the inventory
     * account. Mirrors the perpetual-inventory goods-receipt / shipment
     * postings — an inventory value change is never off-book (§2.29).
     */
    @Transactional
    public void postStockAdjustment(
        UUID stockAdjustmentId,
        String adjustmentNumber,
        UUID productId,
        BigDecimal amount,
        boolean gain,
        String currencyCode,
        LocalDate postingDate
    ) {
        if (amount == null || amount.signum() <= 0) {
            log.debug("skip GL post for stock adjustment {} — amount {} is zero/negative",
                adjustmentNumber, amount);
            return;
        }
        String inventoryAccount = inventoryAccountForProduct(productId);
        String debitAccount = gain ? inventoryAccount : FinanceAccountCodes.INVENTORY_ADJUSTMENT;
        String creditAccount = gain ? FinanceAccountCodes.INVENTORY_ADJUSTMENT : inventoryAccount;
        post(
            JournalEntry.NUMBER_PREFIX + journalSuffix(),
            postingDate,
            JournalEntry.SourceModule.FINANCE,
            JournalEntry.SourceDocumentType.STOCK_ADJUSTMENT,
            stockAdjustmentId,
            "Stock adjustment " + adjustmentNumber + (gain ? " — inventory gain" : " — inventory loss/shrinkage"),
            currencyCode,
            debitAccount,
            gain ? "Inventory gain via " + adjustmentNumber : "Inventory write-down via " + adjustmentNumber,
            creditAccount,
            gain ? "Adjustment offset for " + adjustmentNumber : "Stock removed via " + adjustmentNumber,
            amount,
            postingDate
        );
    }

    @Transactional
    public void postSupplierPayment(
        UUID paymentId,
        String supplierName,
        String paymentNumber,
        BigDecimal amount,
        String currencyCode,
        LocalDate postingDate
    ) {
        post(
            JournalEntry.NUMBER_PREFIX + journalSuffix(),
            postingDate,
            JournalEntry.SourceModule.FINANCE,
            JournalEntry.SourceDocumentType.SUPPLIER_PAYMENT,
            paymentId,
            "Supplier payment " + paymentNumber + " to " + supplierName,
            currencyCode,
            FinanceAccountCodes.AP,
            "Settle payable to " + supplierName,
            FinanceAccountCodes.BANK,
            "Bank disbursement to " + supplierName,
            amount,
            postingDate
        );
    }

    @Transactional
    public void postCustomerInvoiceCreation(
        UUID customerInvoiceHeaderId,
        String customerName,
        String invoiceNumber,
        BigDecimal totalAmount,
        String currencyCode,
        LocalDate postingDate
    ) {
        post(
            JournalEntry.NUMBER_PREFIX + journalSuffix(),
            postingDate,
            JournalEntry.SourceModule.FINANCE,
            JournalEntry.SourceDocumentType.CUSTOMER_INVOICE,
            customerInvoiceHeaderId,
            "Customer invoice " + invoiceNumber + " (" + customerName + ")",
            currencyCode,
            FinanceAccountCodes.AR,
            "Receivable from " + customerName,
            FinanceAccountCodes.REVENUE,
            "Sales revenue from " + customerName,
            totalAmount,
            postingDate
        );
    }

    /**
     * §2.31 Slice C. Deferred-revenue recognition at shipment for a
     * prepayment invoice: Dr 2110 Customer Deposits / Cr 4000 Sales Revenue
     * at the invoice's total amount. Posted in addition to the existing
     * Dr COGS / Cr Inventory pair (which fires for every shipment). The
     * caller — {@code ShipmentPostedCogsHandler} — gates this method on
     * {@code customer_invoice_header.revenue_recognized_at} so a redelivered
     * shipment can't post twice. Tax-inclusive — the GST split (§3.3) is
     * deferred indefinitely; revenue absorbs tax just like the on-shipment
     * Cr Revenue pair does today.
     */
    @Transactional
    public void postPrepaymentRevenueRecognition(
        UUID customerInvoiceHeaderId,
        String customerName,
        String invoiceNumber,
        BigDecimal totalAmount,
        String currencyCode,
        LocalDate postingDate
    ) {
        post(
            JournalEntry.NUMBER_PREFIX + journalSuffix(),
            postingDate,
            JournalEntry.SourceModule.FINANCE,
            JournalEntry.SourceDocumentType.CUSTOMER_INVOICE,
            customerInvoiceHeaderId,
            "Recognise revenue at shipment for prepayment invoice " + invoiceNumber + " " + customerName,
            currencyCode,
            FinanceAccountCodes.CUSTOMER_DEPOSITS,
            "Reclassify deposit at shipment for " + customerName,
            FinanceAccountCodes.REVENUE,
            "Recognise revenue for " + customerName,
            totalAmount,
            postingDate
        );
    }

    /**
     * §2.31 Slice B: branches the credit side on {@code invoiceType}.
     * {@link CustomerInvoice.InvoiceType#COMMERCIAL} → Cr 1100 AR (the
     * existing on-shipment flow, balancing the Dr AR posted at invoice
     * creation). {@link CustomerInvoice.InvoiceType#PREPAYMENT} →
     * Cr 2110 Customer Deposits (the liability we owe the customer until
     * shipment; reclassified to revenue at shipment in Slice C). The debit
     * side (Dr Cash) is the same regardless.
     */
    @Transactional
    public void postCustomerPayment(
        UUID paymentId,
        String customerName,
        String paymentNumber,
        BigDecimal amount,
        String currencyCode,
        LocalDate postingDate,
        CustomerInvoice.InvoiceType invoiceType
    ) {
        Assert.notNull(invoiceType, "invoiceType");
        // §2.31 prepayment + §2.32 deposit both park the receipt in 2110 Customer
        // Deposits (a liability) until shipment reclassifies it to revenue;
        // commercial + balance invoices settle the AR posted at invoice creation.
        boolean toDeposits = invoiceType == CustomerInvoice.InvoiceType.PREPAYMENT
            || invoiceType == CustomerInvoice.InvoiceType.DEPOSIT;
        String creditAccount = toDeposits
            ? FinanceAccountCodes.CUSTOMER_DEPOSITS
            : FinanceAccountCodes.AR;
        String creditMemo = toDeposits
            ? "Hold deposit from " + customerName + " (" + invoiceType.dbValue() + " invoice)"
            : "Settle receivable from " + customerName;
        post(
            JournalEntry.NUMBER_PREFIX + journalSuffix(),
            postingDate,
            JournalEntry.SourceModule.FINANCE,
            JournalEntry.SourceDocumentType.CUSTOMER_PAYMENT,
            paymentId,
            "Customer payment " + paymentNumber + " from " + customerName,
            currencyCode,
            FinanceAccountCodes.BANK,
            "Bank receipt from " + customerName,
            creditAccount,
            creditMemo,
            amount,
            postingDate
        );
    }

    /**
     * §3.7 Bulk reverse every posted journal entry that originated from the
     * given source document. Returns the new reversal entry ids. All
     * reversals run inside this method's transaction — if any one fails the
     * whole batch rolls back.
     *
     * <p>Idempotent against already-reversed entries: the underlying
     * {@code findPostedIdsBySource} query filters by {@code status='posted'},
     * so re-invoking with the same source document returns an empty list
     * after the first run.
     *
     * <p>Use cases (future): cancelling a customer-invoice cascades reversal
     * of the AR / Revenue entry; cancelling a supplier-payment cascades
     * reversal of the AP / Bank entry. Not invoked by the order-cancel flow
     * today (cancel only reaches pre-GL states).
     */
    @Transactional
    public List<UUID> reverseBySourceDocument(
        String sourceDocumentType,
        UUID sourceDocumentId,
        String reason,
        LocalDate postingDate
    ) {
        List<JournalEntryId> originals = journalEntries.findPostedIdsBySource(sourceDocumentType, sourceDocumentId);
        List<UUID> reversalIds = new java.util.ArrayList<>();
        for (JournalEntryId originalId : originals) {
            reversalIds.add(reverseEntry(originalId.value(), reason, postingDate));
        }
        log.info("bulk-reversed {} entries for source {}/{}",
            reversalIds.size(), sourceDocumentType, sourceDocumentId);
        return reversalIds;
    }

    /**
     * Reverse a posted journal entry. Posts a new entry with all debits and
     * credits swapped, then transitions the original from {@code 'posted'}
     * to {@code 'reversed'} (the only outbound transition the schema's
     * immutability trigger permits). Both writes happen in the same
     * transaction; if either fails the whole reversal is rolled back.
     */
    @Transactional
    public UUID reverseEntry(UUID originalJournalEntryId, String reason, LocalDate postingDate) {
        JournalEntryId originalId = JournalEntryId.of(originalJournalEntryId);
        JournalEntry original = journalEntries.findById(originalId)
            .orElseThrow(() -> new IllegalArgumentException(
                "No journal entry with id=" + originalJournalEntryId));
        Assert.state(original.status() == JournalEntry.Status.POSTED, "Cannot reverse journal " + originalJournalEntryId
                    + " in status=" + original.status().dbValue() + " (must be posted)");
        JournalEntry reversal = JournalEntry.reverseOf(original, reason, postingDate);
        journalEntries.save(reversal);
        journalEntries.markReversed(originalId);
        log.info(
            "reversed journal {} ({}); reversal {} posted, original now reversed",
            original.journalNumber(), originalJournalEntryId,
            reversal.journalNumber()
        );
        return reversal.id().value();
    }

    private void post(
        String journalNumber,
        LocalDate postingDate,
        JournalEntry.SourceModule sourceModule,
        JournalEntry.SourceDocumentType sourceDocumentType,
        UUID sourceDocumentId,
        String description,
        String currencyCode,
        String debitAccountCode,
        String debitDescription,
        String creditAccountCode,
        String creditDescription,
        BigDecimal amount,
        LocalDate linePostingDate
    ) {
        GlAccount debitAccount = glAccounts.byCode(debitAccountCode);
        GlAccount creditAccount = glAccounts.byCode(creditAccountCode);

        List<JournalEntryLine> lines = List.of(
            JournalEntryLine.debit(10, debitAccount.id(), debitAccount.code(), debitAccount.name(),
                amount, debitDescription, linePostingDate),
            JournalEntryLine.credit(20, creditAccount.id(), creditAccount.code(), creditAccount.name(),
                amount, creditDescription, linePostingDate)
        );

        JournalEntry entry = JournalEntry.post(
            journalNumber,
            postingDate,
            sourceModule,
            sourceDocumentType,
            sourceDocumentId,
            description,
            Currencies.orBase(currencyCode),
            BigDecimal.ONE,
            lines
        );
        journalEntries.save(entry);

        log.info("posted journal {} ({}) Dr {} Cr {} {} {}",
            journalNumber, sourceDocumentType,
            debitAccount.code(), creditAccount.code(),
            amount, currencyCode);
    }

    /**
     * Multi-debit / single-credit journal helper. Used by the per-class
     * receipt posting (multiple inventory-account debits, one GRNI credit).
     */
    private void postMultiDebit(
        String journalNumber,
        LocalDate postingDate,
        JournalEntry.SourceModule sourceModule,
        JournalEntry.SourceDocumentType sourceDocumentType,
        UUID sourceDocumentId,
        String description,
        String currencyCode,
        java.util.Map<String, BigDecimal> debitsByAccount,
        String debitDescription,
        String creditAccountCode,
        String creditDescription
    ) {
        List<JournalEntryLine> lines = new java.util.ArrayList<>();
        int seq = 10;
        BigDecimal total = BigDecimal.ZERO;
        for (var e : debitsByAccount.entrySet()) {
            GlAccount account = glAccounts.byCode(e.getKey());
            lines.add(JournalEntryLine.debit(seq, account.id(), account.code(), account.name(),
                e.getValue(), debitDescription, postingDate));
            seq += 10;
            total = total.add(e.getValue());
        }
        GlAccount creditAccount = glAccounts.byCode(creditAccountCode);
        lines.add(JournalEntryLine.credit(seq, creditAccount.id(), creditAccount.code(), creditAccount.name(),
            total, creditDescription, postingDate));

        JournalEntry entry = JournalEntry.post(
            journalNumber, postingDate,
            sourceModule, sourceDocumentType, sourceDocumentId,
            description, Currencies.orBase(currencyCode), BigDecimal.ONE,
            lines
        );
        journalEntries.save(entry);

        log.info("posted journal {} ({}) Dr [{}] Cr {} {} {}",
            journalNumber, sourceDocumentType, debitsByAccount.keySet(),
            creditAccount.code(), total, currencyCode);
    }

    /**
     * Multi-debit / multi-credit journal helper. Used by the per-class
     * shipment posting (one Dr COGS / Cr Inventory pair per valuation class
     * in a single balanced journal).
     */
    private void postMultiDebitMultiCredit(
        String journalNumber,
        LocalDate postingDate,
        JournalEntry.SourceModule sourceModule,
        JournalEntry.SourceDocumentType sourceDocumentType,
        UUID sourceDocumentId,
        String description,
        String currencyCode,
        java.util.Map<String, BigDecimal> debitsByAccount,
        String debitDescription,
        java.util.Map<String, BigDecimal> creditsByAccount,
        String creditDescription
    ) {
        List<JournalEntryLine> lines = new java.util.ArrayList<>();
        int seq = 10;
        for (var e : debitsByAccount.entrySet()) {
            GlAccount account = glAccounts.byCode(e.getKey());
            lines.add(JournalEntryLine.debit(seq, account.id(), account.code(), account.name(),
                e.getValue(), debitDescription, postingDate));
            seq += 10;
        }
        for (var e : creditsByAccount.entrySet()) {
            GlAccount account = glAccounts.byCode(e.getKey());
            lines.add(JournalEntryLine.credit(seq, account.id(), account.code(), account.name(),
                e.getValue(), creditDescription, postingDate));
            seq += 10;
        }

        JournalEntry entry = JournalEntry.post(
            journalNumber, postingDate,
            sourceModule, sourceDocumentType, sourceDocumentId,
            description, Currencies.orBase(currencyCode), BigDecimal.ONE,
            lines
        );
        journalEntries.save(entry);

        log.info("posted journal {} ({}) Dr [{}] Cr [{}] {}",
            journalNumber, sourceDocumentType,
            debitsByAccount.keySet(), creditsByAccount.keySet(), currencyCode);
    }

    private static String journalSuffix() {
        return UUID.randomUUID().toString().substring(0, JournalEntry.NUMBER_SUFFIX_LENGTH).toUpperCase();
    }

}
