package com.northwood.finance.api.dto;

import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record ReverseJournalEntryRequest(
    @Size(max = 500) String reason,
    LocalDate postingDate
) {}
