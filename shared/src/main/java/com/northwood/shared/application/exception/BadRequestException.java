package com.northwood.shared.application.exception;

/**
 * Marker for {@link DomainException} subclasses that map to HTTP 400. Use
 * for argument-contract violations — the input the caller supplied is
 * malformed or inconsistent in a way the caller can correct by retrying
 * with different input.
 *
 * <p>Empty body — all common storage and {@link #code()} live on
 * {@link AbstractDomainException}; this subclass exists only so the
 * shared {@code DomainExceptionAdvice} can route via a typed
 * {@code @ExceptionHandler(BadRequestException.class)} method.
 *
 * <p><b>vs. {@code Assert.argument(...)}.</b> The {@link com.northwood.shared.domain.Assert}
 * helpers throw {@link IllegalArgumentException} for argument-contract
 * violations that don't merit a dedicated exception class — short-lived
 * input checks at method entry. When a violation surfaces to the wire and
 * the SPA needs to render different UX per cause (e.g. "currency mismatch"
 * vs "no catalogue price"), promote it to a {@code BadRequestException}
 * subclass with its own wire-format code so the SPA can dispatch off it.
 */
public abstract class BadRequestException extends AbstractDomainException {

    protected BadRequestException(String code, String message) {
        super(code, message);
    }

    protected BadRequestException(String code, String message, Throwable cause) {
        super(code, message, cause);
    }
}
