package com.bank.compliance.infrastructure.outbox;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Relays committed outbox rows to Kafka in insertion order.
 *
 * One replica relays at a time (Postgres advisory lock), so the service can
 * scale out without reordering events; consumers de-duplicate on eventId,
 * which makes the at-least-once delivery safe.
 *
 * Failure handling follows ADR-021 decision 4. A failure caused by the row's
 * own payload (RecordTooLargeException, SerializationException,
 * InvalidTopicException) parks that row (parked_at, attempts, last_error) at
 * once and the batch moves on. Every other failure (retriable errors and
 * timeouts, authentication and authorisation errors, an unclassified
 * KafkaException, any other exception) is about the producer or the cluster:
 * it stops the batch without marking the row or anything after it, is
 * logged and counted (outbox.send.failures), and is retried with an
 * in-memory exponential backoff, however long it lasts. Such rows are never
 * parked; the outbox.oldest.pending.age.seconds gauge (from created_at) is
 * the alert signal. From the maxAttempts-th consecutive stopped run on the
 * failure is logged at ERROR instead of WARN. Parked rows are skipped,
 * counted once by the outbox.parked.events counter (an operator park as
 * OperatorPark), shown by the outbox.parked.rows gauge and replayed by hand (runbook).
 * Each screening aggregate has one event, so skipping a parked row reorders
 * no aggregate's events.
 */
public class OutboxRelay {

    // Distinct from the other services' keys ("cus_out"...) in case a database is ever shared.
    static final long RELAY_LOCK_KEY = 0x636D705F6F7574L; // "cmp_out"
    static final String SEND_FAILURES = "outbox.send.failures";
    /** Counted once per parked row (Prometheus outbox_parked_events_total); tag exception. */
    static final String PARKED_EVENTS = "outbox.parked.events";
    static final String OPERATOR_PARK = "OperatorPark";
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final SpringDataOutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final int batchSize;
    private final Duration sendTimeout;
    private final Duration retention;
    private final int maxAttempts;
    private final Duration backoffInitial;
    private final Duration backoffMax;
    private final MeterRegistry meters;
    // Backoff state of this relay instance; relayOnce runs on one scheduler thread.
    private int consecutiveStops;
    private Instant nextAttemptAt;

