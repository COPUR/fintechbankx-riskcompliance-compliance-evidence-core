package com.bank.compliance.infrastructure.outbox;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

class TraceContextTest {

    private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String SPAN = "00f067aa0ba902b7";

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void buildsAW3cTraceparentFromTheCurrentSpan() {
        MDC.put("traceId", TRACE);
        MDC.put("spanId", SPAN);

        assertThat(TraceContext.currentTraceparent()).isEqualTo("00-" + TRACE + "-" + SPAN + "-01");
    }

    @Test
    void absentOrInvalidContextGivesNothing() {
        assertThat(TraceContext.currentTraceparent()).isNull();
        assertThat(TraceContext.traceparent(TRACE, null)).isNull();
        assertThat(TraceContext.traceparent(null, SPAN)).isNull();
        assertThat(TraceContext.traceparent("4BF92F3577B34DA6A3CE929D0E0E4736", SPAN)).as("upper case").isNull();
        assertThat(TraceContext.traceparent(TRACE.substring(1), SPAN)).as("short trace id").isNull();
        assertThat(TraceContext.traceparent(TRACE, SPAN + "0")).as("long span id").isNull();
        assertThat(TraceContext.traceparent("0".repeat(32), SPAN)).as("invalid all-zero trace id").isNull();
        assertThat(TraceContext.traceparent(TRACE, "0".repeat(16))).as("invalid all-zero span id").isNull();
    }
}
