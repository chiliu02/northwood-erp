package com.northwood.shared.api.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * Allow only callers with the {@code warehouse_manager} Keycloak realm role.
 * Meta-annotation over {@link PreAuthorize}; the role string lives in exactly
 * one place so rename / find-usages / typo-detection all work from here.
 *
 * <p>First used by {@code StockAdjustmentController} when the warehouse-manager
 * realm role was introduced. The role is already defined in
 * {@code db/keycloak/northwood-realm.json}; this annotation is the only Java
 * piece that was missing.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("hasRole('warehouse_manager')")
public @interface RequireWarehouseManager {}
