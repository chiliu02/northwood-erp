package com.northwood.purchasing.infrastructure.persistence;

import com.northwood.purchasing.application.SupplierProductPriceQueryPort;
import com.northwood.purchasing.application.dto.SupplierPriceListView;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcSupplierProductPriceQueryPort implements SupplierProductPriceQueryPort {

    private static final RowMapper<SupplierPriceListView> ROW_MAPPER = (rs, n) -> new SupplierPriceListView(
        rs.getObject("supplier_product_price_id", UUID.class),
        rs.getObject("supplier_id", UUID.class),
        rs.getString("supplier_code"),
        rs.getString("supplier_name"),
        rs.getObject("product_id", UUID.class),
        rs.getString("product_sku"),
        rs.getString("product_name"),
        rs.getString("currency_code"),
        rs.getBigDecimal("unit_price"),
        rs.getBigDecimal("min_quantity"),
        rs.getLong("version")
    );

    private static final String FIND_ALL = """
        SELECT spp.supplier_product_price_id,
               spp.supplier_id,
               s.supplier_code,
               s.name AS supplier_name,
               spp.product_id,
               pc.product_sku,
               pc.product_name,
               spp.currency_code,
               spp.unit_price,
               spp.min_quantity,
               spp.version
          FROM purchasing.supplier_product_price spp
          LEFT JOIN purchasing.supplier s     ON s.supplier_id = spp.supplier_id
          LEFT JOIN purchasing.product_card pc ON pc.product_id = spp.product_id
         ORDER BY s.name NULLS LAST, pc.product_sku NULLS LAST, spp.min_quantity
        """;

    private final JdbcTemplate jdbc;

    public JdbcSupplierProductPriceQueryPort(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<SupplierPriceListView> findAll() {
        return jdbc.query(FIND_ALL, ROW_MAPPER);
    }
}
