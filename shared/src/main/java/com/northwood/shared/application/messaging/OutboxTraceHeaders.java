package com.northwood.shared.application.messaging;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;

/**
 * Captures the current trace context as an {@code outbox_message.headers} JSON
 * value at append time, so {@code OutboxDrainer} can reparent / link the async
 * saga continuation onto the originating request trace later (§1D.6, see
 * {@link SagaTraceLinkage}).
 *
 * <p><b>Why the raw OTel API rather than a Micrometer {@code Tracer}.</b> The
 * append sites are the 17 {@code Jdbc*Repository.writeOutbox} emitters (plus
 * {@code OutboxAppender}); injecting a {@code Tracer} into each would mean 17
 * constructor changes and breaking as many persistence-IT construction sites.
 * {@link io.opentelemetry.api.trace.Span#current()} reads the active span from
 * the ambient OTel context statically — the OTel bridge is always present
 * (shared depends on {@code spring-boot-starter-opentelemetry}) — so capture is
 * a one-line call with no wiring. The string format matches
 * {@link Traceparent#format} so the drainer parses either side identically.
 */
public final class OutboxTraceHeaders {

    private OutboxTraceHeaders() {}

    /**
     * The current trace context as a headers JSON object
     * ({@code {"traceparent":"00-…"}}), or {@code "{}"} when no valid span is
     * active (e.g. a write outside any traced request). Always returns a valid
     * JSONB literal so callers can pass it straight into the {@code headers}
     * column without null handling.
     */
    public static String currentJson() {
        SpanContext sc = Span.current().getSpanContext();
        if (!sc.isValid()) {
            return "{}";
        }
        String traceparent = "00-" + sc.getTraceId() + "-" + sc.getSpanId() + "-" + sc.getTraceFlags().asHex();
        return "{\"" + EventEnvelope.HEADER_TRACEPARENT + "\":\"" + traceparent + "\"}";
    }
}
