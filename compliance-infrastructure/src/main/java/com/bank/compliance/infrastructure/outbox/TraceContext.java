package com.bank.compliance.infrastructure.outbox;

import org.slf4j.MDC;

import java.util.regex.Pattern;

/**
 * W3C trace context of the request that wrote an outbox row, so the relay can
 * send it as the {@code traceparent} header and traces cross the broker.
 *
 * Read from the logging MDC ({@code traceId}, {@code spanId}), where the
 * Micrometer tracing bridge puts the current span. When there is no valid
 * trace context (no tracing on the classpath, or the row is written outside a
 * traced request) nothing is stored and the header is omitted. The span is
 * recorded, so the sampled flag is set.
 */
final class TraceContext {

    static final String TRACE_ID_KEY = "traceId";
    static final String SPAN_ID_KEY = "spanId";
    private static final Pattern TRACE_ID = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern SPAN_ID = Pattern.compile("[0-9a-f]{16}");

    private TraceContext() {
    }

    static String currentTraceparent() {
        return traceparent(MDC.get(TRACE_ID_KEY), MDC.get(SPAN_ID_KEY));
    }

    static String traceparent(String traceId, String spanId) {
        if (traceId == null || spanId == null
                || !TRACE_ID.matcher(traceId).matches() || !SPAN_ID.matcher(spanId).matches()
                || traceId.chars().allMatch(c -> c == '0') || spanId.chars().allMatch(c -> c == '0')) {
            return null;
        }
        return "00-" + traceId + "-" + spanId + "-01";
    }
}
