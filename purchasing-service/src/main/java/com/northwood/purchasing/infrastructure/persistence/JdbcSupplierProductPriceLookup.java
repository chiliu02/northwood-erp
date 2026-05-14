package com.northwood.purchasing.infrastructure.persistence;

import com.northwood.purchasing.application.SupplierProductPriceLookup;
import com.northwood.purchasing.domain.SupplierId;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcSupplierProductPriceLookup implements SupplierProductPriceLookup {

    private final JdbcTemplate jdbc;

    public JdbcSupplierProductPriceLookup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<BigDecimal> findUnitPrice(SupplierId supplierId, UUID productId, String currencyCode) {
        return findUnitPrice(supplierId, productId, currencyCode, LocalDate.now(), BigDecimal.ZERO);
    }

    @Override
    public Optional<BigDecimal> findUnitPrice(
        SupplierId supplierId,
        UUID productId,
        String currencyCode,
        LocalDate at,
        BigDecimal quantity
    ) {
        String currency = currencyCode == null ? "AUD" : currencyCode;
        LocalDate when = at == null ? LocalDate.now() : at;
        BigDecimal qty = quantity == null ? BigDecimal.ZERO : quantity;
        // Pick the row whose effective range covers `when`, whose
        // min_quantity ≤ qty, and which has the highest min_quantity
        // (deepest applicable tier). Ties on min_quantity broken by latest
        // effective_from (most recent supersedes).
        List<BigDecimal> rows = jdbc.query("""
            SELECT unit_price
              FROM purchasing.supplier_product_price
             WHERE supplier_id = ?
               AND product_id = ?
               AND currency_code = ?
               AND effective_from <= ?
               AND (effective_to IS NULL OR effective_to > ?)
               AND min_quantity <= ?
             ORDER BY min_quantity DESC, effective_from DESC
             LIMIT 1
            """,
            (rs, n) -> rs.getBigDecimal("unit_price"),
            supplierId.value(), productId, currency,
            Date.valueOf(when), Date.valueOf(when), qty
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }
}
