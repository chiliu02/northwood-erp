package com.northwood.manufacturing.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Port for editorial mutations against {@code manufacturing.bom_header} and
 * {@code manufacturing.bom_line}. The edit-time representation isn't a single
 * aggregate today (BOMs are read via the {@code BomLookup} port for active-only access);
 * this port carries the line-level read+write API that {@code BomEditService}
 * needs to keep the BOM graph acyclic across draft → activate transitions.
 *
 * <p>JDBC implementation lives in
 * {@code infrastructure/persistence/JdbcBomEditRepository}.
 */
public interface BomEditRepository {

    /** Insert a new BOM header in {@code 'draft'} status. */
    void insertHeader(
        UUID bomHeaderId,
        UUID finishedProductId,
        String finishedProductSku,
        String finishedProductName,
        String version);

    /** Read header identity + status. Empty when no row exists. */
    Optional<HeaderRow> findHeader(UUID bomHeaderId);

    /** Next {@code line_number} for a new line on this BOM. */
    int nextLineNumber(UUID bomHeaderId);

    /** Insert a new BOM line. */
    void insertLine(
        UUID bomLineId,
        UUID bomHeaderId,
        int lineNumber,
        UUID componentProductId,
        String componentSku,
        String componentName,
        String componentKind,
        BigDecimal quantityPerFinishedUnit,
        BigDecimal scrapFactorPercent);

    /** Find the header id this line belongs to. Empty when no such line exists. */
    Optional<UUID> findHeaderIdByLineId(UUID bomLineId);

    /** Delete a line by id. Returns true when a row was deleted, false otherwise. */
    boolean deleteLine(UUID bomLineId);

    /** Number of lines on a header. */
    int countLines(UUID bomHeaderId);

    /**
     * Flip the header status to {@code 'active'}. The partial unique index
     * {@code uq_bom_active_per_product} enforces "at most one active per
     * finished_product_id" — a competing active surfaces as a
     * {@code DataIntegrityViolationException} which rolls back the
     * transaction cleanly.
     */
    void markActive(UUID bomHeaderId);

    /** All component product ids on a header (for cycle-detection during activate). */
    List<UUID> findComponentProductIds(UUID bomHeaderId);

    record HeaderRow(UUID id, UUID finishedProductId, String finishedProductSku, String status) {}
}
