package com.northwood.shared.application.messaging;

import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;

/**
 * W3C {@code traceparent} helpers shared by the outbox publish path: format the
 * active span as a {@code 00-<traceId>-<spanId>-<flags>} string, and parse a
 * stored one back into a Micrometer {@link TraceContext} (for reparenting /
 * linking in {@code OutboxDrainer}, §1D.6).
 *
 * <p>Append-side capture lives in {@link OutboxTraceHeaders} instead — it reads
 * the OTel current span statically so it needs no {@link Tracer} injected into
 * the 17 {@code Jdbc*Repository} emitters.
 */
public final class Traceparent {

    private Traceparent() {}

    /** The current span's traceparent, or {@code null} when no valid span is active. */
    public static String current(Tracer tracer) {
        if (tracer == null) {
            return null;
        }
        var span = tracer.currentSpan();
        return span == null ? null : format(span.context());
    }

    /** Format a {@link TraceContext} as a W3C traceparent, or {@code null} if incomplete. */
    public static String format(TraceContext ctx) {
        if (ctx == null || ctx.traceId() == null || ctx.spanId() == null) {
            return null;
        }
        String flags = Boolean.TRUE.equals(ctx.sampled()) ? "01" : "00";
        return "00-" + ctx.traceId() + "-" + ctx.spanId() + "-" + flags;
    }

    /**
     * Rebuild a {@link TraceContext} from a stored W3C traceparent so a span can
     * be reparented onto / linked to it. Returns {@code null} when the string is
     * absent or malformed (the drainer then falls back to OFF behaviour for that
     * row).
     */
    public static TraceContext parse(Tracer tracer, String traceparent) {
        if (tracer == null || traceparent == null) {
            return null;
        }
        String[] parts = traceparent.split("-");
        if (parts.length < 4 || parts[1].isBlank() || parts[2].isBlank()) {
            return null;
        }
        return tracer.traceContextBuilder()
            .traceId(parts[1])
            .spanId(parts[2])
            .sampled(!"00".equals(parts[3]))
            .build();
    }
}
