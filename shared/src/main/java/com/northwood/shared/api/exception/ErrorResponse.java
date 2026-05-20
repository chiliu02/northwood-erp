package com.northwood.shared.api.exception;

import java.util.Map;

/**
 * Wire format for every 4xx error response. The SPA (or any other client)
 * looks up the {@code code} in its message catalogue and renders the
 * localised message with {@code params} substituted.
 *
 * <p>Replaces the older per-controller {@code ResponseEntity<String>}
 * shape (English exception messages leaking to the wire). Lives in
 * {@code shared.api.exception} because every service's
 * {@code @ExceptionHandler} now returns this type via the shared
 * {@code DomainExceptionAdvice}.
 *
 * <p>{@code params} is typed {@code Map<String, Object>} so a single
 * record handles the per-field heterogeneity (some codes carry a UUID,
 * some a status enum, some a numeric quantity). Values must be
 * JSON-serialisable; Jackson 3 handles the common cases (Strings, UUIDs,
 * Numbers, Booleans, nested records) without extra config.
 */
public record ErrorResponse(String code, Map<String, Object> params) {

    public ErrorResponse {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
        if (params == null) {
            params = Map.of();
        }
    }

    /** Convenience factory for codes that carry no parameters. */
    public static ErrorResponse of(String code) {
        return new ErrorResponse(code, Map.of());
    }

    /** Convenience factory mirroring the {@code (code, params)} constructor. */
    public static ErrorResponse of(String code, Map<String, Object> params) {
        return new ErrorResponse(code, params);
    }
}
