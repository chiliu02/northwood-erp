package com.northwood.manufacturing.infrastructure.persistence;

import com.northwood.manufacturing.application.inbox.ProductActiveBomProjection;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcProductActiveBomProjection implements ProductActiveBomProjection {

    private static final Logger log = LoggerFactory.getLogger(JdbcProductActiveBomProjection.class);

    private final JdbcTemplate jdbc;

    public JdbcProductActiveBomProjection(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void apply(UUID productId, UUID newActiveBomId) {
        jdbc.update("""
            INSERT INTO manufacturing.product_card (product_id, active_bom_header_id, updated_at)
            VALUES (?, ?, now())
            ON CONFLICT (product_id) DO UPDATE SET
                active_bom_header_id = EXCLUDED.active_bom_header_id,
                updated_at = now()
            """,
            productId, newActiveBomId
        );
        log.info("active BOM projection updated: product={} active_bom={}", productId, newActiveBomId);
    }

    @Override
    public Optional<UUID> findActiveBomId(UUID productId) {
        var ids = jdbc.query(
            "SELECT active_bom_header_id FROM manufacturing.product_card WHERE product_id = ?",
            (rs, i) -> (UUID) rs.getObject("active_bom_header_id"),
            productId
        );
        if (ids.isEmpty()) return Optional.empty();
        return Optional.ofNullable(ids.get(0));
    }
}
