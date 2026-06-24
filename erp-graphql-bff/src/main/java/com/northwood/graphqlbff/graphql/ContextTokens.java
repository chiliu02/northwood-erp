package com.northwood.graphqlbff.graphql;

import graphql.GraphQLContext;

/**
 * Shared key + accessor for the per-request access token stashed in the
 * {@code GraphQLContext} by {@code TokenPropagationInterceptor}.
 */
public final class ContextTokens {

    public static final String ACCESS_TOKEN = "accessToken";

    private ContextTokens() {}

    /** The captured token, or "" when the request carried none. */
    public static String accessToken(GraphQLContext context) {
        return context.getOrDefault(ACCESS_TOKEN, "");
    }
}
