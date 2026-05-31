package com.northwood.purchasing.infrastructure.persistence;

import com.northwood.purchasing.domain.SupplierProductPrice;
import com.northwood.purchasing.domain.SupplierProductPriceId;
import com.northwood.purchasing.domain.SupplierProductPriceRepository;
import com.northwood.shared.application.messaging.OutboxTraceHeaders;
import com.northwood.shared.application.security.CurrentUserAccessor;
import com.northwood.shared.domain.DomainEvent;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcSupplierProductPriceRepository implements SupplierProductPriceRepository {

    private static final RowMapper<SupplierProductPrice> ROW_MAPPER = (rs, n) -> SupplierProductPrice.reconstitute(
        SupplierProductPriceId.of(rs.getObject("supplier_product_price_id", UUID.class)),
        rs.getObject("supplier_id", UUID.class),
        rs.getObject("product_id", UUID.class),
        rs.getString("currency_code"),
        rs.getBigDecimal("unit_price"),
        rs.getLong("version")
    );

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final CurrentUserAccessor currentUser;

    public JdbcSupplierProductPriceRepository(JdbcTemplate jdbc, ObjectMapper json, CurrentUserAccessor currentUser) {
        this.jdbc = jdbc;
        this.json = json;
        this.currentUser = currentUser;
    }

    @Override
    public Optional<SupplierProductPrice> findByKey(UUID supplierId, UUID productId, String currencyCode) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                """
                SELECT supplier_product_price_id, supplier_id, product_id, currency_code, unit_price, version
                  FROM purchasing.supplier_product_price
                 WHERE supplier_id = ? AND product_id = ? AND currency_code = ?
                """,
                ROW_MAPPER, supplierId, productId, currencyCode
            ));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public void save(SupplierProductPrice price) {
        String actor = currentUser.currentUsername().orElse(null);
        if (price.version() == 0L) {
            jdbc.update("""
                INSERT INTO purchasing.supplier_product_price
                    (supplier_product_price_id, supplier_id, product_id, currency_code, unit_price,
                     created_by, last_modified_by)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                price.id().value(), price.supplierId(), price.productId(), price.currencyCode(),
                price.unitPrice(), actor, actor
            );
        } else {
            int updated = jdbc.update("""
                UPDATE purchasing.supplier_product_price
                   SET unit_price = ?, version = version + 1, last_modified_by = ?
                 WHERE supplier_product_price_id = ? AND version = ?
                """,
                price.unitPrice(), actor, price.id().value(), price.version()
            );
            if (updated == 0) {
                throw new OptimisticLockingFailureException(
                    "SupplierProductPrice " + price.id().value() + " was modified concurrently (expected version "
                        + price.version() + ")"
                );
            }
        }
        for (DomainEvent event : price.pullPendingEvents()) {
            try {
                jdbc.update("""
                    INSERT INTO purchasing.outbox_message (
                        outbox_message_id, aggregate_type, aggregate_id,
                        event_type, event_version, payload, headers, status, actor_user_id
                    ) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, 'pending', ?)
                    """,
                    event.eventId(),
                    SupplierProductPrice.AGGREGATE_TYPE,
                    event.aggregateId(),
                    event.eventType(),
                    event.eventVersion(),
                    json.writeValueAsString(event), OutboxTraceHeaders.currentJson(),
                    actor
                );
            } catch (JacksonException e) {
                throw new IllegalStateException("Cannot serialise " + event.eventType(), e);
            }
        }
    }

    @Override
    public List<SupplierProductPrice> listForSupplier(UUID supplierId) {
        return jdbc.query("""
            SELECT supplier_product_price_id, supplier_id, product_id, currency_code, unit_price, version
              FROM purchasing.supplier_product_price
             WHERE supplier_id = ?
             ORDER BY product_id
            """,
            ROW_MAPPER, supplierId
        );
    }
}
