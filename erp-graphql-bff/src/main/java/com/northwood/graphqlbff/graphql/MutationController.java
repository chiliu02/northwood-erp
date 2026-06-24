package com.northwood.graphqlbff.graphql;

import com.northwood.graphqlbff.dto.PlaceOrderInput;
import com.northwood.graphqlbff.dto.SalesOrderView;
import com.northwood.graphqlbff.upstream.UpstreamClient;
import graphql.GraphQLContext;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.stereotype.Controller;

/**
 * The single write path: {@code placeOrder} forwards to sales-service's
 * {@code POST /api/sales-orders}. Authorization is enforced upstream — the
 * service requires the sales-clerk role on the forwarded Bearer token — so a
 * caller without it gets the service's 403 surfaced as a GraphQL error.
 */
@Controller
public class MutationController {

    private final UpstreamClient upstream;

    public MutationController(UpstreamClient upstream) {
        this.upstream = upstream;
    }

    @MutationMapping
    public SalesOrderView placeOrder(@Argument PlaceOrderInput input, GraphQLContext context) {
        return upstream.placeOrder(input, ContextTokens.accessToken(context));
    }
}
