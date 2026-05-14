package com.northwood.manufacturing.infrastructure.persistence;

import com.northwood.manufacturing.domain.BomCycleDetector;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Cycle detection via PostgreSQL recursive CTE. Walks descendants of
 * {@code startProductId} through the active-BOM graph (plus an optional
 * candidate BOM treated as active for the duration of the walk). Returns true
 * iff {@code targetProductId} appears in the descendant set.
 *
 * <p>The CTE uses {@code UNION} (not {@code UNION ALL}) so it deduplicates and
 * terminates even if the existing graph already contains a (data-corruption)
 * cycle.
 */
@Repository
public class JdbcBomCycleDetector implements BomCycleDetector {

    private final JdbcTemplate jdbc;

    public JdbcBomCycleDetector(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean wouldCreateCycle(UUID startProductId, UUID targetProductId, UUID candidateActiveBomHeaderId) {
        Boolean reached = jdbc.queryForObject("""
            WITH RECURSIVE descendants(product_id) AS (
                SELECT CAST(? AS UUID)
                UNION
                SELECT bl.component_product_id
                FROM descendants d
                JOIN manufacturing.bom_header bh
                  ON bh.finished_product_id = d.product_id
                 AND (bh.status = 'active' OR bh.bom_header_id = CAST(? AS UUID))
                JOIN manufacturing.bom_line bl
                  ON bl.bom_header_id = bh.bom_header_id
            )
            SELECT EXISTS (SELECT 1 FROM descendants WHERE product_id = CAST(? AS UUID))
            """,
            Boolean.class,
            startProductId,
            candidateActiveBomHeaderId,
            targetProductId
        );
        return Boolean.TRUE.equals(reached);
    }
}
