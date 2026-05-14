package com.northwood.purchasing.infrastructure.persistence;

import com.northwood.purchasing.domain.SupplierProductPriceRepository;
import com.northwood.shared.application.security.CurrentUserAccessor;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcSupplierProductPriceRepository implements SupplierProductPriceRepository {

    private final JdbcTemplate jdbc;
    private final CurrentUserAccessor currentUser;

    public JdbcSupplierProductPriceRepository(JdbcTemplate jdbc, CurrentUserAccessor currentUser) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
    }

    @Override
    public Optional<ExistingPrice> find(UUID supplierId, UUID productId, String currencyCode) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                """
                SELECT supplier_product_price_id, unit_price
                  FROM purchasing.supplier_product_price
                 WHERE supplier_id = ? AND product_id = ? AND currency_code = ?
                """,
                (rs, n) -> new ExistingPrice(
                    rs.getObject("supplier_product_price_id", UUID.class),
                    rs.getBigDecimal("unit_price")
                ),
                supplierId, productId, currencyCode
            ));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public void insert(UUID priceId, UUID supplierId, UUID productId, String currencyCode, BigDecimal unitPrice) {
        String actor = currentUser.currentUsername().orElse(null);
        jdbc.update("""
            INSERT INTO purchasing.supplier_product_price
                (supplier_product_price_id, supplier_id, product_id, currency_code, unit_price,
                 created_by, last_modified_by)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """,
            priceId, supplierId, productId, currencyCode, unitPrice,
            actor, actor
        );
    }

    @Override
    public void updatePrice(UUID priceId, BigDecimal newUnitPrice) {
        String actor = currentUser.currentUsername().orElse(null);
        jdbc.update("""
            UPDATE purchasing.supplier_product_price
               SET unit_price = ?, version = version + 1, last_modified_by = ?
             WHERE supplier_product_price_id = ?
            """,
            newUnitPrice, actor, priceId
        );
    }

    @Override
    public List<PriceRow> listForSupplier(UUID supplierId) {
        return jdbc.query("""
            SELECT supplier_product_price_id, supplier_id, product_id, currency_code, unit_price, version
              FROM purchasing.supplier_product_price
             WHERE supplier_id = ?
             ORDER BY product_id
            """,
            (rs, n) -> new PriceRow(
                rs.getObject("supplier_product_price_id", UUID.class),
                rs.getObject("supplier_id", UUID.class),
                rs.getObject("product_id", UUID.class),
                rs.getString("currency_code"),
                rs.getBigDecimal("unit_price"),
                rs.getLong("version")
            ),
            supplierId
        );
    }
}
