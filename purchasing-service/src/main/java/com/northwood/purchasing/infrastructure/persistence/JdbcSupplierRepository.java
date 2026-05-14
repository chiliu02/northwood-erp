package com.northwood.purchasing.infrastructure.persistence;

import com.northwood.purchasing.domain.Supplier;
import com.northwood.purchasing.domain.SupplierId;
import com.northwood.purchasing.domain.SupplierRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcSupplierRepository implements SupplierRepository {

    private static final RowMapper<Supplier> ROW_MAPPER = (rs, n) -> new Supplier(
        SupplierId.of(rs.getObject("supplier_id", UUID.class)),
        rs.getString("supplier_code"),
        rs.getString("name"),
        rs.getString("status")
    );

    private final JdbcTemplate jdbc;

    public JdbcSupplierRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Supplier> findById(SupplierId id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                "SELECT supplier_id, supplier_code, name, status FROM purchasing.supplier WHERE supplier_id = ?",
                ROW_MAPPER, id.value()
            ));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<Supplier> findByCode(String supplierCode) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                "SELECT supplier_id, supplier_code, name, status FROM purchasing.supplier WHERE supplier_code = ?",
                ROW_MAPPER, supplierCode
            ));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<Supplier> findAll() {
        return jdbc.query(
            """
            SELECT supplier_id, supplier_code, name, status
            FROM purchasing.supplier
            ORDER BY supplier_code
            """,
            ROW_MAPPER
        );
    }

    @Override
    public Supplier defaultSupplier() {
        try {
            return jdbc.queryForObject(
                """
                SELECT supplier_id, supplier_code, name, status
                FROM purchasing.supplier
                WHERE status = 'active'
                ORDER BY supplier_code
                LIMIT 1
                """,
                ROW_MAPPER
            );
        } catch (EmptyResultDataAccessException e) {
            throw new IllegalStateException(
                "No active supplier seeded; northwood_erp.sql provisions SUP-001 — check the install."
            );
        }
    }

}
