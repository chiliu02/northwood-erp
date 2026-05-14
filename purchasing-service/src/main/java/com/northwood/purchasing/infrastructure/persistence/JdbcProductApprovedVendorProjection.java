package com.northwood.purchasing.infrastructure.persistence;

import com.northwood.product.domain.ApprovedVendor;
import com.northwood.purchasing.application.inbox.ProductApprovedVendorProjection;
import java.util.List;
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
        jdbc.update("DELETE FROM purchasing.product_approved_vendor WHERE product_id = ?", productId);
        if (vendors == null) return;
        for (var v : vendors) {
            jdbc.update("""
                INSERT INTO purchasing.product_approved_vendor
                    (product_approved_vendor_id, product_id, supplier_id, supplier_code, supplier_name, is_preferred)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(), productId, v.supplierId(), v.supplierCode(), v.supplierName(), v.preferred()
            );
        }
        log.info("replaced approved-vendor projection for product {} with {} entries",
            productId, vendors.size());
    }
}
