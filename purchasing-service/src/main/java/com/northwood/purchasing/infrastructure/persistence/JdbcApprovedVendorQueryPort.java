package com.northwood.purchasing.infrastructure.persistence;

import com.northwood.product.domain.ApprovedVendor;
import com.northwood.purchasing.application.ApprovedVendorQueryPort;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcApprovedVendorQueryPort implements ApprovedVendorQueryPort {

    private static final RowMapper<ApprovedVendor> MAPPER = (rs, n) -> new ApprovedVendor(
        rs.getObject("supplier_id", UUID.class),
        rs.getString("supplier_code"),
        rs.getString("supplier_name"),
        rs.getBoolean("is_preferred")
    );

    private final JdbcTemplate jdbc;

    public JdbcApprovedVendorQueryPort(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ApprovedVendor> findApprovedFor(UUID productId) {
        return jdbc.query("""
            SELECT supplier_id, supplier_code, supplier_name, is_preferred
              FROM purchasing.product_approved_vendor
             WHERE product_id = ?
             ORDER BY is_preferred DESC, supplier_code
            """, MAPPER, productId);
    }

}
