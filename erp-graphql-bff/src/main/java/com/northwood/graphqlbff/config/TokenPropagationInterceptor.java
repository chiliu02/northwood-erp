package com.northwood.graphqlbff.config;

import com.northwood.graphqlbff.graphql.ContextTokens;
import com.northwood.graphqlbff.upstream.BffAccessTokenAccessor;
import java.util.Map;
import org.springframework.graphql.server.WebGraphQlInterceptor;
import org.springframework.graphql.server.WebGraphQlRequest;
import org.springframework.graphql.server.WebGraphQlResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Captures the Keycloak access token <em>once per request</em> on the servlet
 * thread (where the {@code SecurityContext} / request are available) and stashes
 * it in the {@code GraphQLContext}. Resolvers and {@code @BatchMapping} loaders
 * read it back via {@link ContextTokens#accessToken} — they run on
 * {@code CompletableFuture} threads where {@code SecurityContextHolder} is empty,
 * so reading the token there directly would fail.
 */
@Component
public class TokenPropagationInterceptor implements WebGraphQlInterceptor {

    private final BffAccessTokenAccessor tokens;

    public TokenPropagationInterceptor(BffAccessTokenAccessor tokens) {
        this.tokens = tokens;
    }

    @Override
    public Mono<WebGraphQlResponse> intercept(WebGraphQlRequest request, Chain chain) {
        String token = tokens.currentAccessToken();
        request.configureExecutionInput((executionInput, builder) ->
            builder.graphQLContext(Map.of(ContextTokens.ACCESS_TOKEN, token == null ? "" : token)).build());
        return chain.next(request);
    }
}
