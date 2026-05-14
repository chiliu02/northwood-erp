package com.northwood.shared.infrastructure.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.northwood.shared.domain.saga.SagaInstance;
import com.northwood.shared.application.saga.SagaManager;
import com.northwood.shared.application.saga.SagaPort;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * Positive proxy smoke test for {@link SagaManager}. Boots a minimal Spring
 * context with {@code @EnableTransactionManagement}, registers a concrete
 * {@code TestSagaManager} that has {@code @Transactional} on a sibling apply
 * method (mirroring the production saga managers' shape), and verifies:
 *
 * <ul>
 *   <li>The bean is a CGLIB proxy (the {@code @Transactional} on the apply
 *       method triggers proxy creation for the whole class).</li>
 *   <li>Calling the inherited {@code drain(...)} method on the proxy does not
 *       NPE — i.e. the base class's instance fields ({@code tx},
 *       {@code sagaPort}, etc.) resolve correctly through the proxy.</li>
 * </ul>
 *
 * <p>Locks in the fix for the bug we caught in the audit on 2026-05-10:
 * {@code SagaManager.drain} used to be {@code public final}, which meant
 * CGLIB couldn't override it and the inherited final method body executed
 * on the Objenesis-bypassed proxy with all fields = null → NPE on
 * {@code this.tx.execute(...)}.
 */
@ExtendWith(SpringExtension.class)
class SagaManagerProxyTest {

    @Autowired TestSagaManager manager;

    @Test void saga_manager_bean_is_a_cglib_proxy() {
        assertThat(AopUtils.isCglibProxy(manager))
            .as("@Transactional on a sibling method should make Spring proxy the class via CGLIB")
            .isTrue();
    }

    @Test void inherited_drain_runs_through_proxy_without_NPE() {
        // The bug we're guarding against: drain() reads this.tx / this.sagaPort
        // / this.leaseTtl. If drain is final on a CGLIB-proxied class, the
        // proxy's null fields surface as an NPE on the first field access.
        assertThatNoException().isThrownBy(() ->
            manager.drain(10, "smoke-worker", saga -> {})
        );
    }

    @Test void apply_transactional_method_runs_through_proxy() {
        // Sanity: the @Transactional method itself works through the proxy.
        // (If it didn't, all the production saga managers would already be
        // broken; this is for symmetry.)
        assertThatNoException().isThrownBy(() ->
            manager.applyTouch(UUID.randomUUID())
        );
    }

    // ============================================================
    // Test fixtures
    // ============================================================

    /** Minimal saga subclass — only what {@link SagaManager} needs to drain. */
    static final class TestSaga extends SagaInstance {
        TestSaga() {
            super(
                UUID.randomUUID(), "started", "init", null,
                0, Instant.now(),
                null, null,
                0L, "{}",
                Instant.now(), Instant.now(), null
            );
        }
        @Override public Set<String> terminalStates() { return Set.of("done", "failed"); }
    }

    /** No-op saga port — claimDue returns empty so drain() exits early. */
    interface TestSagaPort extends SagaPort<TestSaga> {}

    /**
     * Concrete {@link SagaManager} subclass with {@code @Transactional} on a
     * sibling apply method, matching the production saga managers' shape.
     * The {@code @Transactional} here is what triggers Spring to CGLIB-proxy
     * the whole class.
     */
    static class TestSagaManager extends SagaManager<TestSaga, TestSagaPort> {
        TestSagaManager(TestSagaPort sagaPort, PlatformTransactionManager tm) {
            super(sagaPort, tm, Duration.ofSeconds(30), Duration.ofSeconds(15));
        }

        @Override protected Set<String> activeStates() { return Set.of("started"); }

        @Transactional
        public void applyTouch(UUID sagaId) {
            // No-op; presence of @Transactional triggers proxy creation.
        }
    }

    @Configuration
    @EnableTransactionManagement
    static class Config {

        @Bean
        TestSagaPort testSagaPort() {
            return new TestSagaPort() {
                @Override public List<TestSaga> claimDue(int batchSize, Set<String> activeStates,
                                                        String leaseOwner, Duration leaseTtl) {
                    return List.of();
                }
                @Override public void save(TestSaga saga) {}
                @Override public void insert(TestSaga saga) {}
                @Override public Optional<TestSaga> findBySagaId(UUID sagaId) { return Optional.empty(); }
            };
        }

        @Bean
        PlatformTransactionManager testTransactionManager() {
            return new PlatformTransactionManager() {
                @Override public TransactionStatus getTransaction(TransactionDefinition d) throws TransactionException {
                    return new SimpleTransactionStatus(true);
                }
                @Override public void commit(TransactionStatus status) throws TransactionException {}
                @Override public void rollback(TransactionStatus status) throws TransactionException {}
            };
        }

        @Bean
        TestSagaManager testSagaManager(TestSagaPort port, PlatformTransactionManager tm) {
            return new TestSagaManager(port, tm);
        }
    }
}
