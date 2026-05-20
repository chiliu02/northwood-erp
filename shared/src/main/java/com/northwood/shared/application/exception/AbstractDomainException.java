package com.northwood.shared.application.exception;

/**
 * Common base for every concrete {@link DomainException}. Stores the
 * wire-format {@code code} once and exposes it via a {@code final}
 * {@link #code()} accessor — concrete classes don't have to override
 * {@code code()} themselves.
 *
 * <p>HTTP-status routing is driven by which of the three thin marker
 * subclasses the concrete exception extends:
 *
 * <ul>
 *   <li>{@link NotFoundException} — HTTP 404</li>
 *   <li>{@link ConflictException} — HTTP 409</li>
 *   <li>{@link BadRequestException} — HTTP 400</li>
 * </ul>
 *
 * The shared {@code DomainExceptionAdvice} has one
 * {@code @ExceptionHandler} per marker; Spring's nearest-supertype match
 * routes concrete exceptions to the right status without the advice
 * needing to know about the concrete types.
 *
 * <p>Subclasses of this class directly are not expected — always extend
 * one of the three markers so the HTTP status is decided at class-shape
 * time, not at the call site.
 */
public abstract class AbstractDomainException extends RuntimeException implements DomainException {

    private final String code;

    protected AbstractDomainException(String code, String message) {
        super(message);
        this.code = code;
    }

    protected AbstractDomainException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    @Override
    public final String code() {
        return code;
    }
}
