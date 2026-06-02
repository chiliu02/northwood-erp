package com.northwood.shared.infrastructure.observability;

import io.micrometer.observation.ObservationPredicate;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.http.server.observation.ServerRequestObservationContext;

/**
 * Trims observation/trace noise that isn't service business flow, so a
 * trace in Tempo reflects a service request, not infrastructure chatter.
 *
 * <p>Drops the HTTP-server observation (span <em>and</em> metric) for any
 * {@code /actuator/**} request — i.e. Prometheus scraping
 * {@code /actuator/prometheus} every few seconds, which otherwise floods Tempo
 * with {@code http get /actuator/prometheus} root spans. Spring Boot's
 * {@code ObservationAutoConfiguration} feeds every {@link ObservationPredicate}
 * bean into the {@code ObservationRegistry}, so publishing this bean is all the
 * wiring required.
 *
 * <p>Companion levers (in {@code application.yml}, not here):
 * {@code management.observations.enable.spring.security=false} drops the Spring
 * Security filter-chain / authentication / authorization observation spans, and
 * the two BFFs run at {@code management.tracing.sampling.probability=0} (plus
 * their {@code ProxyController} no longer propagates trace context upstream) so
 * only the backend services emit spans and each service roots its own trace.
 */
@AutoConfiguration
@ConditionalOnClass({ ObservationPredicate.class, ServerRequestObservationContext.class })
public class ObservabilityAutoConfiguration {

    @Bean
    public ObservationPredicate skipActuatorServerObservations() {
        return (name, context) -> {
            if (context instanceof ServerRequestObservationContext serverContext) {
                String uri = serverContext.getCarrier().getRequestURI();
                return uri == null || !uri.startsWith("/actuator");
            }
            return true;
        };
    }
}
