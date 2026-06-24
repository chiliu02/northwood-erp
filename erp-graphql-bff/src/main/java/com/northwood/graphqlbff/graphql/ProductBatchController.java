package com.northwood.graphqlbff.graphql;

import com.northwood.graphqlbff.dto.AvailableToPromiseView;
import com.northwood.graphqlbff.dto.ProductView;
import com.northwood.graphqlbff.dto.StockItemView;
import com.northwood.graphqlbff.upstream.UpstreamClient;
import graphql.GraphQLContext;
import java.util.List;
import java.util.Map;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.stereotype.Controller;

/**
 * Batch loaders for {@code Product.stock} and {@code Product.atp}. Each fires
 * once per request regardless of how many products are in play: one
 * {@code GET /api/stock-items} and one {@code GET /api/atp}, indexed by
 * productId — the resolution of the N+1 problem the demo highlights.
 */
@Controller
public class ProductBatchController {

    private final UpstreamClient upstream;

    public ProductBatchController(UpstreamClient upstream) {
        this.upstream = upstream;
    }

    @BatchMapping(typeName = "Product", field = "stock")
    public Map<ProductView, StockItemView> stock(List<ProductView> products, GraphQLContext context) {
        Map<java.util.UUID, StockItemView> byProduct =
            BatchLoaders.index(upstream.listStockItems(ContextTokens.accessToken(context)), StockItemView::productId);
        return BatchLoaders.associate(products, ProductView::id, byProduct);
    }

    @BatchMapping(typeName = "Product", field = "atp")
    public Map<ProductView, AvailableToPromiseView> atp(List<ProductView> products, GraphQLContext context) {
        Map<java.util.UUID, AvailableToPromiseView> byProduct =
            BatchLoaders.index(upstream.listAtp(ContextTokens.accessToken(context)), AvailableToPromiseView::productId);
        return BatchLoaders.associate(products, ProductView::id, byProduct);
    }
}
