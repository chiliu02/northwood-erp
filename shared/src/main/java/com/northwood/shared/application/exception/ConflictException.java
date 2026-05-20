package com.northwood.shared.application.exception;

/**
 * Abstract base for {@link DomainException} subclasses that map to
 * HTTP 409. Use for receiver-state invariant violations — the resource
 * exists, but its current state doesn't permit the requested action
 * (mutating a posted invoice, cancelling a completed order, etc.).
 *
 * <p>Concrete subclasses live alongside their application service and
 * carry typed accessors for the current state (status enum, version,
 * etc.) plus the {@link #params()} implementation. The {@code code}
 * field is set once by the constructor and exposed via the final
 * {@link #code()} accessor so subclasses don't repeat the same
 * {@code @Override} boilerplate.
 */
public abstract class ConflictException extends RuntimeException implements DomainException {

    private final String code;

    protected ConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    protected ConflictException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    @Override
    public final String code() {
        return code;
    }
}
