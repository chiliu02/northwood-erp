package com.northwood.graphqlbff.graphql;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Helpers shared by the {@code @BatchMapping} loaders: build a UUID index over
 * a fetched list, then associate each source object with its match. A source
 * with no match is simply absent from the returned map, which Spring GraphQL
 * resolves to {@code null} for that field.
 */
final class BatchLoaders {

    private BatchLoaders() {}

    static <V> Map<UUID, V> index(List<V> values, Function<V, UUID> key) {
        Map<UUID, V> byKey = new HashMap<>();
        for (V value : values) {
            byKey.put(key.apply(value), value);
        }
        return byKey;
    }

    static <S, V> Map<S, V> associate(List<S> sources, Function<S, UUID> key, Map<UUID, V> byKey) {
        Map<S, V> result = new HashMap<>();
        for (S source : sources) {
            V value = byKey.get(key.apply(source));
            if (value != null) {
                result.put(source, value);
            }
        }
        return result;
    }
}
