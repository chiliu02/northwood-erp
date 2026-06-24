package com.northwood.graphqlbff.graphql;

import com.northwood.graphqlbff.dto.CustomerView;
import com.northwood.graphqlbff.dto.ProductView;
import com.northwood.graphqlbff.dto.SalesOrder360View;
import com.northwood.graphqlbff.dto.SalesOrderView;
import com.northwood.graphqlbff.upstream.UpstreamClient;
import graphql.GraphQLContext;
import java.util.List;
import java.util.UUID;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

/**
 * Root query resolvers. Each returns the locally-mirrored view record for the
 * matching GraphQL type; relationship fields (customer / product / stock / atp)
 * are filled in by the {@code @BatchMapping} loaders. {@code id} arguments come
 * in as the GraphQL {@code ID} scalar (a String) and are parsed to UUID here,
 * avoiding any String→UUID argument-binding conversion.
 */
@Controller
public class QueryController {

    private final UpstreamClient upstream;

    public QueryController(UpstreamClient upstream) {
        this.upstream = upstream;
    }

    @QueryMapping
    public List<SalesOrder360View> salesOrders(GraphQLContext context) {
        return upstream.listSalesOrders(ContextTokens.accessToken(context));
    }

    @QueryMapping
    public SalesOrderView salesOrder(@Argument String id, GraphQLContext context) {
        return upstream.getSalesOrder(UUID.fromString(id), ContextTokens.accessToken(context));
    }

    @QueryMapping
    public List<ProductView> products(GraphQLContext context) {
        return upstream.listProducts(ContextTokens.accessToken(context));
    }

    @QueryMapping
    public ProductView product(@Argument String id, GraphQLContext context) {
        return upstream.getProduct(UUID.fromString(id), ContextTokens.accessToken(context));
    }

    @QueryMapping
    public CustomerView customer(@Argument String id, GraphQLContext context) {
        return upstream.getCustomer(UUID.fromString(id), ContextTokens.accessToken(context));
    }
}
