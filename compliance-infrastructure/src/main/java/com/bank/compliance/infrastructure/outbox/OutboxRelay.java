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
 * scale out without reordering events. A retriable failure (Kafka's
 * RetriableException, including its TimeoutException, or the relay's own send
 * timeout) stops the batch and is retried on the next run; consumers
 * de-duplicate on eventId, which makes the at-least-once delivery safe.
 *
 * A row that can never be sent does not hold back every later event: a
 * failure caused by the row itself (RecordTooLargeException,
 * SerializationException, InvalidTopicException) parks it (parked_at) at
 * once and the batch moves on. Every other failure is about the producer or
 * the cluster, not the row: retriable errors and timeouts, authentication
 * and authorisation errors (an IRSA/STS hiccup, an ACL or IAM rollout), an
 * unclassified KafkaException or any other exception. It stops the batch and
 * parks the row only once it has kept failing for longer than
 * retryableParkAfter since its first failure (first_failed_at), so an outage
 * or a credentials problem delays events but parks at most one row per tick. From the
 * maxAttempts-th failed send on, a retriable failure is logged at ERROR
 * instead of WARN; the count never parks a row. Parked rows are skipped, counted by the
 * outbox.parked.events gauge and replayed by hand (runbook). Each screening
 * aggregate has one event, so skipping a parked row reorders no aggregate's
 * events.
 */
public class OutboxRelay {

    // Distinct from the other services' keys ("cus_out"...) in case a database is ever shared.
    static final long RELAY_LOCK_KEY = 0x636D705F6F7574L; // "cmp_out"
    static final String SEND_FAILURES = "outbox.send.failures";
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final SpringDataOutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final int batchSize;
    private final Duration sendTimeout;
    private final Duration retention;
    private final int maxAttempts;
    private final Duration retryableParkAfter;
    private final Duration backoffInitial;
    private final Duration backoffMax;
    private final MeterRegistry meters;
    // Backoff state of this relay instance; relayOnce runs on one scheduler thread.
    private int consecutiveStops;
    private Instant nextAttemptAt;

    public OutboxRelay(SpringDataOutboxRepository outbox, KafkaTemplate<String, String> kafka,
                       TransactionTemplate transactions, Clock clock, int batchSize,
                       Duration sendTimeout, Duration retention, int maxAttempts, Duration retryableParkAfter,
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
        this.retryableParkAfter = java.util.Objects.requireNonNull(retryableParkAfter, "retryableParkAfter is required");
        if (retryableParkAfter.isNegative() || retryableParkAfter.isZero()) {
            throw new IllegalArgumentException("retryableParkAfter must be positive");
        }
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
        Integer published = transactions.execute(status -> {
            if (!outbox.tryRelayLock(RELAY_LOCK_KEY)) {
                return 0;
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
                    row.markFailed("interrupted", clock.instant());
                    countFailure(e);
                    stopped[0] = true;
                    break;
                } catch (Exception e) {
                    Throwable cause = unwrap(e);
                    Instant now = clock.instant();
                    row.markFailed(describe(cause), now);
                    countFailure(cause);
                    boolean pastCeiling = Duration.between(row.getFirstFailedAt(), now).compareTo(retryableParkAfter) > 0;
                    if (!isPoison(cause) && !pastCeiling) {
                        if (row.getAttempts() >= maxAttempts) {
                            log.error("Outbox relay still cannot publish event {} to {} (attempt {}, failing since {}); will retry",
                                row.getEventId(), row.getTopic(), row.getAttempts(), row.getFirstFailedAt(), e);
                        } else {
                            log.warn("Outbox relay could not publish event {} to {} (attempt {}); will retry",
                                row.getEventId(), row.getTopic(), row.getAttempts(), e);
                        }
                        stopped[0] = true;
                        break;
                    }
                    row.markParked(clock.instant());
                    log.error("Outbox relay parked event {} for {} after {} attempt(s): {}; replay it by hand (runbook)",
                        row.getEventId(), row.getTopic(), row.getAttempts(), row.getLastError(), e);
                }
            }
            return sent;
        });
        if (stopped[0]) {
            consecutiveStops++;
            nextAttemptAt = clock.instant().plus(backoff(consecutiveStops));
        } else {
            consecutiveStops = 0;
            nextAttemptAt = null;
        }
        return published == null ? 0 : published;
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
        record.headers().add("x-fapi-interaction-id", row.getCorrelationId().getBytes(StandardCharsets.UTF_8));
        if (row.getTraceparent() != null) {
            record.headers().add("traceparent", row.getTraceparent().getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }
}
