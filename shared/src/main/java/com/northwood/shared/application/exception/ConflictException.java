package com.northwood.shared.application.exception;

/**
 * Abstract base for {@link DomainException} subclasses that map to
 * HTTP 409. Use for receiver-state invariant violations — the resource
 * exists, but its current state doesn't permit the requested action
 * (mutating a posted invoice, cancelling a completed order, etc.).
 *
 * <p>Concrete subclasses live alongside their application service and
 * carry typed accessors for the current state (status enum, version,
 * etc.) plus the {@code code()} / {@code params()} contract.
 */
public abstract class ConflictException extends RuntimeException implements DomainException {

    protected ConflictException(String message) {
        super(message);
    }

    protected ConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
