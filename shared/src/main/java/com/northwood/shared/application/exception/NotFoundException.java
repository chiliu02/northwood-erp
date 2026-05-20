package com.northwood.shared.application.exception;

/**
 * Abstract base for {@link DomainException} subclasses that map to
 * HTTP 404. Use for "no such id / code" lookups where the resource the
 * caller named doesn't exist (or no longer exists).
 *
 * <p>Concrete subclasses live alongside their application service and
 * carry typed accessors for the lookup key (customer code, order id,
 * etc.) plus the {@link #params()} implementation. The {@code code}
 * field is set once by the constructor and exposed via the final
 * {@link #code()} accessor so subclasses don't repeat the same
 * {@code @Override} boilerplate.
 */
public abstract class NotFoundException extends RuntimeException implements DomainException {

    private final String code;

    protected NotFoundException(String code, String message) {
        super(message);
        this.code = code;
    }

    protected NotFoundException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    @Override
    public final String code() {
        return code;
    }
}
