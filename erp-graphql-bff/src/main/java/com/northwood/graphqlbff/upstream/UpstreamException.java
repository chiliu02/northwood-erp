package com.northwood.graphqlbff.upstream;

/**
 * Raised when an upstream REST call fails (non-2xx response, or transport
 * error). Carries the failing operation and, for HTTP failures, the upstream
 * status + body so the GraphQL error surfaces something actionable.
 */
public class UpstreamException extends RuntimeException {

    public UpstreamException(String operation, int status, String body) {
        super(operation + " -> upstream " + status + ": " + body);
    }

    public UpstreamException(String message, Throwable cause) {
        super(message, cause);
    }
}
