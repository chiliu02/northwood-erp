package com.northwood.graphqlbff.graphql;

import com.northwood.graphqlbff.dto.CustomerView;
import com.northwood.graphqlbff.dto.ProductView;
import com.northwood.graphqlbff.dto.SalesOrderLineView;
import com.northwood.graphqlbff.dto.SalesOrderView;
import com.northwood.graphqlbff.upstream.UpstreamClient;
import graphql.GraphQLContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.stereotype.Controller;

/**
 * Batch loaders for {@code SalesOrder.customer} (by customerId) and
 * {@code SalesOrderLine.product} (by productId — the classic N+1 hotspot: one
 * naive resolver per line collapses to a single {@code GET /api/products}).
 */
@Controller
public class SalesOrderBatchController {

    private final UpstreamClient upstream;

    public SalesOrderBatchController(UpstreamClient upstream) {
        this.upstream = upstream;
    }

    @BatchMapping(typeName = "SalesOrder", field = "customer")
    public Map<SalesOrderView, CustomerView> customer(List<SalesOrderView> orders, GraphQLContext context) {
        Map<UUID, CustomerView> byId =
            BatchLoaders.index(upstream.listCustomers(ContextTokens.accessToken(context)), CustomerView::id);
        return BatchLoaders.associate(orders, SalesOrderView::customerId, byId);
    }

    @BatchMapping(typeName = "SalesOrderLine", field = "product")
    public Map<SalesOrderLineView, ProductView> product(List<SalesOrderLineView> lines, GraphQLContext context) {
        Map<UUID, ProductView> byId =
            BatchLoaders.index(upstream.listProducts(ContextTokens.accessToken(context)), ProductView::id);
        return BatchLoaders.associate(lines, SalesOrderLineView::productId, byId);
    }
}
