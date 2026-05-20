package com.northwood.shared.application.exception;

/**
 * Marker for {@link DomainException} subclasses that map to HTTP 404. Use
 * for "no such id / code" lookups where the resource the caller named
 * doesn't exist (or no longer exists).
 *
 * <p>Empty body — all common storage and {@link #code()} live on
 * {@link AbstractDomainException}; this subclass exists only so the
 * shared {@code DomainExceptionAdvice} can route via a typed
 * {@code @ExceptionHandler(NotFoundException.class)} method.
 */
public abstract class NotFoundException extends AbstractDomainException {

    protected NotFoundException(String code, String message) {
        super(code, message);
    }

    protected NotFoundException(String code, String message, Throwable cause) {
        super(code, message, cause);
    }
}
