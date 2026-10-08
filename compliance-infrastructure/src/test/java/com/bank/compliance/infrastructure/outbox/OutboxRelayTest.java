package com.bank.compliance.infrastructure.outbox;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.NotEnoughReplicasException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.errors.TopicAuthorizationException;
import org.apache.kafka.common.errors.SerializationException;
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
    private final OutboxRelay relay = new OutboxRelay(outbox, kafka, transactions,
        Clock.fixed(NOW, ZoneOffset.UTC), 50, Duration.ofSeconds(1), Duration.ofDays(7), MAX_ATTEMPTS);

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

        // The relay's own wait on the send future running out.
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
    void failuresThrownBySendItselfAreClassifiedTheSameWay() {
        OutboxEventJpaEntity unserializable = row("CMP-1");
        OutboxEventJpaEntity unauthorised = row("CMP-2");
        OutboxEventJpaEntity next = row("CMP-3");
        when(outbox.tryRelayLock(anyLong())).thenReturn(true);
        when(outbox.findUnpublishedBatch(50)).thenReturn(List.of(unserializable, unauthorised, next));
        when(kafka.send(any(ProducerRecord.class)))
            .thenThrow(new SerializationException("cannot serialize"))
            .thenThrow(new TopicAuthorizationException(Set.of("evt.cmp.compliance.screened.v1")))
            .thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));

        assertThat(relay.relayOnce()).isEqualTo(1);

        assertThat(unserializable.getParkedAt()).isEqualTo(NOW);
        assertThat(unauthorised.getParkedAt()).isEqualTo(NOW);
        assertThat(unauthorised.getLastError()).startsWith("TopicAuthorizationException");
        assertThat(next.getPublishedAt()).isEqualTo(NOW);
    }

    @Test
    void aRowThatReachesTheAttemptCapIsParkedAndTheNextRowIsPublished() {
        OutboxEventJpaEntity stuck = row("CMP-1");
        for (int attempt = 1; attempt < MAX_ATTEMPTS; attempt++) {
            stuck.markFailed("NotEnoughReplicasException");
        }
        OutboxEventJpaEntity next = row("CMP-2");
        when(outbox.tryRelayLock(anyLong())).thenReturn(true);
        when(outbox.findUnpublishedBatch(50)).thenReturn(List.of(stuck, next));
        when(kafka.send(any(ProducerRecord.class)))
            .thenReturn(CompletableFuture.failedFuture(new NotEnoughReplicasException("still out of sync")))
            .thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));

        assertThat(relay.relayOnce()).isEqualTo(1);

        assertThat(stuck.getAttempts()).isEqualTo(MAX_ATTEMPTS);
        assertThat(stuck.getParkedAt()).isEqualTo(NOW);
        assertThat(next.getPublishedAt()).isEqualTo(NOW);
    }

    @Test
    void aRowBelowTheAttemptCapIsNotParked() {
        OutboxEventJpaEntity row = row("CMP-1");
        for (int attempt = 1; attempt < MAX_ATTEMPTS - 1; attempt++) {
            row.markFailed("NotEnoughReplicasException");
        }
        when(outbox.tryRelayLock(anyLong())).thenReturn(true);
        when(outbox.findUnpublishedBatch(50)).thenReturn(List.of(row, row("CMP-2")));
        when(kafka.send(any(ProducerRecord.class)))
            .thenReturn(CompletableFuture.failedFuture(new NotEnoughReplicasException("still out of sync")));

        assertThat(relay.relayOnce()).isZero();

        assertThat(row.getAttempts()).isEqualTo(MAX_ATTEMPTS - 1);
        assertThat(row.getParkedAt()).isNull();
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

    private static TransactionTemplate inlineTransactions() {
        return new TransactionTemplate() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(new SimpleTransactionStatus());
            }
        };
    }
}
