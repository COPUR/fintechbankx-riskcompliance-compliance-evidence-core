package com.bank.compliance.infrastructure.outbox;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.errors.RetriableException;
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
 * non-retriable failure (RecordTooLargeException, SerializationException,
 * InvalidTopicException, TopicAuthorizationException, anything not
 * retriable) parks it (parked_at) at once and the batch moves on. A
 * retriable failure parks a row only once it has kept failing for longer
 * than retryableParkAfter since its first failure (first_failed_at), so a
 * broker or egress outage delays events but parks none of them. From the
 * maxAttempts-th failed send on, a retriable failure is logged at ERROR
 * instead of WARN; the count never parks a row. Parked rows are skipped, counted by the
 * outbox.parked.events gauge and replayed by hand (runbook). Each screening
 * aggregate has one event, so skipping a parked row reorders no aggregate's
 * events.
 */
public class OutboxRelay {

    // Distinct from the other services' keys ("cus_out"...) in case a database is ever shared.
    static final long RELAY_LOCK_KEY = 0x636D705F6F7574L; // "cmp_out"
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

    public OutboxRelay(SpringDataOutboxRepository outbox, KafkaTemplate<String, String> kafka,
                       TransactionTemplate transactions, Clock clock, int batchSize,
                       Duration sendTimeout, Duration retention, int maxAttempts, Duration retryableParkAfter) {
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
    }

    /**
     * @return number of events published in this run
     */
    public int relayOnce() {
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
                    break;
                } catch (Exception e) {
                    Throwable cause = unwrap(e);
                    Instant now = clock.instant();
                    row.markFailed(describe(cause), now);
                    boolean pastCeiling = Duration.between(row.getFirstFailedAt(), now).compareTo(retryableParkAfter) > 0;
                    if (isRetriable(cause) && !pastCeiling) {
                        if (row.getAttempts() >= maxAttempts) {
                            log.error("Outbox relay still cannot publish event {} to {} (attempt {}, failing since {}); will retry",
                                row.getEventId(), row.getTopic(), row.getAttempts(), row.getFirstFailedAt(), e);
                        } else {
                            log.warn("Outbox relay could not publish event {} to {} (attempt {}); will retry",
                                row.getEventId(), row.getTopic(), row.getAttempts(), e);
                        }
                        break;
                    }
                    row.markParked(clock.instant());
                    log.error("Outbox relay parked event {} for {} after {} attempt(s): {}; replay it by hand (runbook)",
                        row.getEventId(), row.getTopic(), row.getAttempts(), row.getLastError(), e);
                }
            }
            return sent;
        });
        return published == null ? 0 : published;
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

    private static boolean isRetriable(Throwable cause) {
        return cause instanceof RetriableException || cause instanceof TimeoutException;
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
