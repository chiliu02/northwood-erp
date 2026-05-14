package com.northwood.shared.api.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * Allow only callers with the {@code accountant} Keycloak realm role.
 * Meta-annotation over {@link PreAuthorize}; the role string lives in exactly
 * one place so rename / find-usages / typo-detection all work from here.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("hasRole('accountant')")
public @interface RequireAccountant {}
