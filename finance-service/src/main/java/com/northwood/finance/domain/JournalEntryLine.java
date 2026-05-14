package com.northwood.finance.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Value object: one line on a journal entry. Each line is either a debit
 * OR a credit (schema CHECK enforces non-negative + mutually-exclusive
 * non-zero). The DB-level deferred trigger
 * {@code finance.enforce_journal_balance} verifies debit-sum equals
 * credit-sum at posting time.
 */
public record JournalEntryLine(
    UUID id,
    int lineNumber,
    UUID glAccountId,
    String accountCode,
    String accountName,
    BigDecimal debitAmount,
    BigDecimal creditAmount,
    String description,
    LocalDate postingDate
) {

    public static JournalEntryLine debit(
        int lineNumber,
        UUID glAccountId,
        String accountCode,
        String accountName,
        BigDecimal amount,
        String description,
        LocalDate postingDate
    ) {
        return new JournalEntryLine(
            UUID.randomUUID(), lineNumber,
            glAccountId, accountCode, accountName,
            amount, BigDecimal.ZERO, description, postingDate
        );
    }

    public static JournalEntryLine credit(
        int lineNumber,
        UUID glAccountId,
        String accountCode,
        String accountName,
        BigDecimal amount,
        String description,
        LocalDate postingDate
    ) {
        return new JournalEntryLine(
            UUID.randomUUID(), lineNumber,
            glAccountId, accountCode, accountName,
            BigDecimal.ZERO, amount, description, postingDate
        );
    }
}
