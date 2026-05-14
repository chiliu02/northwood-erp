package com.northwood.finance.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * CQRS read port for the journal-entries list view. Returns header rows
 * decorated with a per-row line-sum (computed in SQL — debits and credits
 * always balance, so we expose the debit-side total as the row total).
 *
 * <p>Separate from {@link com.northwood.finance.domain.JournalEntryRepository}
 * because the list view aggregates across the lines table without
 * reconstituting the full {@code JournalEntry} aggregate per row, and the
 * controller-side use case is a CQRS read, not aggregate orchestration.
 */
public interface JournalEntrySummaryQueryPort {

    /**
     * Most recent {@code limit} journal entries (DESC by {@code posted_at}
     * with {@code created_at} as a tiebreaker for draft rows that haven't
     * posted yet). Optional filter on {@code source_document_type} so the
     * UI can scope to e.g. all journals from supplier-invoice approvals.
     */
    List<JournalEntrySummary> findRecent(int limit, Optional<String> sourceDocumentType);

    record JournalEntrySummary(
        UUID journalEntryHeaderId,
        String journalNumber,
        LocalDate postingDate,
        String sourceModule,
        String sourceDocumentType,
        UUID sourceDocumentId,
        String description,
        String status,
        String currencyCode,
        BigDecimal totalAmount,
        int lineCount,
        Instant postedAt
    ) {}
}
