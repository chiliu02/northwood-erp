package com.northwood.finance.domain;

import com.northwood.shared.domain.Assert;
import com.northwood.shared.domain.Currencies;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Aggregate root for a journal entry: header + balanced debit/credit lines.
 * Phase 5b posts entries directly at status {@code 'posted'} from each AP/AR
 * service path; manual journal-entry creation lands in a future slice.
 *
 * <p>The application layer is responsible for constructing balanced lines.
 * The DB-level deferred trigger {@code finance.enforce_journal_balance}
 * verifies debit-sum == credit-sum at COMMIT and rolls back if not — so a
 * mistake in the application code surfaces as an error at txn close, not as
 * a silent imbalance.
 */
public final class JournalEntry {

    /**
     * Wire-format aggregate-type stamped onto {@code finance.outbox_message.aggregate_type}
     * for events about this aggregate. JournalEntry is an event-less write-once aggregate
     * (see the {@code *Repository} rule in {@code docs/conventions.md}); the constant exists
     * because the aggregate root's identity remains the anchor for audit-log + reporting
     * references, even when no outbox write currently references it.
     */
    public static final String AGGREGATE_TYPE = FinanceAggregateTypes.JOURNAL_ENTRY;

    /**
     * Human-readable number prefix for new journal entries; stamped by
     * {@code JournalEntryService.post*} sites. Pure formatting choice — no
     * consumer dispatches on this value, but surfacing it through a constant
     * (parallel to other aggregates' {@code NUMBER_PREFIX}) keeps the prefix
     * scheme rename-safe.
     */
    public static final String NUMBER_PREFIX = "JE-";

    /**
     * Reversal-entry variant of {@link #NUMBER_PREFIX}, stamped by
     * {@link #reverseOf}. Distinct prefix so reversal numbers are visually
     * separable from original postings in audit lists.
     */
    public static final String REVERSAL_NUMBER_PREFIX = "JE-REV-";

    /**
     * Character count of the random suffix appended to {@link #NUMBER_PREFIX} /
     * {@link #REVERSAL_NUMBER_PREFIX} when constructing a new journal number
     * (a {@code UUID.randomUUID().toString().substring(0, …).toUpperCase()}
     * slice). Pairs with the prefix constants — together they define the full
     * number format.
     */
    public static final int NUMBER_SUFFIX_LENGTH = 8;

    /**
     * Source-module classifier. Mirrors the schema CHECK on
     * {@code finance.journal_entry_header.source_module}. Identifies which
     * service originated the document being journalled.
     */
    public enum SourceModule {
        SALES("sales"),
        INVENTORY("inventory"),
        MANUFACTURING("manufacturing"),
        PURCHASING("purchasing"),
        FINANCE("finance");

        private final String dbValue;

        SourceModule(String dbValue) {
            this.dbValue = dbValue;
        }

        public String dbValue() {
            return dbValue;
        }

        public static SourceModule fromDb(String value) {
            for (SourceModule m : values()) {
                if (m.dbValue.equals(value)) return m;
            }
            throw Assert.unknownValue("journal_entry source_module", value);
        }
    }

    /**
     * Source-document classifier. The {@code source_document_type} column is
     * free-form text in the schema (no CHECK), but in practice carries one of
     * these wire-format values — each identifies the kind of business event
     * the journal was posted for. Used by {@link #reverseOf(JournalEntry, String, java.time.LocalDate)}
     * to mark reversal entries, and by reverse-by-source flows to find all
     * journals posted from a given upstream document.
     */
    public enum SourceDocumentType {
        SUPPLIER_INVOICE("supplier_invoice"),
        CUSTOMER_INVOICE("customer_invoice"),
        SUPPLIER_PAYMENT("supplier_payment"),
        CUSTOMER_PAYMENT("customer_payment"),
        GOODS_RECEIPT("goods_receipt"),
        SHIPMENT_COST("shipment_cost"),
        JOURNAL_REVERSAL("journal_reversal");

