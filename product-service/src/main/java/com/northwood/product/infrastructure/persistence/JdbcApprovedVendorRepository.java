package com.northwood.product.infrastructure.persistence;

import com.northwood.product.domain.ApprovedVendor;
import com.northwood.product.domain.ApprovedVendorRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcApprovedVendorRepository implements ApprovedVendorRepository {

    private static final RowMapper<ApprovedVendor> MAPPER = (rs, n) ->
        new ApprovedVendor(
            rs.getObject("supplier_id", UUID.class),
            rs.getString("supplier_code"),
            rs.getString("supplier_name"),
            rs.getBoolean("is_preferred")
        );

    private final JdbcTemplate jdbc;

    public JdbcApprovedVendorRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ApprovedVendor> findForProduct(UUID productId) {
        return jdbc.query("""
            SELECT supplier_id, supplier_code, supplier_name, is_preferred
              FROM product.approved_vendor
             WHERE product_id = ?
             ORDER BY is_preferred DESC, supplier_code
            """, MAPPER, productId);
    }

    @Override
    public void replaceFor(UUID productId, List<ApprovedVendor> vendors) {
        // Atomic-ish via the surrounding @Transactional: delete-all-then-insert.
        // Cleaner than per-row diff for the showcase; the event carries the full
        // list anyway so the projection can replace its copy in one statement.
        jdbc.update("DELETE FROM product.approved_vendor WHERE product_id = ?", productId);
        for (var v : vendors) {
            jdbc.update("""
                INSERT INTO product.approved_vendor
                    (approved_vendor_id, product_id, supplier_id, supplier_code, supplier_name, is_preferred)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(), productId,
                v.supplierId(), v.supplierCode(), v.supplierName(), v.preferred()
            );
        }
    }

}
