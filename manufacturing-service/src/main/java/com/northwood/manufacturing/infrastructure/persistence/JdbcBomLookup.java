package com.northwood.manufacturing.infrastructure.persistence;

import com.northwood.manufacturing.application.BomLookup;
import com.northwood.manufacturing.domain.Bom;
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
                Bom.ComponentKind.fromDb(rs.getString("component_kind"))
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

    /**
     * Returns the entire active-BOM hierarchy in a single recursive
     * CTE — anchor row(s) are direct children of the root product's active
     * BOM, recursion descends via each component's own active BOM. Computes
     * cumulative quantity (running product of {@code quantity_per_finished_unit
     * × (1 + scrap_factor_percent/100)}) in SQL.
     *
     * <p>"Active" resolution uses {@code bom_header.status='active'} for both
     * the root anchor and each level of recursion — same fallback semantics as
     * {@link #findActiveByFinishedProductId}. The
     * {@code product_card.active_bom_header_id} column is the canonical
     * pointer, but it isn't populated by every code path today (e.g. the seed
     * baseline only sets it indirectly), and {@code bom_header.status} is
     * reliably populated for every activated BOM.
     *
     * <p>A 20-level depth cap guards against the (impossible-in-practice)
     * case of {@code BomCycleDetector} missing a path — Postgres would
     * otherwise loop forever on a true cycle.
     */
    @Override
    public List<ComponentTreeRow> findActiveBomTreeRows(UUID rootProductId) {
        return jdbc.query(
            """
            WITH RECURSIVE bom_walk AS (
                SELECT
                    1 AS depth,
                    bh.bom_header_id AS holder_bom_header_id,
                    bl.line_number,
                    bl.component_product_id,
                    bl.component_sku,
                    bl.component_name,
                    bl.component_kind,
                    bl.quantity_per_finished_unit,
                    bl.scrap_factor_percent,
                    child_bh.bom_header_id AS child_active_bom_header_id,
                    bl.quantity_per_finished_unit
                        * (1 + bl.scrap_factor_percent / 100) AS cumulative_qty
                FROM manufacturing.bom_header bh
                JOIN manufacturing.bom_line bl ON bl.bom_header_id = bh.bom_header_id
                LEFT JOIN manufacturing.bom_header child_bh
                  ON child_bh.finished_product_id = bl.component_product_id
                 AND child_bh.status = 'active'
                WHERE bh.finished_product_id = ?
                  AND bh.status = 'active'

                UNION ALL

                SELECT
                    w.depth + 1,
                    bh.bom_header_id,
                    bl.line_number,
                    bl.component_product_id,
                    bl.component_sku,
                    bl.component_name,
                    bl.component_kind,
                    bl.quantity_per_finished_unit,
                    bl.scrap_factor_percent,
                    child_bh.bom_header_id,
                    w.cumulative_qty
                        * bl.quantity_per_finished_unit
                        * (1 + bl.scrap_factor_percent / 100)
                FROM bom_walk w
                JOIN manufacturing.bom_header bh
                  ON bh.bom_header_id = w.child_active_bom_header_id
                JOIN manufacturing.bom_line bl
                  ON bl.bom_header_id = bh.bom_header_id
                LEFT JOIN manufacturing.bom_header child_bh
                  ON child_bh.finished_product_id = bl.component_product_id
                 AND child_bh.status = 'active'
                WHERE w.child_active_bom_header_id IS NOT NULL
                  AND w.depth < 20
            )
            SELECT depth, holder_bom_header_id, component_product_id, component_sku,
                   component_name, component_kind, quantity_per_finished_unit,
                   scrap_factor_percent, child_active_bom_header_id, cumulative_qty
              FROM bom_walk
             ORDER BY depth, holder_bom_header_id, line_number
            """,
            (rs, n) -> new ComponentTreeRow(
                rs.getInt("depth"),
                rs.getObject("holder_bom_header_id", UUID.class),
                rs.getObject("component_product_id", UUID.class),
                rs.getString("component_sku"),
                rs.getString("component_name"),
                Bom.ComponentKind.fromDb(rs.getString("component_kind")),
                rs.getBigDecimal("quantity_per_finished_unit"),
                rs.getBigDecimal("scrap_factor_percent"),
                rs.getObject("child_active_bom_header_id", UUID.class),
                rs.getBigDecimal("cumulative_qty")
            ),
            rootProductId
        );
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
