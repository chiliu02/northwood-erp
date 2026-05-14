package com.northwood.finance.application.dto;

import com.northwood.finance.domain.JournalEntryLine;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Read-side projection of {@link JournalEntryLine} for the wire layer. */
public record JournalEntryLineView(
    UUID lineId,
    int lineNumber,
    UUID glAccountId,
    String accountCode,
    String accountName,
    BigDecimal debitAmount,
    BigDecimal creditAmount,
    String description,
    LocalDate postingDate
) {
    public static JournalEntryLineView from(JournalEntryLine l) {
        return new JournalEntryLineView(
            l.id(), l.lineNumber(),
            l.glAccountId(), l.accountCode(), l.accountName(),
            l.debitAmount(), l.creditAmount(),
            l.description(), l.postingDate()
        );
    }
}
