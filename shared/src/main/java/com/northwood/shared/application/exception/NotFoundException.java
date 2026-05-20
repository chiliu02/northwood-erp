package com.northwood.shared.application.exception;

/**
 * Abstract base for {@link DomainException} subclasses that map to
 * HTTP 404. Use for "no such id / code" lookups where the resource the
 * caller named doesn't exist (or no longer exists).
 *
 * <p>Concrete subclasses live alongside their application service and
 * carry typed accessors for the lookup key (customer code, order id,
 * etc.) plus the {@code code()} / {@code params()} contract.
 */
public abstract class NotFoundException extends RuntimeException implements DomainException {

    protected NotFoundException(String message) {
        super(message);
    }

    protected NotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
