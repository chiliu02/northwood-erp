package com.northwood.finance.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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

    /** Status — wire-format string stored in finance.journal_entry_header.status. */
    public static final String POSTED = "posted";


    private final JournalEntryId id;
    private final String journalNumber;
    private final LocalDate postingDate;
    private final String sourceModule;
    private final String sourceDocumentType;
    private final UUID sourceDocumentId;
    private final String description;
    private final String status;
    private final String currencyCode;
    private final BigDecimal exchangeRate;
    private final Instant exchangeRateCapturedAt;
    private final List<JournalEntryLine> lines;
    private final long version;

    public static JournalEntry post(
        String journalNumber,
        LocalDate postingDate,
        String sourceModule,
        String sourceDocumentType,
        UUID sourceDocumentId,
        String description,
        String currencyCode,
        BigDecimal exchangeRate,
        List<JournalEntryLine> lines
    ) {
        Objects.requireNonNull(sourceDocumentId, "sourceDocumentId");
        if (lines == null || lines.size() < 2) {
            throw new IllegalArgumentException("journal entry must have at least 2 lines (one debit, one credit)");
        }
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
            "posted",
            currencyCode == null ? "AUD" : currencyCode,
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
        Objects.requireNonNull(original, "original");
        if (!POSTED.equals(original.status)) {
            throw new IllegalStateException(
                "Can only reverse a posted journal entry; original " + original.id().value()
                    + " is in status=" + original.status
            );
        }
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
            "JE-REV-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
            postingDate,
            "finance",
            "journal_reversal",
            original.id().value(),
            description,
            original.currencyCode,
            original.exchangeRate,
            reversedLines
        );
    }

    public static JournalEntry reconstitute(
        JournalEntryId id, String journalNumber, LocalDate postingDate,
        String sourceModule, String sourceDocumentType, UUID sourceDocumentId,
        String description, String status,
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
        String sourceModule, String sourceDocumentType, UUID sourceDocumentId,
        String description, String status,
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
    public String sourceModule()                   { return sourceModule; }
    public String sourceDocumentType()             { return sourceDocumentType; }
    public UUID sourceDocumentId()                 { return sourceDocumentId; }
    public String description()                    { return description; }
    public String status()                         { return status; }
    public String currencyCode()                   { return currencyCode; }
    public BigDecimal exchangeRate()               { return exchangeRate; }
    public Instant exchangeRateCapturedAt()        { return exchangeRateCapturedAt; }
    public List<JournalEntryLine> lines()          { return List.copyOf(lines); }
    public long version()                          { return version; }
}
