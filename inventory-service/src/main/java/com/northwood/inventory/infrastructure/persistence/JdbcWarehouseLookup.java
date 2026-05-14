package com.northwood.inventory.infrastructure.persistence;

import com.northwood.inventory.application.WarehouseLookup;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcWarehouseLookup implements WarehouseLookup {

    private final JdbcTemplate jdbc;

    public JdbcWarehouseLookup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public UUID findIdByCode(String warehouseCode) {
        try {
            return jdbc.queryForObject(
                "SELECT warehouse_id FROM inventory.warehouse WHERE warehouse_code = ?",
                UUID.class, warehouseCode
            );
        } catch (EmptyResultDataAccessException e) {
            throw new IllegalStateException("Unknown warehouse code: " + warehouseCode);
        }
    }
}
