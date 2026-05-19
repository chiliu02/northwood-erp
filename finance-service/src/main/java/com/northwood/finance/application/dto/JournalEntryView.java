package com.northwood.finance.application.dto;

import com.northwood.finance.domain.JournalEntry;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Read-side projection of {@link JournalEntry} for the wire layer. */
public record JournalEntryView(
    UUID journalEntryHeaderId,
    String journalNumber,
    LocalDate postingDate,
    String sourceModule,
    String sourceDocumentType,
    UUID sourceDocumentId,
    String description,
    String status,
    String currencyCode,
    BigDecimal exchangeRate,
    Instant exchangeRateCapturedAt,
    List<JournalEntryLineView> lines,
    long version
) {
    public static JournalEntryView from(JournalEntry entry) {
        return new JournalEntryView(
            entry.id().value(),
            entry.journalNumber(),
            entry.postingDate(),
            entry.sourceModule().dbValue(),
            entry.sourceDocumentType().dbValue(),
            entry.sourceDocumentId(),
            entry.description(),
            entry.status().dbValue(),
            entry.currencyCode(),
            entry.exchangeRate(),
            entry.exchangeRateCapturedAt(),
            entry.lines().stream().map(JournalEntryLineView::from).toList(),
            entry.version()
        );
    }
}
