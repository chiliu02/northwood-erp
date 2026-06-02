package com.northwood.finance.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Bulk reversal request. Reverses every posted journal entry whose
 * {@code (source_document_type, source_document_id)} matches.
 */
public record ReverseBySourceRequest(
    @NotBlank String sourceDocumentType,
    @NotNull UUID sourceDocumentId,
    @Size(max = 500) String reason,
    LocalDate postingDate
) {}
