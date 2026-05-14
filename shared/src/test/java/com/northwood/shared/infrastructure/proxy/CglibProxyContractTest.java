package com.northwood.shared.infrastructure.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.saga.SagaManager;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Regression guard for the CGLIB-proxy gotcha (see CLAUDE.md → "final on a
 * method that needs @Transactional advice silently breaks the proxy"). Spring
 * creates a CGLIB proxy for any class with {@code @Transactional} (or other
 * AOP advice) on any method; Objenesis instantiates the proxy without invoking
 * the constructor, so the proxy's instance fields are null. CGLIB cannot
 * override final methods — they execute on the proxy with null fields and NPE
 * on the first field access.
 *
 * <p>The cost of the bug class is high (silent NPEs routed to DLT or swallowed
 * by Spring's scheduler) and the structural test is tiny, so we lock in the
 * invariant here. If a future change reintroduces {@code final} on any of the
 * methods of {@link SagaManager} or {@link AbstractInboxHandler} that subclasses
 * inherit, this test fails at compile-vs-runtime boundary instead of in
 * production.
 *
 * <p>Two sites already bitten and now guarded:
 * <ul>
 *   <li>{@code AbstractInboxHandler#handles} / {@code #consumerName} — caused
 *       Kafka listener NPE → DLT (caught by ReorderPolicyChangedSeamIT 2026-05-10).</li>
 *   <li>{@code SagaManager#drain} — would have caused saga-worker NPE on
 *       every poll tick under {@code @Profile("kafka")} (caught by audit, no IT).</li>
 * </ul>
 */
class CglibProxyContractTest {

    @Test void SagaManager_has_no_final_methods() {
        assertNoFinalInstanceMethods(SagaManager.class);
    }

    @Test void AbstractInboxHandler_has_no_final_methods() {
        assertNoFinalInstanceMethods(AbstractInboxHandler.class);
    }

    /**
     * Asserts the class declares no {@code final} instance methods. {@code static}
     * and {@code private} methods are exempt — CGLIB doesn't proxy those anyway,
     * so a {@code final} on them is structurally fine.
     */
    private static void assertNoFinalInstanceMethods(Class<?> clazz) {
        List<String> finalMethods = Arrays.stream(clazz.getDeclaredMethods())
            .filter(m -> Modifier.isFinal(m.getModifiers()))
            .filter(m -> !Modifier.isStatic(m.getModifiers()))
            .filter(m -> !Modifier.isPrivate(m.getModifiers()))
            .map(Method::toString)
            .toList();
        assertThat(finalMethods)
            .as("Class %s must not have final instance methods — CGLIB cannot override them and "
                + "an Objenesis-bypassed proxy would NPE on field access. See CLAUDE.md.",
                clazz.getName())
            .isEmpty();
    }
}