        private final String dbValue;

        SourceDocumentType(String dbValue) {
            this.dbValue = dbValue;
        }

        public String dbValue() {
            return dbValue;
        }

        public static SourceDocumentType fromDb(String value) {
            for (SourceDocumentType t : values()) {
                if (t.dbValue.equals(value)) return t;
            }
            throw Assert.unknownValue("journal_entry source_document_type", value);
        }
    }

    /**
     * Journal-entry lifecycle status. Mirrors the schema CHECK on
     * {@code finance.journal_entry_header.status}. Lifecycle:
     * {@code DRAFT → POSTED → REVERSED}. {@code DRAFT} is load-bearing for
     * the two-phase save pattern in {@code JdbcJournalEntryRepository} —
     * the {@code guard_journal_line_immutability} DB trigger rejects line
     * INSERTs once the header is {@code POSTED}, so the repository inserts
     * the header at {@code DRAFT} first, INSERTs lines, then UPDATEs to
     * {@code POSTED}.
     */
    public enum Status {
        DRAFT("draft"),
        POSTED("posted"),
        REVERSED("reversed");

        private final String dbValue;

        Status(String dbValue) {
            this.dbValue = dbValue;
        }

        public String dbValue() {
            return dbValue;
        }

        public static Status fromDb(String value) {
            for (Status s : values()) {
                if (s.dbValue.equals(value)) return s;
            }
            throw Assert.unknownValue("journal_entry status", value);
        }
    }

    private final JournalEntryId id;
    private final String journalNumber;
    private final LocalDate postingDate;
    private final SourceModule sourceModule;
    private final SourceDocumentType sourceDocumentType;
    private final UUID sourceDocumentId;
    private final String description;
    private final Status status;
    private final String currencyCode;
    private final BigDecimal exchangeRate;
    private final Instant exchangeRateCapturedAt;
    private final List<JournalEntryLine> lines;
    private final long version;

    public static JournalEntry post(
        String journalNumber,
        LocalDate postingDate,
        SourceModule sourceModule,
        SourceDocumentType sourceDocumentType,
        UUID sourceDocumentId,
        String description,
        String currencyCode,
        BigDecimal exchangeRate,
        List<JournalEntryLine> lines
    ) {
        Assert.notNull(sourceDocumentId, "sourceDocumentId");
        Assert.argument(lines != null && lines.size() >= 2, "journal entry must have at least 2 lines (one debit, one credit)");
        BigDecimal debits = BigDecimal.ZERO;
        BigDecimal credits = BigDecimal.ZERO;
        for (JournalEntryLine l : lines) {
            debits = debits.add(l.debitAmount());
            credits = credits.add(l.creditAmount());
        }
        if (debits.setScale(2, RoundingMode.HALF_UP).compareTo(credits.setScale(2, RoundingMode.HALF_UP)) != 0) {
            throw new IllegalArgumentException(
                "journal entry unbalanced: debits=" + debits + " credits=" + credits
            );
        }
        return new JournalEntry(
            JournalEntryId.newId(), journalNumber,
            postingDate == null ? LocalDate.now() : postingDate,
            sourceModule, sourceDocumentType, sourceDocumentId,
            description,
            Status.POSTED,
            Currencies.orBase(currencyCode),
            exchangeRate == null ? BigDecimal.ONE : exchangeRate,
            Instant.now(),
            new ArrayList<>(lines), 0L
        );
    }