    public OutboxRelay(SpringDataOutboxRepository outbox, KafkaTemplate<String, String> kafka,
                       TransactionTemplate transactions, Clock clock, int batchSize,
                       Duration sendTimeout, Duration retention, int maxAttempts,
                       Duration backoffInitial, Duration backoffMax, MeterRegistry meters) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        this.outbox = outbox;
        this.kafka = kafka;
        this.transactions = transactions;
        this.clock = clock;
        this.batchSize = batchSize;
        this.sendTimeout = sendTimeout;
        this.retention = retention;
        this.maxAttempts = maxAttempts;
        this.backoffInitial = positive(backoffInitial, "backoffInitial");
        this.backoffMax = positive(backoffMax, "backoffMax");
        if (backoffMax.compareTo(backoffInitial) < 0) {
            throw new IllegalArgumentException("backoffMax must not be shorter than backoffInitial");
        }
        this.meters = java.util.Objects.requireNonNull(meters, "meters are required");
    }

    private static Duration positive(Duration value, String name) {
        java.util.Objects.requireNonNull(value, name + " is required");
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    /**
     * After a run that stopped on a failure, the next run waits
     * backoffInitial, doubling per consecutive stopped run up to backoffMax;
     * a run that does not stop resets it. While waiting, a call returns 0
     * without touching the database.
     *
     * @return number of events published in this run
     */
    public int relayOnce() {
        if (nextAttemptAt != null && clock.instant().isBefore(nextAttemptAt)) {
            return 0;
        }
        boolean[] stopped = {false};
        // Counted after commit, so a rolled-back run never counts a park.
        List<String> parks = new java.util.ArrayList<>();
        Integer published = transactions.execute(status -> {
            if (!outbox.tryRelayLock(RELAY_LOCK_KEY)) {
                return 0;
            }
            for (int i = outbox.markUncountedParksCounted(); i > 0; i--) {
                parks.add(OPERATOR_PARK);
            }
            List<OutboxEventJpaEntity> batch = outbox.findUnpublishedBatch(batchSize);
            int sent = 0;
            for (OutboxEventJpaEntity row : batch) {
                try {
                    kafka.send(toRecord(row)).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
                    row.markPublished(clock.instant());
                    sent++;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    countFailure(e);
                    logStopped(row, e);
                    stopped[0] = true;
                    break;
                } catch (Exception e) {
                    Throwable cause = unwrap(e);
                    countFailure(cause);
                    if (!isPoison(cause)) {
                        // ADR-021 decision 4: not about the payload. Mark nothing, stop, back off, retry.
                        logStopped(row, e);
                        stopped[0] = true;
                        break;
                    }
                    row.markFailed(describe(cause));
                    row.markParked(clock.instant());
                    parks.add(cause.getClass().getSimpleName());
                    log.error("Outbox relay parked event {} for {}: {}; replay it by hand (runbook)",
                        row.getEventId(), row.getTopic(), row.getLastError(), e);
                }
            }
            return sent;
        });
        for (String reason : parks) {
            meters.counter(PARKED_EVENTS, "exception", reason).increment();
        }
        if (stopped[0]) {
            consecutiveStops++;
            nextAttemptAt = clock.instant().plus(backoff(consecutiveStops));
        } else {
            consecutiveStops = 0;
            nextAttemptAt = null;
        }
        return published == null ? 0 : published;
    }

    /** The row is untouched; the event id and topic identify it (never customer or transaction ids). */
    private void logStopped(OutboxEventJpaEntity row, Exception e) {
        if (consecutiveStops + 1 >= maxAttempts) {
            log.error("Outbox relay still cannot publish event {} to {} ({} consecutive stopped runs); will retry",
                row.getEventId(), row.getTopic(), consecutiveStops + 1, e);
        } else {
            log.warn("Outbox relay could not publish event {} to {}; will retry", row.getEventId(), row.getTopic(), e);
        }
    }

    private Duration backoff(int stops) {
        if (stops - 1 >= 30) {
            return backoffMax;
        }
        Duration delay = backoffInitial.multipliedBy(1L << (stops - 1));
        return delay.compareTo(backoffMax) > 0 ? backoffMax : delay;
    }

    /** Alert signal: failed sends by exception class (simple name only, never ids or messages). */
    private void countFailure(Throwable cause) {
        meters.counter(SEND_FAILURES, "exception", cause.getClass().getSimpleName()).increment();
    }

    public int purgePublished() {
        Integer deleted = transactions.execute(status -> outbox.deletePublishedBefore(clock.instant().minus(retention)));
        return deleted == null ? 0 : deleted;
    }

    /**
     * The exception that says what went wrong: the most specific Kafka client
     * exception in the chain (Spring wraps it in KafkaProducerException, the
     * future in ExecutionException), or the relay's own TimeoutException.
     */
    static Throwable unwrap(Throwable e) {
        Throwable generic = null;
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof TimeoutException) {
                return t;
            }
            if (t instanceof KafkaException) {
                if (t.getClass() != KafkaException.class) {
                    return t;
                }
                generic = generic == null ? t : generic;
            }
        }
        if (generic != null) {
            return generic;
        }
        return e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
    }

    /** Failures caused by the row's own payload or topic: retrying it can never succeed. */
    private static boolean isPoison(Throwable cause) {
        return cause instanceof org.apache.kafka.common.errors.RecordTooLargeException
            || cause instanceof org.apache.kafka.common.errors.SerializationException
            || cause instanceof org.apache.kafka.common.errors.InvalidTopicException;
    }

    private static String describe(Throwable cause) {
        return cause.getMessage() == null
            ? cause.getClass().getSimpleName()
            : cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }

    static ProducerRecord<String, String> toRecord(OutboxEventJpaEntity row) {
        ProducerRecord<String, String> record = new ProducerRecord<>(row.getTopic(), row.getAggregateId(), row.getPayload());
        record.headers().add("eventType", row.getEventType().getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventId", row.getEventId().toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add("correlationId", row.getCorrelationId().getBytes(StandardCharsets.UTF_8));
        if (row.getTraceparent() != null) {
            record.headers().add("traceparent", row.getTraceparent().getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }
}
