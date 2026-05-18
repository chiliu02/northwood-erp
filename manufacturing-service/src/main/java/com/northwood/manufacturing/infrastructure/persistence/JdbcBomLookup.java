package com.northwood.manufacturing.infrastructure.persistence;

import com.northwood.manufacturing.application.BomLookup;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcBomLookup implements BomLookup {

    private final JdbcTemplate jdbc;

    public JdbcBomLookup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ActiveBom> findActiveByFinishedProductId(UUID finishedProductId) {
        // Shape A: consult the active_bom_header_id column on
        // manufacturing.product_card (maintained by ActiveBomChangedHandler
        // from product master) first. Fall back to bom_header.status='active'
        // when no projection row exists — covers BOMs activated locally
        // before the projection was populated. The fallback path becomes
        // dead in practice once the baseline seed lands; it stays as a
        // defensive safety net during the migration window.
        UUID bomHeaderId = null;
        try {
            bomHeaderId = jdbc.queryForObject(
                """
                SELECT active_bom_header_id FROM manufacturing.product_card
                WHERE product_id = ? AND active_bom_header_id IS NOT NULL
                """,
                UUID.class, finishedProductId
            );
        } catch (EmptyResultDataAccessException e) {
            // No projection row — fall through to legacy lookup below.
        }
        if (bomHeaderId == null) {
            try {
                bomHeaderId = jdbc.queryForObject(
                    """
                    SELECT bom_header_id FROM manufacturing.bom_header
                    WHERE finished_product_id = ? AND status = 'active'
                    """,
                    UUID.class, finishedProductId
                );
            } catch (EmptyResultDataAccessException e) {
                return Optional.empty();
            }
        }
        if (bomHeaderId == null) {
            return Optional.empty();
        }
        List<Component> components = jdbc.query(
            """
            SELECT component_product_id, component_sku, component_name,
                   quantity_per_finished_unit, scrap_factor_percent, component_kind
            FROM manufacturing.bom_line
            WHERE bom_header_id = ? ORDER BY line_number
            """,
            (rs, n) -> new Component(
                rs.getObject("component_product_id", UUID.class),
                rs.getString("component_sku"),
                rs.getString("component_name"),
                rs.getBigDecimal("quantity_per_finished_unit"),
                rs.getBigDecimal("scrap_factor_percent"),
                rs.getString("component_kind")
            ),
            bomHeaderId
        );
        return Optional.of(new ActiveBom(bomHeaderId, components));
    }

    @Override
    public Optional<BomHeaderIdentity> findActiveBomIdentity(UUID finishedProductId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                """
                SELECT finished_product_sku, finished_product_name
                FROM manufacturing.bom_header
                WHERE finished_product_id = ? AND status = 'active'
                LIMIT 1
                """,
                (rs, n) -> new BomHeaderIdentity(
                    rs.getString("finished_product_sku"),
                    rs.getString("finished_product_name")
                ),
                finishedProductId
            ));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<UUID> findParentProductIdsByComponent(UUID componentProductId) {
        // Use bom_header.status='active' as the runtime authority — it's
        // always set for active BoMs (whether activated in-service via
        // BomService.activate, or projected from product.ActiveBomChanged
        // via ActiveBomChangedHandler). The product_card.active_bom_header_id
        // column co-exists but isn't always in sync (BomService doesn't
        // write to it); falling back to status='active' is the same approach
        // findActiveByFinishedProductId uses.
        return jdbc.query(
            """
            SELECT DISTINCT bh.finished_product_id
            FROM manufacturing.bom_header bh
            JOIN manufacturing.bom_line bl ON bl.bom_header_id = bh.bom_header_id
            WHERE bh.status = 'active'
              AND bl.component_product_id = ?
            """,
            (rs, n) -> rs.getObject("finished_product_id", UUID.class),
            componentProductId
        );
    }
}
