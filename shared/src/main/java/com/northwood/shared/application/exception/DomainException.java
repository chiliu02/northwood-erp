package com.northwood.shared.application.exception;

import java.util.Map;

/**
 * Marker for application-layer exceptions that surface as typed HTTP error
 * responses. The shared {@code DomainExceptionAdvice} (in
 * {@code shared.api.exception}) catches any {@code DomainException} thrown
 * from a controller and emits an {@code ErrorResponse { code, params }}
 * JSON body so SPA clients can localise / re-render without parsing the
 * exception's English message.
 *
 * <p>Concrete exceptions implement {@link #code()} (a stable wire-format
 * uppercase-snake-case identifier exposed as a {@code public static final
 * String CODE} on the concrete class — e.g. {@code CustomerNotFoundException.CODE
 * = "CUSTOMER_NOT_FOUND"}) and
 * {@link #params()} (the typed context the consumer needs to render a full
 * message — customer id, expected status, etc.). The exception's English
 * {@link Exception#getMessage()} stays useful for logs and stack traces; it
 * is no longer the wire-format response body.
 *
 * <p>HTTP status mapping comes from the abstract base the concrete exception
 * extends: {@link NotFoundException} → 404, {@link ConflictException} → 409,
 * {@link BadRequestException} → 400. This keeps application-layer code free
 * of Spring's {@code HttpStatus} type while letting the shared advice route
 * cleanly via {@code switch (e)} on the base type.
 *
 * <p>The convention's three exception-wrapping flavours
 * (see {@code docs/conventions.md} → <i>Exception wrapping</i>) all
 * implement this interface on their application-layer wrapper class so
 * controllers consistently catch a {@code DomainException} and never a
 * domain-layer or infrastructure-layer exception.
 */
public interface DomainException {

    /**
     * Stable wire-format identifier for this error condition. Concrete
     * subclasses expose this as a {@code public static final String CODE}
     * field (uppercase snake case, e.g.
     * {@code CustomerNotFoundException.CODE = "CUSTOMER_NOT_FOUND"}) and pass
     * it through {@code super(CODE, ...)} so the literal is declared exactly
     * once. {@link AbstractDomainException} stores it and this accessor
     * returns it. Used as the lookup key in SPA message bundles; never changes
     * once an exception class ships.
     */
    String code();

    /**
     * Typed context for the consumer to substitute into a localised message.
     * Returns an empty map when there's no parameterised content. Keys are
     * stable identifiers; values should be JSON-serialisable (Strings, UUIDs,
     * numbers, booleans, simple records/maps) — Jackson serialises them
     * straight into the {@code ErrorResponse.params} field.
     */
    Map<String, Object> params();
}
