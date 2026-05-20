package com.northwood.shared.application.exception;

/**
 * Marker for {@link DomainException} subclasses that map to HTTP 409. Use
 * for receiver-state invariant violations — the resource exists, but its
 * current state doesn't permit the requested action (mutating a posted
 * invoice, cancelling a completed order, etc.).
 *
 * <p>Empty body — all common storage and {@link #code()} live on
 * {@link AbstractDomainException}; this subclass exists only so the
 * shared {@code DomainExceptionAdvice} can route via a typed
 * {@code @ExceptionHandler(ConflictException.class)} method.
 */
public abstract class ConflictException extends AbstractDomainException {

    protected ConflictException(String code, String message) {
        super(code, message);
    }

    protected ConflictException(String code, String message, Throwable cause) {
        super(code, message, cause);
    }
}
