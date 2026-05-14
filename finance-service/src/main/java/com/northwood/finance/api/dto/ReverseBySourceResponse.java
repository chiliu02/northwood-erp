package com.northwood.finance.api.dto;

import java.util.List;
import java.util.UUID;

public record ReverseBySourceResponse(
    int reversedCount,
    List<UUID> reversalEntryIds
) {}
