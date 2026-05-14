package com.northwood.finance.domain;

import java.util.UUID;

public record JournalEntryId(UUID value) {

    public static JournalEntryId newId() {
        return new JournalEntryId(UUID.randomUUID());
    }

    public static JournalEntryId of(UUID value) {
        return new JournalEntryId(value);
    }
}
