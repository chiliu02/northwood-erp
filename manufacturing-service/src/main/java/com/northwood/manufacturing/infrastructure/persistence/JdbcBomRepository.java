package com.northwood.manufacturing.infrastructure.persistence;

import com.northwood.manufacturing.domain.Bom;
import com.northwood.manufacturing.domain.BomId;
import com.northwood.manufacturing.domain.BomLine;
import com.northwood.manufacturing.domain.BomLineId;
import com.northwood.manufacturing.domain.BomRepository;
import com.northwood.shared.application.messaging.OutboxTraceHeaders;
import com.northwood.shared.application.security.CurrentUserAccessor;
import com.northwood.shared.domain.DomainEvent;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcBomRepository implements BomRepository {

    private static final RowMapper<BomLine> LINE_ROW_MAPPER = (rs, n) -> new BomLine(
        BomLineId.of(rs.getObject("bom_line_id", UUID.class)),
        rs.getInt("line_number"),
        rs.getObject("component_product_id", UUID.class),
        rs.getString("component_sku"),
        rs.getString("component_name"),
        Bom.ComponentKind.fromDb(rs.getString("component_kind")),
        rs.getBigDecimal("quantity_per_finished_unit"),
        rs.getBigDecimal("scrap_factor_percent")
    );

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final CurrentUserAccessor currentUser;

    public JdbcBomRepository(JdbcTemplate jdbc, ObjectMapper json, CurrentUserAccessor currentUser) {
        this.jdbc = jdbc;
        this.json = json;
        this.currentUser = currentUser;
    }

    @Override
    public Optional<Bom> findById(BomId id) {
        Optional<HeaderRow> header;
        try {
            header = Optional.ofNullable(jdbc.queryForObject(
                """
                SELECT bom_header_id, finished_product_id, finished_product_sku, finished_product_name,
                       version, status, row_version
                  FROM manufacturing.bom_header
                 WHERE bom_header_id = ?
                """,
                (rs, n) -> new HeaderRow(
                    rs.getObject("bom_header_id", UUID.class),
                    rs.getObject("finished_product_id", UUID.class),
                    rs.getString("finished_product_sku"),
                    rs.getString("finished_product_name"),
                    rs.getString("version"),
                    rs.getString("status"),
                    rs.getLong("row_version")
                ),
                id.value()
            ));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
        if (header.isEmpty()) return Optional.empty();
        HeaderRow h = header.get();
        List<BomLine> lines = jdbc.query(
            """
            SELECT bom_line_id, line_number, component_product_id, component_sku, component_name,
                   component_kind, quantity_per_finished_unit, scrap_factor_percent
              FROM manufacturing.bom_line
             WHERE bom_header_id = ?
             ORDER BY line_number
            """,
            LINE_ROW_MAPPER, h.id
        );
        return Optional.of(Bom.reconstitute(
            BomId.of(h.id),
            h.finishedProductId,
            h.finishedProductSku,
            h.finishedProductName,
            h.version,
            Bom.Status.fromDb(h.status),
            lines,
            h.rowVersion
        ));
    }

    @Override
    public Optional<BomId> findBomIdByLineId(BomLineId bomLineId) {
        try {
            UUID headerId = jdbc.queryForObject(
                "SELECT bom_header_id FROM manufacturing.bom_line WHERE bom_line_id = ?",
                UUID.class, bomLineId.value()
            );
            return Optional.ofNullable(headerId).map(BomId::of);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public void save(Bom bom) {
        String actor = currentUser.currentUsername().orElse(null);
        if (bom.aggregateVersion() == 0L) {
            insertHeader(bom, actor);
        } else {
            updateHeader(bom, actor);
        }
        // DELETEs first so any add-then-remove sequence within the same draft
        // session (which can't happen today because removeLine prunes from
        // addedLines first, but kept for symmetry) is safe.
        List<BomLineId> removed = bom.pullRemovedLineIds();
        for (BomLineId lineId : removed) {
            jdbc.update("DELETE FROM manufacturing.bom_line WHERE bom_line_id = ?", lineId.value());
        }
        List<BomLine> added = bom.pullAddedLines();
        for (BomLine line : added) {
            insertLine(bom.id(), line);
        }
        for (DomainEvent event : bom.pullPendingEvents()) {
            writeOutbox(event, actor);
        }
    }

    private void insertHeader(Bom bom, String actor) {
        jdbc.update("""
            INSERT INTO manufacturing.bom_header (
                bom_header_id, finished_product_id,
                finished_product_sku, finished_product_name,
                version, status, row_version,
                created_by, last_modified_by
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            bom.id().value(),
            bom.finishedProductId(),
            bom.finishedProductSku(),
            bom.finishedProductName(),
            bom.version(),
            bom.status().dbValue(),
            // Persist with row_version=1 so a subsequent reload + mutate + save
            // routes to UPDATE. The aggregate's in-memory 0 sentinel means
            // "not yet persisted"; the row never sits at row_version=0.
            1L,
            actor, actor
        );
    }

    private void updateHeader(Bom bom, String actor) {
        // The partial unique index uq_bom_active_per_product enforces
        // "at most one active per finished_product_id"; a competing active
        // surfaces as a DataIntegrityViolationException, which propagates
        // and rolls back the surrounding @Transactional.
        int rows = jdbc.update("""
            UPDATE manufacturing.bom_header
               SET status = ?, row_version = row_version + 1, last_modified_by = ?
             WHERE bom_header_id = ? AND row_version = ?
            """,
            bom.status().dbValue(),
            actor,
            bom.id().value(),
            bom.aggregateVersion()
        );
        if (rows == 0) {
            throw new OptimisticLockingFailureException(
                "Bom " + bom.id().value() + " was modified concurrently (expected row_version "
                    + bom.aggregateVersion() + ")"
            );
        }
    }

    private void insertLine(BomId bomId, BomLine line) {
        jdbc.update("""
            INSERT INTO manufacturing.bom_line (
                bom_line_id, bom_header_id, line_number,
                component_product_id, component_sku, component_name,
                component_kind, quantity_per_finished_unit, scrap_factor_percent
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            line.id().value(),
            bomId.value(),
            line.lineNumber(),
            line.componentProductId(),
            line.componentSku(),
            line.componentName(),
            line.componentKind().dbValue(),
            line.quantityPerFinishedUnit(),
            line.scrapFactorPercent() == null ? BigDecimal.ZERO : line.scrapFactorPercent()
        );
    }

    private void writeOutbox(DomainEvent event, String actor) {
        try {
            jdbc.update("""
                INSERT INTO manufacturing.outbox_message (
                    outbox_message_id, aggregate_type, aggregate_id,
                    event_type, event_version, payload, headers, status, actor_user_id
                ) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, 'pending', ?)
                """,
                event.eventId(),
                Bom.AGGREGATE_TYPE,
                event.aggregateId(),
                event.eventType(),
                event.eventVersion(),
                json.writeValueAsString(event), OutboxTraceHeaders.currentJson(),
                actor
            );
        } catch (JacksonException e) {
            throw new IllegalStateException("Cannot serialise " + event.eventType(), e);
        }
    }

    private record HeaderRow(
        UUID id,
        UUID finishedProductId,
        String finishedProductSku,
        String finishedProductName,
        String version,
        String status,
        long rowVersion
    ) {}
}
