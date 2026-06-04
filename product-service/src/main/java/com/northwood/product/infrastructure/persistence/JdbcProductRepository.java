package com.northwood.product.infrastructure.persistence;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.northwood.product.domain.ApprovedVendor;
import com.northwood.product.domain.Product;
import com.northwood.product.domain.ProductId;
import com.northwood.product.domain.ProductRepository;
import com.northwood.product.domain.ProductType;
import com.northwood.product.domain.ReplenishmentStrategy;
import com.northwood.product.domain.ValuationClass;
import com.northwood.shared.domain.Currencies;
import com.northwood.shared.domain.DomainEvent;
import com.northwood.shared.domain.Money;
import com.northwood.shared.domain.Sku;
import com.northwood.shared.application.messaging.OutboxTraceHeaders;
import com.northwood.shared.application.security.CurrentUserAccessor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcProductRepository implements ProductRepository {

    public static class DuplicateSkuException extends RuntimeException {
        public DuplicateSkuException(String sku, Throwable cause) {
            super("SKU already exists: " + sku, cause);
        }
    }

    private static final RowMapper<ApprovedVendor> APPROVED_VENDOR_MAPPER = (rs, n) ->
        new ApprovedVendor(
            rs.getObject("supplier_id", UUID.class),
            rs.getString("supplier_code"),
            rs.getString("supplier_name"),
            rs.getBoolean("is_preferred")
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
        Map<UUID, List<ApprovedVendor>> vendorsByProduct = loadApprovedVendors(List.of(id.value()));
        return jdbc.query(
            """
            SELECT product_id, sku, name, description, product_type, base_uom_id,
                   is_stocked, is_purchased, is_manufactured, is_sellable,
                   sales_price, standard_cost,
                   reorder_point, reorder_quantity,
                   replenishment_strategy, valuation_class, active_bom_id,
                   planning_time_fence_days,
                   status, version
            FROM product.product WHERE product_id = ?
            """,
            rowMapper(vendorsByProduct), id.value()
        ).stream().findFirst();
    }

    @Override
    public Optional<Product> findBySku(String sku) {
        Optional<UUID> productId = jdbc.query(
            "SELECT product_id FROM product.product WHERE sku = ?",
            (rs, n) -> rs.getObject("product_id", UUID.class),
            sku
        ).stream().findFirst();
        if (productId.isEmpty()) return Optional.empty();
        return findById(ProductId.of(productId.get()));
    }

    @Override
    public List<Product> findAll() {
        List<UUID> ids = jdbc.query(
            "SELECT product_id FROM product.product ORDER BY sku",
            (rs, n) -> rs.getObject("product_id", UUID.class)
        );
        if (ids.isEmpty()) return List.of();
        Map<UUID, List<ApprovedVendor>> vendorsByProduct = loadApprovedVendors(ids);
        return jdbc.query(
            """
            SELECT product_id, sku, name, description, product_type, base_uom_id,
                   is_stocked, is_purchased, is_manufactured, is_sellable,
                   sales_price, standard_cost,
                   reorder_point, reorder_quantity,
                   replenishment_strategy, valuation_class, active_bom_id,
                   planning_time_fence_days,
                   status, version
            FROM product.product
            ORDER BY sku
            """,
            rowMapper(vendorsByProduct)
        );
    }

    private RowMapper<Product> rowMapper(Map<UUID, List<ApprovedVendor>> vendorsByProduct) {
        return (rs, n) -> {
            UUID pid = rs.getObject("product_id", UUID.class);
            String valuationClassDb = rs.getString("valuation_class");
            String replenishmentStrategyDb = rs.getString("replenishment_strategy");
            return Product.reconstitute(
                ProductId.of(pid),
                new Sku(rs.getString("sku")),
                rs.getString("name"),
                rs.getString("description"),
                ProductType.fromDb(rs.getString("product_type")),
                rs.getObject("base_uom_id", UUID.class),
                rs.getBoolean("is_stocked"),
                rs.getBoolean("is_purchased"),
                rs.getBoolean("is_manufactured"),
                rs.getBoolean("is_sellable"),
                Money.of(rs.getBigDecimal("sales_price"), Currencies.BASE_CURRENCY),
                Money.of(rs.getBigDecimal("standard_cost"), Currencies.BASE_CURRENCY),
                rs.getBigDecimal("reorder_point"),
                rs.getBigDecimal("reorder_quantity"),
                replenishmentStrategyDb == null ? null : ReplenishmentStrategy.fromDb(replenishmentStrategyDb),
                valuationClassDb == null ? null : ValuationClass.fromDb(valuationClassDb),
                rs.getObject("active_bom_id", UUID.class),
                rs.getInt("planning_time_fence_days"),
                Product.Status.fromDb(rs.getString("status")),
                rs.getLong("version"),
                vendorsByProduct.getOrDefault(pid, List.of())
            );
        };
    }

    private Map<UUID, List<ApprovedVendor>> loadApprovedVendors(List<UUID> productIds) {
        if (productIds.isEmpty()) return Map.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(productIds.size(), "?"));
        List<Map.Entry<UUID, ApprovedVendor>> rows = jdbc.query(
            """
            SELECT product_id, supplier_id, supplier_code, supplier_name, is_preferred
              FROM product.approved_vendor
             WHERE product_id IN (""" + placeholders + """
             )
             ORDER BY product_id, is_preferred DESC, supplier_code
            """,
            (rs, n) -> Map.entry(
                rs.getObject("product_id", UUID.class),
                APPROVED_VENDOR_MAPPER.mapRow(rs, n)
            ),
            productIds.toArray()
        );
        Map<UUID, List<ApprovedVendor>> out = new HashMap<>();
        for (Map.Entry<UUID, ApprovedVendor> r : rows) {
            out.computeIfAbsent(r.getKey(), k -> new ArrayList<>()).add(r.getValue());
        }
        return out;
    }

    @Override
    public void save(Product product) {
        String actor = currentUser.currentUsername().orElse(null);

        if (product.version() == 0) {
            insert(product, actor);
        } else {
            update(product, actor);
        }
        if (product.pullApprovedVendorsDirty()) {
            writeApprovedVendors(product);
        }
        for (DomainEvent event : product.pullPendingEvents()) {
            writeOutbox(event, actor);
        }
    }

    private void writeApprovedVendors(Product p) {
        jdbc.update("DELETE FROM product.approved_vendor WHERE product_id = ?", p.id().value());
        for (ApprovedVendor v : p.approvedVendors()) {
            jdbc.update("""
                INSERT INTO product.approved_vendor
                    (approved_vendor_id, product_id, supplier_id, supplier_code, supplier_name, is_preferred)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(), p.id().value(),
                v.supplierId(), v.supplierCode(), v.supplierName(), v.preferred()
            );
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
                    replenishment_strategy, valuation_class, active_bom_id,
                    planning_time_fence_days,
                    status, version,
                    created_by, last_modified_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                p.replenishmentStrategy() == null ? null : p.replenishmentStrategy().dbValue(),
                p.valuationClass() == null ? null : p.valuationClass().dbValue(),
                p.activeBomId(),
                p.planningTimeFenceDays(),
                p.status().dbValue(),
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
                replenishment_strategy = ?, valuation_class = ?, active_bom_id = ?,
                planning_time_fence_days = ?,
                status = ?, version = version + 1,
                last_modified_by = ?
            WHERE product_id = ? AND version = ?
            """,
            p.name(), p.description(),
            p.isStocked(), p.isPurchased(), p.isManufactured(), p.isSellable(),
            p.salesPrice().amount(), p.standardCost().amount(),
            p.reorderPoint(), p.reorderQuantity(),
            p.replenishmentStrategy() == null ? null : p.replenishmentStrategy().dbValue(),
            p.valuationClass() == null ? null : p.valuationClass().dbValue(),
            p.activeBomId(),
            p.planningTimeFenceDays(),
            p.status().dbValue(),
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
                    event_type, event_version, payload, headers, status, actor_user_id
                ) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, 'pending', ?)
                """,
                event.eventId(),
                Product.AGGREGATE_TYPE,
                event.aggregateId(),
                event.eventType(),
                event.eventVersion(),
                json.writeValueAsString(event), OutboxTraceHeaders.currentJson(),
                actor
            );
        } catch (JacksonException e) {
            throw new IllegalStateException("Cannot serialise event " + event.eventType(), e);
        }
    }

}
