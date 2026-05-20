package com.northwood.shared.application.exception;

/**
 * Abstract base for {@link DomainException} subclasses that map to
 * HTTP 400. Use for argument-contract violations — the input the caller
 * supplied is malformed or inconsistent in a way the caller can correct
 * by retrying with different input.
 *
 * <p>Concrete subclasses live alongside their application service and
 * carry typed accessors for the offending input (the supplied currency
 * code, the SKU with no catalogue price, etc.) plus the {@code code()} /
 * {@code params()} contract.
 *
 * <p><b>vs. {@code Assert.argument(...)}.</b> The {@link com.northwood.shared.domain.Assert}
 * helpers throw {@link IllegalArgumentException} for argument-contract
 * violations that don't merit a dedicated exception class — short-lived
 * input checks at method entry. When a violation surfaces to the wire and
 * the SPA needs to render different UX per cause (e.g. "currency mismatch"
 * vs "no catalogue price"), promote it to a {@code BadRequestException}
 * subclass with its own {@code CODE} so the SPA can dispatch off the code.
 */
public abstract class BadRequestException extends RuntimeException implements DomainException {

    protected BadRequestException(String message) {
        super(message);
    }

    protected BadRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}
