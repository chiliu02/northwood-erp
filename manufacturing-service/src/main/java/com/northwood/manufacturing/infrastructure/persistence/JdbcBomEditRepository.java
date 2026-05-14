package com.northwood.manufacturing.infrastructure.persistence;

import com.northwood.manufacturing.domain.BomEditRepository;
import com.northwood.shared.application.security.CurrentUserAccessor;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcBomEditRepository implements BomEditRepository {

    private static final RowMapper<HeaderRow> HEADER_MAPPER = (rs, n) -> new HeaderRow(
        rs.getObject("bom_header_id", UUID.class),
        rs.getObject("finished_product_id", UUID.class),
        rs.getString("finished_product_sku"),
        rs.getString("status")
    );

    private final JdbcTemplate jdbc;
    private final CurrentUserAccessor currentUser;

    public JdbcBomEditRepository(JdbcTemplate jdbc, CurrentUserAccessor currentUser) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
    }

    @Override
    public void insertHeader(
        UUID bomHeaderId,
        UUID finishedProductId,
        String finishedProductSku,
        String finishedProductName,
        String version
    ) {
        String actor = currentUser.currentUsername().orElse(null);
        jdbc.update("""
            INSERT INTO manufacturing.bom_header (
                bom_header_id, finished_product_id,
                finished_product_sku, finished_product_name,
                version, status, row_version,
                created_by, last_modified_by
            ) VALUES (?, ?, ?, ?, ?, 'draft', 0, ?, ?)
            """,
            bomHeaderId, finishedProductId,
            finishedProductSku, finishedProductName,
            version,
            actor, actor
        );
    }

    @Override
    public Optional<HeaderRow> findHeader(UUID bomHeaderId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                """
                SELECT bom_header_id, finished_product_id, finished_product_sku, status
                FROM manufacturing.bom_header WHERE bom_header_id = ?
                """,
                HEADER_MAPPER, bomHeaderId
            ));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public int nextLineNumber(UUID bomHeaderId) {
        Integer next = jdbc.queryForObject(
            "SELECT COALESCE(MAX(line_number), 0) + 1 FROM manufacturing.bom_line WHERE bom_header_id = ?",
            Integer.class, bomHeaderId
        );
        return next == null ? 1 : next;
    }

    @Override
    public void insertLine(
        UUID bomLineId,
        UUID bomHeaderId,
        int lineNumber,
        UUID componentProductId,
        String componentSku,
        String componentName,
        String componentKind,
        BigDecimal quantityPerFinishedUnit,
        BigDecimal scrapFactorPercent
    ) {
        jdbc.update("""
            INSERT INTO manufacturing.bom_line (
                bom_line_id, bom_header_id, line_number,
                component_product_id, component_sku, component_name,
                component_kind, quantity_per_finished_unit, scrap_factor_percent
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            bomLineId, bomHeaderId, lineNumber,
            componentProductId, componentSku, componentName,
            componentKind,
            quantityPerFinishedUnit,
            scrapFactorPercent == null ? BigDecimal.ZERO : scrapFactorPercent
        );
    }

    @Override
    public Optional<UUID> findHeaderIdByLineId(UUID bomLineId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                "SELECT bom_header_id FROM manufacturing.bom_line WHERE bom_line_id = ?",
                UUID.class, bomLineId
            ));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public boolean deleteLine(UUID bomLineId) {
        return jdbc.update("DELETE FROM manufacturing.bom_line WHERE bom_line_id = ?", bomLineId) > 0;
    }

    @Override
    public int countLines(UUID bomHeaderId) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM manufacturing.bom_line WHERE bom_header_id = ?",
            Integer.class, bomHeaderId
        );
        return count == null ? 0 : count;
    }

    @Override
    public void markActive(UUID bomHeaderId) {
        String actor = currentUser.currentUsername().orElse(null);
        jdbc.update(
            "UPDATE manufacturing.bom_header SET status = 'active', last_modified_by = ? WHERE bom_header_id = ?",
            actor, bomHeaderId
        );
    }

    @Override
    public List<UUID> findComponentProductIds(UUID bomHeaderId) {
        return jdbc.queryForList(
            "SELECT component_product_id FROM manufacturing.bom_line WHERE bom_header_id = ?",
            UUID.class, bomHeaderId
        );
    }

}
