package com.northwood.product.infrastructure.persistence;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.northwood.product.domain.Product;
import com.northwood.product.domain.ProductId;
import com.northwood.product.domain.ProductRepository;
import com.northwood.product.domain.ProductType;
import com.northwood.shared.domain.DomainEvent;
import com.northwood.shared.domain.Money;
import com.northwood.shared.domain.Sku;
import com.northwood.shared.application.security.CurrentUserAccessor;
import java.math.BigDecimal;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcProductRepository implements ProductRepository {

    private static final RowMapper<Product> ROW_MAPPER = (rs, n) -> Product.reconstitute(
        ProductId.of(rs.getObject("product_id", UUID.class)),
        new Sku(rs.getString("sku")),
        rs.getString("name"),
        rs.getString("description"),
        ProductType.fromDb(rs.getString("product_type")),
        rs.getObject("base_uom_id", UUID.class),
        rs.getBoolean("is_stocked"),
        rs.getBoolean("is_purchased"),
        rs.getBoolean("is_manufactured"),
        rs.getBoolean("is_sellable"),
        Money.of(rs.getBigDecimal("sales_price"), "AUD"),
        Money.of(rs.getBigDecimal("standard_cost"), "AUD"),
        rs.getBigDecimal("reorder_point"),
        rs.getBigDecimal("reorder_quantity"),
        rs.getString("valuation_class"),
        rs.getObject("active_bom_id", UUID.class),
        Product.Status.valueOf(rs.getString("status").toUpperCase()),
        rs.getLong("version")
    );

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final CurrentUserAccessor currentUser;

    public JdbcProductRepository(JdbcTemplate jdbc, ObjectMapper json, CurrentUserAccessor currentUser) {
        this.jdbc = jdbc;
        this.json = json;
        this.currentUser = currentUser;
    }

    @Override
    public Optional<Product> findById(ProductId id) {
        return jdbc.query(
            """
            SELECT product_id, sku, name, description, product_type, base_uom_id,
                   is_stocked, is_purchased, is_manufactured, is_sellable,
                   sales_price, standard_cost,
                   reorder_point, reorder_quantity,
                   valuation_class, active_bom_id,
                   status, version
            FROM product.product WHERE product_id = ?
            """,
            ROW_MAPPER, id.value()
        ).stream().findFirst();
    }

    @Override
    public Optional<Product> findBySku(String sku) {
        return jdbc.query(
            """
            SELECT product_id, sku, name, description, product_type, base_uom_id,
                   is_stocked, is_purchased, is_manufactured, is_sellable,
                   sales_price, standard_cost,
                   reorder_point, reorder_quantity,
                   valuation_class, active_bom_id,
                   status, version
            FROM product.product WHERE sku = ?
            """,
            ROW_MAPPER, sku
        ).stream().findFirst();
    }

    @Override
    public List<Product> findAll() {
        return jdbc.query(
            """
            SELECT product_id, sku, name, description, product_type, base_uom_id,
                   is_stocked, is_purchased, is_manufactured, is_sellable,
                   sales_price, standard_cost,
                   reorder_point, reorder_quantity,
                   valuation_class, active_bom_id,
                   status, version
            FROM product.product
            ORDER BY sku
            """,
            ROW_MAPPER
        );
    }

    @Override
    public void save(Product product) {
        // Slice B2: stamp the actor on every write. currentUser is request-scoped
        // (HTTP threads carry the JWT principal); saga / scheduler / publisher
        // threads return Optional.empty() and the column stays null.
        String actor = currentUser.currentUsername().orElse(null);

        // Two paths: INSERT (version == 0 and id was just minted) or UPDATE.
        // The optimistic-concurrency contract: UPDATE checks version and bumps
        // it, refusing to write if another tx beat us to it.
        if (product.version() == 0) {
            insert(product, actor);
        } else {
            update(product, actor);
        }
        // In the same transaction, write pending domain events to the outbox.
        // The transactional boundary is the application service's @Transactional.
        for (DomainEvent event : product.pullPendingEvents()) {
            writeOutbox(event, actor);
        }
    }

    private void insert(Product p, String actor) {
        try {
            jdbc.update("""
                INSERT INTO product.product (
                    product_id, sku, name, description, product_type, base_uom_id,
                    is_stocked, is_purchased, is_manufactured, is_sellable,
                    sales_price, standard_cost,
                    reorder_point, reorder_quantity,
                    valuation_class, active_bom_id,
                    status, version,
                    created_by, last_modified_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                p.id().value(),
                p.sku().value(),
                p.name(),
                p.description(),
                p.productType().dbValue(),
                p.baseUomId(),
                p.isStocked(), p.isPurchased(), p.isManufactured(), p.isSellable(),
                p.salesPrice().amount(), p.standardCost().amount(),
                p.reorderPoint(), p.reorderQuantity(),
                p.valuationClass(), p.activeBomId(),
                p.status().name().toLowerCase(),
                // Persist with version=1 so a subsequent reload + mutate +
                // save routes to UPDATE. The aggregate's in-memory version=0
                // sentinel means "not yet persisted"; the row in the DB never
                // sits at version=0.
                1L,
                actor, actor
            );
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateSkuException(p.sku().value(), e);
        }
    }

    private void update(Product p, String actor) {
        int rows = jdbc.update("""
            UPDATE product.product SET
                name = ?, description = ?,
                is_stocked = ?, is_purchased = ?, is_manufactured = ?, is_sellable = ?,
                sales_price = ?, standard_cost = ?,
                reorder_point = ?, reorder_quantity = ?,
                valuation_class = ?, active_bom_id = ?,
                status = ?, version = version + 1,
                last_modified_by = ?
            WHERE product_id = ? AND version = ?
            """,
            p.name(), p.description(),
            p.isStocked(), p.isPurchased(), p.isManufactured(), p.isSellable(),
            p.salesPrice().amount(), p.standardCost().amount(),
            p.reorderPoint(), p.reorderQuantity(),
            p.valuationClass(), p.activeBomId(),
            p.status().name().toLowerCase(),
            actor,
            p.id().value(), p.version()
        );
        if (rows == 0) {
            throw new OptimisticLockingFailureException(
                "Product " + p.id().value() + " was modified by another transaction"
            );
        }
    }

    private void writeOutbox(DomainEvent event, String actor) {
        try {
            jdbc.update("""
                INSERT INTO product.outbox_message (
                    outbox_message_id, aggregate_type, aggregate_id,
                    event_type, event_version, payload, status, actor_user_id
                ) VALUES (?, ?, ?, ?, ?, ?::jsonb, 'pending', ?)
                """,
                event.eventId(),
                Product.AGGREGATE_TYPE,
                event.aggregateId(),
                event.eventType(),
                event.eventVersion(),
                json.writeValueAsString(event),
                actor
            );
        } catch (JacksonException e) {
            throw new IllegalStateException("Cannot serialise event " + event.eventType(), e);
        }
    }

    public static class DuplicateSkuException extends RuntimeException {
        public DuplicateSkuException(String sku, Throwable cause) {
            super("SKU already exists: " + sku, cause);
        }
    }
}
