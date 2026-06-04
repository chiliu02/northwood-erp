package com.northwood.sales.infrastructure.saga;

import java.time.InstantSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exposes the wall-clock {@link InstantSource} the fulfilment saga worker reads
 * for the planning-time-fence gate decision (and all park / event timestamps).
 * Injecting it — rather than calling {@code Instant.now()} inline — makes the
 * decide-once gate a pure unit test with a fixed clock (see {@code docs/sagas.md}
 * → Timed releases). {@code @ConditionalOnMissingBean} lets a test slice or a
 * future shared clock bean override it without a collision.
 */
@Configuration
public class SalesClockConfig {

    @Bean
    @ConditionalOnMissingBean
    public InstantSource instantSource() {
        return InstantSource.system();
    }
}
