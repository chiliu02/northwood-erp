package com.northwood.shared.infrastructure.exception;

import com.northwood.shared.api.exception.DomainExceptionAdvice;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.RestController;

/**
 * Registers the shared {@link DomainExceptionAdvice} into every service
 * that has a web stack on its classpath. Mirrors the existing pattern in
 * {@code AuditAutoConfiguration} — instantiate the {@code @RestControllerAdvice}
 * bean here so each service picks it up without per-service wiring.
 *
 * <p>Conditional on {@link RestController} so test harnesses or any
 * future non-web module that depends on {@code shared} doesn't pull in
 * Spring MVC just to satisfy the bean wiring.
 *
 * <p>This is the {@code @AutoConfiguration → @RestControllerAdvice in
 * shared.api.*} layering exception documented in {@code CLAUDE.md}'s
 * hexagonal rules (same shape as
 * {@code AuditAutoConfiguration → shared.api.audit.AuditController}).
 */
@AutoConfiguration
@ConditionalOnClass(RestController.class)
public class DomainExceptionAutoConfiguration {

    @Bean
    public DomainExceptionAdvice domainExceptionAdvice() {
        return new DomainExceptionAdvice();
    }
}
