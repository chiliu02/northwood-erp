package com.northwood.manufacturing.infrastructure.persistence;

import com.northwood.manufacturing.application.inbox.ProductApprovedVendorProjection;
import com.northwood.product.domain.ApprovedVendor;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcProductApprovedVendorProjection implements ProductApprovedVendorProjection {

    private static final Logger log = LoggerFactory.getLogger(JdbcProductApprovedVendorProjection.class);

    private final JdbcTemplate jdbc;

    public JdbcProductApprovedVendorProjection(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void replaceFor(UUID productId, List<ApprovedVendor> vendors) {
        jdbc.update("DELETE FROM manufacturing.product_approved_vendor WHERE product_id = ?", productId);
        if (vendors == null) return;
        for (var v : vendors) {
            jdbc.update("""
                INSERT INTO manufacturing.product_approved_vendor
                    (product_id, supplier_id, supplier_code, supplier_name, is_preferred)
                VALUES (?, ?, ?, ?, ?)
                """,
                productId, v.supplierId(), v.supplierCode(), v.supplierName(), v.preferred()
            );
        }
        log.info("replaced approved-vendor projection for product {} with {} entries",
            productId, vendors.size());
    }

    @Override
    public Optional<UUID> findPreferredSupplierId(UUID productId) {
        // Returns empty when 0 or 2+ rows are flagged preferred — both are
        // "ambiguous" from the rollup engine's perspective and surface as
        // reason='inputs_missing' to the consumer.
        List<UUID> preferred = jdbc.query(
            """
            SELECT supplier_id
            FROM manufacturing.product_approved_vendor
            WHERE product_id = ? AND is_preferred = true
            """,
            (rs, i) -> (UUID) rs.getObject("supplier_id"),
            productId
        );
        if (preferred.size() == 1) return Optional.of(preferred.get(0));
        return Optional.empty();
    }
}
