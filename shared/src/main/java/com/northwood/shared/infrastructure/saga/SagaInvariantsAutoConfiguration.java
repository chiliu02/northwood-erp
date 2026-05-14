package com.northwood.shared.infrastructure.saga;

import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Registers the {@link SagaStateInvariantChecker} bean. The checker
 * auto-collects every {@link SagaStateInvariantCheck} bean a service
 * registers and runs them on startup ({@code ApplicationReadyEvent}).
 *
 * <p>Services that don't register any checks (e.g. product-service,
 * reporting-service — neither owns a saga) get the checker bean too;
 * with an empty list it short-circuits immediately.
 */
@AutoConfiguration
public class SagaInvariantsAutoConfiguration {

    @Bean
    public SagaStateInvariantChecker sagaStateInvariantChecker(
        JdbcTemplate jdbc,
        List<SagaStateInvariantCheck> checks
    ) {
        return new SagaStateInvariantChecker(jdbc, checks);
    }
}
