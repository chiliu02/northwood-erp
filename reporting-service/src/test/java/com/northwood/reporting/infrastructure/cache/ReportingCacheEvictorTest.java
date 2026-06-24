package com.northwood.reporting.infrastructure.cache;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

/**
 * Unit test for the cache-eviction logic: the no-transaction fast path evicts
 * immediately, while inside a transaction the evict is deferred to after-commit
 * (the ordering that closes the evict-then-stale-read race). A missing cache is
 * tolerated. Cache wiring / serialization is verified by the manual end-to-end
 * in {@code docs/redis.md}.
 */
class ReportingCacheEvictorTest {

    private final CacheManager cacheManager = Mockito.mock(CacheManager.class);
    private final Cache cache = Mockito.mock(Cache.class);
    private final ReportingCacheEvictor evictor = new ReportingCacheEvictor(cacheManager);

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void evicts_immediately_when_no_transaction_active() {
        UUID productId = UUID.randomUUID();
        when(cacheManager.getCache(ReportingCacheNames.ATP)).thenReturn(cache);

        evictor.evictAtp(productId);

        verify(cache).evict(productId);
    }

    @Test
    void defers_eviction_until_after_commit_when_in_transaction() {
        UUID orderId = UUID.randomUUID();
        when(cacheManager.getCache(ReportingCacheNames.SALES_ORDER_360)).thenReturn(cache);
        TransactionSynchronizationManager.initSynchronization();

        evictor.evictSalesOrder360(orderId);

        // Nothing happens at write time — the transaction hasn't committed yet.
        verifyNoInteractions(cache);

        // Simulate the commit: the registered synchronization fires.
        TransactionSynchronizationUtils.triggerAfterCommit();
        verify(cache).evict(orderId);
    }

    @Test
    void financial_dashboard_eviction_clears_the_whole_cache() {
        when(cacheManager.getCache(ReportingCacheNames.FINANCIAL_DASHBOARD)).thenReturn(cache);

        evictor.evictFinancialDashboard();

        verify(cache).clear();
    }

    @Test
    void tolerates_a_missing_cache() {
        when(cacheManager.getCache(ReportingCacheNames.ATP)).thenReturn(null);

        evictor.evictAtp(UUID.randomUUID());

        verify(cache, never()).evict(Mockito.any());
    }
}
