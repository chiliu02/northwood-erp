package com.northwood.graphqlbff.graphql;

import com.northwood.graphqlbff.dto.CustomerView;
import com.northwood.graphqlbff.dto.EntityHitView;
import com.northwood.graphqlbff.dto.ProductView;
import com.northwood.graphqlbff.dto.SalesOrderView;
import com.northwood.graphqlbff.upstream.UpstreamClient;
import graphql.GraphQLContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

/**
 * Hydrates a global-search hit ({@link EntityHitView}, from Elasticsearch read
 * model B) into the <em>fresh</em> authoritative record from its owning service —
 * the "ES finds (stale-tolerant), the service shows the truth" composition that
 * is the read architecture's headline result.
 *
 * <p>{@code product} and {@code customer} are DataLoader-batched: one upstream
 * {@code GET /api/products} / {@code GET /api/customers} per request regardless
 * of how many hits, indexed by id; a hit whose id isn't a product/customer (a
 * different entity type) simply has no match and resolves to {@code null}. Each
 * resolves only for its matching {@code entityType}, with a short-circuit so a
 * search with no product/customer hits makes no upstream call. {@code Product}'s
 * own {@code stock}/{@code atp} fields then layer on via the existing
 * {@code ProductBatchController} loaders — so one query goes search → product →
 * live stock/ATP. {@code salesOrder} is a per-hit fetch (orders in a result page
 * are few; it carries lines, so there's nothing to batch against).
 */
@Controller
public class SearchHitController {

    private final UpstreamClient upstream;

    public SearchHitController(UpstreamClient upstream) {
        this.upstream = upstream;
    }

    @BatchMapping(typeName = "EntityHit", field = "product")
    public Map<EntityHitView, ProductView> product(List<EntityHitView> hits, GraphQLContext context) {
        if (hits.stream().noneMatch(h -> EntityTypes.PRODUCT.equals(h.entityType()))) {
            return Map.of();
        }
        Map<UUID, ProductView> byId =
            BatchLoaders.index(upstream.listProducts(ContextTokens.accessToken(context)), ProductView::id);
        return BatchLoaders.associate(hits, SearchHitController::hitId, byId);
    }

    @BatchMapping(typeName = "EntityHit", field = "customer")
    public Map<EntityHitView, CustomerView> customer(List<EntityHitView> hits, GraphQLContext context) {
        if (hits.stream().noneMatch(h -> EntityTypes.CUSTOMER.equals(h.entityType()))) {
            return Map.of();
        }
        Map<UUID, CustomerView> byId =
            BatchLoaders.index(upstream.listCustomers(ContextTokens.accessToken(context)), CustomerView::id);
        return BatchLoaders.associate(hits, SearchHitController::hitId, byId);
    }

    @SchemaMapping(typeName = "EntityHit", field = "salesOrder")
    public SalesOrderView salesOrder(EntityHitView hit, GraphQLContext context) {
        if (!EntityTypes.SALES_ORDER.equals(hit.entityType())) {
            return null;
        }
        return upstream.getSalesOrder(UUID.fromString(hit.entityId()), ContextTokens.accessToken(context));
    }

    /**
     * The hit's entity id as a UUID — used as the batch-association key. A hit of
     * a non-matching type keys to an id absent from the product/customer index,
     * so it resolves to {@code null} (no explicit per-type filter needed).
     */
    private static UUID hitId(EntityHitView hit) {
        return UUID.fromString(hit.entityId());
    }

    /** Entity-type discriminator values, mirroring search-service's GlobalEntityProjection.TYPE_*. */
    private static final class EntityTypes {
        static final String PRODUCT = "product";
        static final String CUSTOMER = "customer";
        static final String SALES_ORDER = "sales_order";
    }
}