    /**
     * Factory: build a reversal of an existing posted journal entry. The
     * reversal is itself a new posted entry whose lines are the original's
     * lines with debit and credit amounts swapped. The original stays
     * posted (per the schema's immutability trigger); a follow-up call to
     * {@code repository.markReversed(original.id())} flips its status to
     * 'reversed'.
     *
     * <p>Linkage to the original is via {@code source_document_type =
     * 'journal_reversal'} and {@code source_document_id = original.id()},
     * so an audit query can walk from a reversal back to its target.
     */
    public static JournalEntry reverseOf(
        JournalEntry original,
        String reason,
        LocalDate reversalPostingDate
    ) {
        Assert.notNull(original, "original");
        Assert.state(original.status == Status.POSTED, "Can only reverse a posted journal entry; original " + original.id().value()
                    + " is in status=" + original.status.dbValue());
        LocalDate postingDate = reversalPostingDate == null ? LocalDate.now() : reversalPostingDate;
        List<JournalEntryLine> reversedLines = new ArrayList<>();
        int seq = 10;
        for (JournalEntryLine src : original.lines) {
            // Swap debit ↔ credit. Either debit or credit is non-zero per
            // schema CHECK; the swap therefore produces a still-valid line.
            reversedLines.add(new JournalEntryLine(
                UUID.randomUUID(),
                seq,
                src.glAccountId(),
                src.accountCode(),
                src.accountName(),
                src.creditAmount(),                              // was credit, now debit
                src.debitAmount(),                               // was debit, now credit
                "Reversal: " + (src.description() == null ? "" : src.description()),
                postingDate
            ));
            seq += 10;
        }
        String description = "Reversal of " + original.journalNumber
            + (reason == null || reason.isBlank() ? "" : " — " + reason);
        return post(
            REVERSAL_NUMBER_PREFIX + UUID.randomUUID().toString().substring(0, NUMBER_SUFFIX_LENGTH).toUpperCase(),
            postingDate,
            SourceModule.FINANCE,
            SourceDocumentType.JOURNAL_REVERSAL,
            original.id().value(),
            description,
            original.currencyCode,
            original.exchangeRate,
            reversedLines
        );
    }

    public static JournalEntry reconstitute(
        JournalEntryId id, String journalNumber, LocalDate postingDate,
        SourceModule sourceModule, SourceDocumentType sourceDocumentType, UUID sourceDocumentId,
        String description, Status status,
        String currencyCode, BigDecimal exchangeRate, Instant exchangeRateCapturedAt,
        List<JournalEntryLine> lines, long version
    ) {
        return new JournalEntry(
            id, journalNumber, postingDate,
            sourceModule, sourceDocumentType, sourceDocumentId,
            description, status,
            currencyCode, exchangeRate, exchangeRateCapturedAt,
            new ArrayList<>(lines), version
        );
    }

    private JournalEntry(
        JournalEntryId id, String journalNumber, LocalDate postingDate,
        SourceModule sourceModule, SourceDocumentType sourceDocumentType, UUID sourceDocumentId,
        String description, Status status,
        String currencyCode, BigDecimal exchangeRate, Instant exchangeRateCapturedAt,
        List<JournalEntryLine> lines, long version
    ) {
        this.id = id;
        this.journalNumber = journalNumber;
        this.postingDate = postingDate;
        this.sourceModule = sourceModule;
        this.sourceDocumentType = sourceDocumentType;
        this.sourceDocumentId = sourceDocumentId;
        this.description = description;
        this.status = status;
        this.currencyCode = currencyCode;
        this.exchangeRate = exchangeRate;
        this.exchangeRateCapturedAt = exchangeRateCapturedAt;
        this.lines = lines;
        this.version = version;
    }

    public JournalEntryId id()                     { return id; }
    public String journalNumber()                  { return journalNumber; }
    public LocalDate postingDate()                 { return postingDate; }
    public SourceModule sourceModule()             { return sourceModule; }
    public SourceDocumentType sourceDocumentType() { return sourceDocumentType; }
    public UUID sourceDocumentId()                 { return sourceDocumentId; }
    public String description()                    { return description; }
    public Status status()                         { return status; }
    public String currencyCode()                   { return currencyCode; }
    public BigDecimal exchangeRate()               { return exchangeRate; }
    public Instant exchangeRateCapturedAt()        { return exchangeRateCapturedAt; }
    public List<JournalEntryLine> lines()          { return List.copyOf(lines); }
    public long version()                          { return version; }
}
