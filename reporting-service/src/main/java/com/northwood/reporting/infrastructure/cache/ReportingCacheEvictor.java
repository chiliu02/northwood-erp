package com.northwood.reporting.infrastructure.cache;

import java.util.UUID;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Event-driven eviction for the reporting cache-aside read models, invoked from
 * the JDBC projection writers ({@code Jdbc*Projection}) — the single write seam
 * each read model funnels through.
 *
 * <p><strong>Why not {@code @CacheEvict} on the inbox handlers?</strong> Every
 * handler extends {@code AbstractInboxHandler}, whose {@code @Transactional
 * handle()} calls {@code apply()} by self-invocation. A self-invoked method
 * bypasses the CGLIB proxy, so a {@code @CacheEvict} on {@code apply()} would
 * never fire. Evicting from the projection writer — which the handler calls as a
 * separate bean — sidesteps the proxy problem and collapses the eviction surface
 * from ~25 handlers to the 3 projections, each of which already holds the exact
 * key being written. The scheduled {@code FinancialDashboardBalanceWorker} is
 * covered for free: it writes through {@code FinancialDashboardProjection
 * .refreshDailyBalances}, which evicts like any other write.
 *
 * <p><strong>After-commit ordering.</strong> The projection write runs inside the
 * inbox handler's transaction. Evicting <em>before</em> that commit opens the race
 * the cache-aside consistency note warns about: a concurrent read in the gap would
 * re-populate the entry from the not-yet-committed (stale) row and pin it for the
 * full TTL. So eviction is deferred to {@code afterCommit} of the active
 * transaction; with no transaction in scope it evicts immediately. The per-cache
 * TTL remains the backstop for the (now closed) gap and for any missed evict.
 */
@Component
public class ReportingCacheEvictor {

    private final CacheManager cacheManager;

    public ReportingCacheEvictor(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    /** Evict the ATP entry for one product after the current transaction commits. */
    public void evictAtp(UUID productId) {
        afterCommit(() -> evict(ReportingCacheNames.ATP, productId));
    }

    /**
     * Evict the whole financial-dashboard cache after commit. The snapshot
     * aggregates across many projections and is keyed only by currency (a
     * handful of entries), so a full clear is cheaper than tracking which
     * currencies an event touched.
     */
    public void evictFinancialDashboard() {
        afterCommit(() -> evictAll(ReportingCacheNames.FINANCIAL_DASHBOARD));
    }

    /** Evict the 360 entry for one sales order after the current transaction commits. */
    public void evictSalesOrder360(UUID salesOrderHeaderId) {
        afterCommit(() -> evict(ReportingCacheNames.SALES_ORDER_360, salesOrderHeaderId));
    }

    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    private void evict(String cacheName, Object key) {
        Cache cache = resolve(cacheName);
        if (cache != null) {
            cache.evict(key);
        }
    }

    private void evictAll(String cacheName) {
        Cache cache = resolve(cacheName);
        if (cache != null) {
            cache.clear();
        }
    }

    @Nullable
    private Cache resolve(String cacheName) {
        return cacheManager.getCache(cacheName);
    }
}
