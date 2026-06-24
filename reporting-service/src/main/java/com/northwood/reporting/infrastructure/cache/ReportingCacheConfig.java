package com.northwood.reporting.infrastructure.cache;

import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.LoggingCacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Makes the reporting cache-aside layer degrade gracefully when Redis is
 * unreachable. Before this layer existed, reporting had no Redis dependency and
 * served every read straight from Postgres; a developer running the service
 * without {@code docker compose up redis} must keep that behaviour rather than
 * see every cached endpoint fail.
 *
 * <p>A {@link LoggingCacheErrorHandler} logs cache get/put/evict/clear failures
 * (so a misconfigured or down Redis is visible) and then swallows them, letting
 * the call fall through to the underlying query — a cache miss, not a 500. The
 * per-cache TTL plus this handler mean Redis is an accelerator, never a hard
 * dependency of the read path.
 */
@Configuration
public class ReportingCacheConfig implements CachingConfigurer {

    @Bean
    @Override
    public CacheErrorHandler errorHandler() {
        return new LoggingCacheErrorHandler(true);
    }
}
