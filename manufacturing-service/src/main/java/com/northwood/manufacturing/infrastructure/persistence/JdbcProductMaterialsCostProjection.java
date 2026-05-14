package com.northwood.manufacturing.infrastructure.persistence;

import com.northwood.manufacturing.application.inbox.ProductMaterialsCostProjection;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcProductMaterialsCostProjection implements ProductMaterialsCostProjection {

    private static final Logger log = LoggerFactory.getLogger(JdbcProductMaterialsCostProjection.class);

    private final JdbcTemplate jdbc;

    public JdbcProductMaterialsCostProjection(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void apply(
        UUID productId,
        BigDecimal materialsCost,
        String currencyCode,
        String reason,
        Instant capturedAt
    ) {
        Timestamp ts = Timestamp.from(capturedAt);
        jdbc.update("""
            INSERT INTO manufacturing.product_materials_cost
                (product_id, materials_cost, currency_code, reason, captured_at, updated_at)
            VALUES (?, ?, ?, ?, ?, now())
            ON CONFLICT (product_id) DO UPDATE SET
                materials_cost = EXCLUDED.materials_cost,
                currency_code = EXCLUDED.currency_code,
                reason = EXCLUDED.reason,
                captured_at = EXCLUDED.captured_at,
                updated_at = now()
            """,
            productId, materialsCost, currencyCode, reason, ts
        );
        log.info("materials cost rolled up: product={} cost={} currency={} reason={}",
            productId, materialsCost, currencyCode, reason);
    }

    @Override
    public Optional<MaterialsCost> findByProductId(UUID productId) {
        var rows = jdbc.query(
            """
            SELECT product_id, materials_cost, currency_code, reason, captured_at
            FROM manufacturing.product_materials_cost
            WHERE product_id = ?
            """,
            (rs, i) -> new MaterialsCost(
                (UUID) rs.getObject("product_id"),
                rs.getBigDecimal("materials_cost"),
                rs.getString("currency_code"),
                rs.getString("reason"),
                rs.getTimestamp("captured_at").toInstant()
            ),
            productId
        );
        if (rows.isEmpty()) return Optional.empty();
        return Optional.of(rows.get(0));
    }
}
