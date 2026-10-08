package com.bank.compliance.infrastructure.outbox;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.NotEnoughReplicasException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.errors.TopicAuthorizationException;
import org.apache.kafka.common.errors.SaslAuthenticationException;
import org.apache.kafka.common.errors.SerializationException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaProducerException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class OutboxRelayTest {

    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");

    private final SpringDataOutboxRepository outbox = mock(SpringDataOutboxRepository.class);
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private final TransactionTemplate transactions = inlineTransactions();
    private static final int MAX_ATTEMPTS = 10;
    private static final Duration PARK_AFTER = Duration.ofHours(24);
    private static final Duration BACKOFF_INITIAL = Duration.ofSeconds(1);
    private static final Duration BACKOFF_MAX = Duration.ofMinutes(5);
    private final MutableClock clock = new MutableClock(NOW);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final OutboxRelay relay = new OutboxRelay(outbox, kafka, transactions,
        clock, 50, Duration.ofSeconds(1), Duration.ofDays(7), MAX_ATTEMPTS, PARK_AFTER, BACKOFF_INITIAL, BACKOFF_MAX, meters);

    @Test
    void anotherReplicaHoldingTheLockMeansNothingIsSent() {
        when(outbox.tryRelayLock(anyLong())).thenReturn(false);

        assertThat(relay.relayOnce()).isZero();
        verify(outbox, never()).findUnpublishedBatch(50);
        verify(kafka, never()).send(any(ProducerRecord.class));
    }

    @Test
    void aRetriableFailureStopsTheBatchSoLaterEventsCannotOvertakeIt() {
        OutboxEventJpaEntity first = row("CMP-1");
        OutboxEventJpaEntity second = row("CMP-2");
        OutboxEventJpaEntity third = row("CMP-3");
        when(outbox.tryRelayLock(anyLong())).thenReturn(true);
        when(outbox.findUnpublishedBatch(50)).thenReturn(List.of(first, second, third));
        when(kafka.send(any(ProducerRecord.class)))
            .thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null))
            .thenReturn(CompletableFuture.failedFuture(new NotEnoughReplicasException("2 of 3 replicas in sync")));

        int sent = relay.relayOnce();

        assertThat(sent).isEqualTo(1);
        assertThat(first.getPublishedAt()).isEqualTo(NOW);
        assertThat(second.getPublishedAt()).isNull();
        assertThat(second.getParkedAt()).as("a retriable failure is retried, not parked").isNull();
        assertThat(second.getAttempts()).isEqualTo(1);
        assertThat(second.getLastError()).startsWith("NotEnoughReplicasException");
        assertThat(third.getAttempts()).isZero();
        verify(kafka, times(2)).send(any(ProducerRecord.class));
    }

    @Test
    void timeoutsAreRetriableWhetherKafkaOrTheRelayGivesUp() {
        // Kafka's own TimeoutException (metadata or delivery timeout, for example a missing topic).
        OutboxEventJpaEntity kafkaTimeout = row("CMP-1");
        when(outbox.tryRelayLock(anyLong())).thenReturn(true);
        when(outbox.findUnpublishedBatch(50)).thenReturn(List.of(kafkaTimeout, row("CMP-2")));
        when(kafka.send(any(ProducerRecord.class)))
            .thenReturn(CompletableFuture.failedFuture(new TimeoutException("Topic not present in metadata after 10000 ms")));

        assertThat(relay.relayOnce()).isZero();
        assertThat(kafkaTimeout.getParkedAt()).isNull();
        verify(kafka, times(1)).send(any(ProducerRecord.class));

        // The relay's own wait on the send future running out (after the backoff).
        clock.advance(BACKOFF_INITIAL);
        OutboxEventJpaEntity relayTimeout = row("CMP-3");
        when(outbox.findUnpublishedBatch(50)).thenReturn(List.of(relayTimeout, row("CMP-4")));
        when(kafka.send(any(ProducerRecord.class))).thenReturn(new CompletableFuture<>());

        assertThat(relay.relayOnce()).isZero();
        assertThat(relayTimeout.getParkedAt()).isNull();
        assertThat(relayTimeout.getLastError()).startsWith("TimeoutException");
        verify(kafka, times(2)).send(any(ProducerRecord.class));
    }

    @Test
    void aPermanentFailureParksTheRowAndTheNextRowIsPublished() {
        OutboxEventJpaEntity tooLarge = row("CMP-1");
        OutboxEventJpaEntity next = row("CMP-2");
        when(outbox.tryRelayLock(anyLong())).thenReturn(true);
        when(outbox.findUnpublishedBatch(50)).thenReturn(List.of(tooLarge, next));
        when(kafka.send(any(ProducerRecord.class)))
            .thenReturn(CompletableFuture.failedFuture(new KafkaProducerException(null, "Failed to send",
                new RecordTooLargeException("The message is 2000000 bytes"))))
            .thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));

        int sent = relay.relayOnce();

        assertThat(sent).isEqualTo(1);
        assertThat(tooLarge.getParkedAt()).isEqualTo(NOW);
        assertThat(tooLarge.getPublishedAt()).isNull();
        assertThat(tooLarge.getAttempts()).isEqualTo(1);
        assertThat(tooLarge.getLastError()).startsWith("RecordTooLargeException: The message is 2000000 bytes");
        assertThat(next.getPublishedAt()).isEqualTo(NOW);
        verify(kafka, times(2)).send(any(ProducerRecord.class));
    }

    @Test
    void aPoisonPayloadThrownBySendItselfParksThatRowAndTheNextRowIsPublished() {
        OutboxEventJpaEntity unserializable = row("CMP-1");
        OutboxEventJpaEntity next = row("CMP-2");
        when(outbox.tryRelayLock(anyLong())).thenReturn(true);
        when(outbox.findUnpublishedBatch(50)).thenReturn(List.of(unserializable, next));
        when(kafka.send(any(ProducerRecord.class)))
            .thenThrow(new SerializationException("cannot serialize"))
            .thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));

        assertThat(relay.relayOnce()).isEqualTo(1);

        assertThat(unserializable.getParkedAt()).isEqualTo(NOW);
        assertThat(next.getPublishedAt()).isEqualTo(NOW);
    }

    /**
     * An authentication or authorisation failure (an IRSA/STS hiccup, an ACL
     * or IAM-policy rollout) is about the producer, not the row: it stops the
     * batch like an outage and parks nothing before the time ceiling.
     */
    @Test
    void anAuthenticationFailureStopsTheBatchAndParksNothing() {
        assertStopsTheBatchWithoutParking(new SaslAuthenticationException("IAM credentials expired"));
    }

    @Test
    void anAuthorisationFailureStopsTheBatchAndParksNothing() {
        assertStopsTheBatchWithoutParking(new TopicAuthorizationException(Set.of("evt.cmp.compliance.screened.v1")));
    }

    @Test
    void anUnclassifiedKafkaFailureStopsTheBatchAndParksNothing() {
        assertStopsTheBatchWithoutParking(new org.apache.kafka.common.KafkaException("Failed to construct kafka producer"));
    }

    @Test
    void anyOtherExceptionStopsTheBatchAndParksNothing() {
        assertStopsTheBatchWithoutParking(new IllegalStateException("Cannot perform operation after producer has been closed"));
    }

    @Test
    void anUnknownTopicStopsTheBatchAndParksNothing() {
        assertStopsTheBatchWithoutParking(new org.apache.kafka.common.errors.UnknownTopicOrPartitionException("not yet created"));
    }

    @Test
    void anAuthorisationFailurePastTheCeilingParksTheRow() {
        OutboxEventJpaEntity stuck = row("CMP-1");
        stuck.markFailed("TopicAuthorizationException", NOW.minus(PARK_AFTER).minusSeconds(1));
        when(outbox.tryRelayLock(anyLong())).thenReturn(true);
        when(outbox.findUnpublishedBatch(50)).thenReturn(List.of(stuck));
        when(kafka.send(any(ProducerRecord.class)))
            .thenReturn(CompletableFuture.failedFuture(new TopicAuthorizationException(Set.of("evt.cmp.compliance.screened.v1"))));

        relay.relayOnce();

        assertThat(stuck.getParkedAt()).isEqualTo(NOW);
    }

    private void assertStopsTheBatchWithoutParking(Exception failure) {
        OutboxEventJpaEntity head = row("CMP-1");
        OutboxEventJpaEntity next = row("CMP-2");
        when(outbox.tryRelayLock(anyLong())).thenReturn(true);
        when(outbox.findUnpublishedBatch(50)).thenReturn(List.of(head, next));
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.failedFuture(failure));

        assertThat(relay.relayOnce()).isZero();

        assertThat(head.getParkedAt()).as("the failing row").isNull();
        assertThat(head.getAttempts()).isEqualTo(1);
        assertThat(head.getFirstFailedAt()).isEqualTo(NOW);
        assertThat(next.getParkedAt()).as("the next row").isNull();
        assertThat(next.getAttempts()).as("the next row is not tried").isZero();
        verify(kafka, times(1)).send(any(ProducerRecord.class));
    }

    /**
     * ADR-021 decision 4: a failure that is not about the payload never
     * parks or skips a row, however long it lasts.
     */
    @Test
    void twentyRetriableFailuresInARowDoNotParkTheRow() {
        OutboxEventJpaEntity head = row("CMP-1");
        when(outbox.tryRelayLock(anyLong())).thenReturn(true);
        when(outbox.findUnpublishedBatch(50)).thenReturn(List.of(head, row("CMP-2")));
        when(kafka.send(any(ProducerRecord.class)))
            .thenAnswer(call -> CompletableFuture.failedFuture(new NotEnoughReplicasException("broker unavailable")));

        for (int tick = 0; tick < 20; tick++) {
            assertThat(relay.relayOnce()).isZero();
            clock.advance(BACKOFF_MAX);
        }

        assertThat(head.getAttempts()).isEqualTo(20).isGreaterThan(MAX_ATTEMPTS);
        assertThat(head.getParkedAt()).isNull();
        verify(kafka, times(20)).send(any(ProducerRecord.class));
    }

    /** After a stopped batch the relay waits 1 s, 2 s, 4 s ... up to 5 min, and resets on success. */
    @Test
    void aStoppedBatchBacksOffExponentiallyUpToTheCapAndResetsOnSuccess() {
        OutboxEventJpaEntity head = row("CMP-1");
        when(outbox.tryRelayLock(anyLong())).thenReturn(true);
        when(outbox.findUnpublishedBatch(50)).thenReturn(List.of(head));
        when(kafka.send(any(ProducerRecord.class)))
            .thenAnswer(call -> CompletableFuture.failedFuture(new NotEnoughReplicasException("broker unavailable")));

        relay.relayOnce();                                      // failure 1: wait 1 s
        assertWaitsExactly(Duration.ofSeconds(1));
        relay.relayOnce();                                      // failure 2: wait 2 s
        assertWaitsExactly(Duration.ofSeconds(2));
        relay.relayOnce();                                      // failure 3: wait 4 s
        assertWaitsExactly(Duration.ofSeconds(4));
        for (int failure = 4; failure <= 12; failure++) {
            relay.relayOnce();
            clock.advance(BACKOFF_MAX);
        }
        relay.relayOnce();                                      // failure 13: 2^12 s is past the cap
        assertWaitsExactly(BACKOFF_MAX);

        when(kafka.send(any(ProducerRecord.class)))
            .thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));
        assertThat(relay.relayOnce()).isEqualTo(1);             // success resets the backoff
        when(outbox.findUnpublishedBatch(50)).thenReturn(List.of(row("CMP-2")));
        when(kafka.send(any(ProducerRecord.class)))
            .thenReturn(CompletableFuture.failedFuture(new NotEnoughReplicasException("broker unavailable")));
        relay.relayOnce();
        assertWaitsExactly(Duration.ofSeconds(1));
    }

    private void assertWaitsExactly(Duration wait) {
        org.mockito.Mockito.clearInvocations(outbox, kafka);
        clock.advance(wait.minusMillis(1));
        assertThat(relay.relayOnce()).isZero();
        verify(outbox, never()).tryRelayLock(anyLong());
        verify(kafka, never()).send(any(ProducerRecord.class));
        clock.advance(Duration.ofMillis(1));
    }

    @Test
    void everyFailedSendIsCountedByExceptionClass() {
        when(outbox.tryRelayLock(anyLong())).thenReturn(true);
        when(outbox.findUnpublishedBatch(50)).thenReturn(List.of(row("CMP-1"), row("CMP-2")));
        when(kafka.send(any(ProducerRecord.class)))
            .thenReturn(CompletableFuture.failedFuture(new RecordTooLargeException("too large")))
            .thenReturn(CompletableFuture.failedFuture(new SaslAuthenticationException("IAM credentials expired")));

        relay.relayOnce();

        assertThat(meters.get("outbox.publish.failures").tag("exception", "RecordTooLargeException").counter().count()).isEqualTo(1);
        assertThat(meters.get("outbox.publish.failures").tag("exception", "SaslAuthenticationException").counter().count()).isEqualTo(1);
        assertThat(meters.get("outbox.publish.failures").tag("exception", "SaslAuthenticationException").counter().getId().getTags())
            .extracting(io.micrometer.core.instrument.Tag::getKey).containsExactlyInAnyOrder("exception", "service");
    }

    @Test
    void aRetriableFailureAtTheCeilingIsStillRetried() {
        OutboxEventJpaEntity head = row("CMP-1");
        head.markFailed("NotEnoughReplicasException", NOW.minus(PARK_AFTER));
        when(outbox.tryRelayLock(anyLong())).thenReturn(true);
        when(outbox.findUnpublishedBatch(50)).thenReturn(List.of(head, row("CMP-2")));
        when(kafka.send(any(ProducerRecord.class)))
            .thenReturn(CompletableFuture.failedFuture(new NotEnoughReplicasException("broker unavailable")));

        assertThat(relay.relayOnce()).isZero();

        assertThat(head.getParkedAt()).isNull();
        assertThat(head.getFirstFailedAt()).as("measured from the first failure").isEqualTo(NOW.minus(PARK_AFTER));
    }

    @Test
    void aRetriableFailurePastTheCeilingParksTheRowAndTheNextRowIsPublished() {
        OutboxEventJpaEntity stuck = row("CMP-1");
        stuck.markFailed("NotEnoughReplicasException", NOW.minus(PARK_AFTER).minusSeconds(1));
        OutboxEventJpaEntity next = row("CMP-2");
        when(outbox.tryRelayLock(anyLong())).thenReturn(true);
        when(outbox.findUnpublishedBatch(50)).thenReturn(List.of(stuck, next));
        when(kafka.send(any(ProducerRecord.class)))
            .thenReturn(CompletableFuture.failedFuture(new NotEnoughReplicasException("still out of sync")))
            .thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));

        assertThat(relay.relayOnce()).isEqualTo(1);

        assertThat(stuck.getParkedAt()).isEqualTo(NOW);
        assertThat(next.getPublishedAt()).isEqualTo(NOW);
    }

    @Test
    void aNonRetriableFailureParksAtOnceOnTheFirstAttempt() {
        OutboxEventJpaEntity invalid = row("CMP-1");
        when(outbox.tryRelayLock(anyLong())).thenReturn(true);
        when(outbox.findUnpublishedBatch(50)).thenReturn(List.of(invalid));
        when(kafka.send(any(ProducerRecord.class)))
            .thenReturn(CompletableFuture.failedFuture(new org.apache.kafka.common.errors.InvalidTopicException("bad topic")));

        relay.relayOnce();

        assertThat(invalid.getAttempts()).isEqualTo(1);
        assertThat(invalid.getFirstFailedAt()).isEqualTo(NOW);
        assertThat(invalid.getParkedAt()).isEqualTo(NOW);
    }

    @Test
    void lastErrorFitsTheColumn() {
        OutboxEventJpaEntity row = row("CMP-1");
        when(outbox.tryRelayLock(anyLong())).thenReturn(true);
        when(outbox.findUnpublishedBatch(50)).thenReturn(List.of(row));
        when(kafka.send(any(ProducerRecord.class)))
            .thenReturn(CompletableFuture.failedFuture(new RecordTooLargeException("x".repeat(2000))));

        relay.relayOnce();

        assertThat(row.getLastError()).hasSize(500);
    }

    @Test
    void relayTakesTheComplianceLockNotAnotherServicesLock() {
        when(outbox.tryRelayLock(anyLong())).thenReturn(false);

        relay.relayOnce();

        verify(outbox).tryRelayLock(0x636D705F6F7574L);
        assertThat(OutboxRelay.RELAY_LOCK_KEY).isNotEqualTo(0x6375735F6F7574L); // customer service's key
    }

    @Test
    void anInterruptedSendStopsTheBatchAndKeepsTheInterruptFlag() throws Exception {
        OutboxEventJpaEntity first = row("CMP-1");
        CompletableFuture<SendResult<String, String>> interrupted = mock(CompletableFuture.class);
        when(interrupted.get(anyLong(), any())).thenThrow(new InterruptedException());
        when(outbox.tryRelayLock(anyLong())).thenReturn(true);
        when(outbox.findUnpublishedBatch(50)).thenReturn(List.of(first, row("CMP-1")));
        when(kafka.send(any(ProducerRecord.class))).thenReturn(interrupted);

        try {
            assertThat(relay.relayOnce()).isZero();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        assertThat(first.getLastError()).isEqualTo("interrupted");
        assertThat(first.getPublishedAt()).isNull();
        verify(kafka, times(1)).send(any(ProducerRecord.class));
    }

    @Test
    void recordIsKeyedByAggregateAndCarriesTracingHeaders() {
        OutboxEventJpaEntity row = row("CMP-9");

        ProducerRecord<String, String> record = OutboxRelay.toRecord(row);

        assertThat(record.topic()).isEqualTo("evt.cmp.compliance.screened.v1");
        assertThat(record.key()).isEqualTo("CMP-9");
        assertThat(record.value()).isEqualTo("{}");
        assertThat(header(record, "eventType")).isEqualTo("Compliance.ComplianceScreening.Screened.v1");
        assertThat(header(record, "eventId")).isEqualTo(row.getEventId().toString());
        assertThat(header(record, "x-fapi-interaction-id")).isEqualTo("corr-9");
        assertThat(record.headers().lastHeader("traceparent")).as("omitted when the row has no trace context").isNull();
    }

    @Test
    void storedTraceContextIsSentAsTheW3cTraceparentHeader() {
        String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

        ProducerRecord<String, String> record = OutboxRelay.toRecord(row("CMP-10", traceparent));

        assertThat(header(record, "traceparent")).isEqualTo(traceparent);
    }

    @Test
    void purgeDeletesRowsPublishedBeforeTheRetentionWindow() {
        when(outbox.deletePublishedBefore(NOW.minus(Duration.ofDays(7)))).thenReturn(3);

        assertThat(relay.purgePublished()).isEqualTo(3);
    }

    private static String header(ProducerRecord<String, String> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }

    private static OutboxEventJpaEntity row(String aggregateId) {
        return row(aggregateId, null);
    }

    private static OutboxEventJpaEntity row(String aggregateId, String traceparent) {
        return new OutboxEventJpaEntity(UUID.randomUUID(), "ComplianceScreening", aggregateId, 0L,
            "Compliance.ComplianceScreening.Screened.v1", "evt.cmp.compliance.screened.v1", "{}", "corr-9", traceparent, NOW);
    }

    /** A clock the test moves forward. */
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static TransactionTemplate inlineTransactions() {
        return new TransactionTemplate() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(new SimpleTransactionStatus());
            }
        };
    }
}
