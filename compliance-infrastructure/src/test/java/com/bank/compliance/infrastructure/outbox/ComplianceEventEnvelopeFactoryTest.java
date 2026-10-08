package com.bank.compliance.infrastructure.outbox;

import com.bank.compliance.domain.ComplianceDecision;
import com.bank.compliance.domain.ComplianceResult;
import com.bank.compliance.domain.ComplianceResultFixtures;
import com.bank.compliance.domain.ComplianceResultId;
import com.bank.compliance.domain.ComplianceResultSnapshot;
import com.bank.compliance.domain.ComplianceScreenedEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ComplianceEventEnvelopeFactoryTest {

    private static final Instant CHECKED_AT = Instant.parse("2026-10-08T09:15:30.123456Z");
    private static final String SCREENING_ID = "CMP-0b9d1c5e-3f4a-4d8e-9a71-2c6f0e5b7d10";

    private final ObjectMapper json = new ObjectMapper();
    private final ComplianceEventEnvelopeFactory factory = new ComplianceEventEnvelopeFactory(json);

    private static ComplianceScreenedEvent screened(ComplianceDecision decision, List<String> reasons) {
        return ComplianceResult.rehydrate(new ComplianceResultSnapshot(ComplianceResultId.of(SCREENING_ID),
                "PAY-77", "C-42", ComplianceResultFixtures.facts(), decision, reasons, "cmp-screening-rules-v1", CHECKED_AT))
                .screenedEvent();
    }

    @Test
    void outboxRowCarriesTheContractTopicTypeAndKey() {
        ComplianceScreenedEvent event = screened(ComplianceDecision.REVIEW, List.of("PEP_HIGH_VALUE_REVIEW"));

        OutboxEventJpaEntity row = factory.toOutboxRow(event, "corr-1");

        assertThat(row.getEventId()).isEqualTo(event.eventId());
        assertThat(row.getTopic()).isEqualTo("evt.cmp.compliance.screened.v1");
        assertThat(row.getEventType()).isEqualTo("Compliance.ComplianceScreening.Screened.v1");
        assertThat(row.getAggregateType()).isEqualTo("ComplianceScreening");
        assertThat(row.getAggregateId()).isEqualTo(SCREENING_ID);
        assertThat(row.getAggregateVersion()).isZero();
        assertThat(row.getCorrelationId()).isEqualTo("corr-1");
        assertThat(row.getOccurredAt()).isEqualTo(CHECKED_AT);
        assertThat(row.getPublishedAt()).isNull();
        assertThat(row.getAttempts()).isZero();
    }

    @Test
    void payloadIsTheStandardEnvelopeWithTheScreeningFacts() throws Exception {
        ComplianceScreenedEvent event = screened(ComplianceDecision.FAIL, List.of("SANCTIONS_HIT"));

        JsonNode envelope = json.readTree(factory.toOutboxRow(event, "corr-2").getPayload());

        assertThat(envelope.fieldNames()).toIterable().containsExactly("eventId", "eventType", "occurredAt",
                "aggregateId", "aggregateVersion", "correlationId", "causationId", "producer", "data");
        assertThat(envelope.get("eventId").asText()).isEqualTo(event.eventId().toString());
        assertThat(envelope.get("eventType").asText()).isEqualTo("Compliance.ComplianceScreening.Screened.v1");
        assertThat(envelope.get("occurredAt").asText()).isEqualTo("2026-10-08T09:15:30.123456Z");
        assertThat(envelope.get("aggregateId").asText()).isEqualTo(SCREENING_ID);
        assertThat(envelope.get("aggregateVersion").asLong()).isZero();
        assertThat(envelope.get("correlationId").asText()).isEqualTo("corr-2");
        assertThat(envelope.get("causationId").isNull()).isTrue();
        assertThat(envelope.get("producer").asText()).isEqualTo("svc-cmp-evidence");

        JsonNode data = envelope.get("data");
        assertThat(data.fieldNames()).toIterable().containsExactly(
                "screeningId", "transactionId", "customerId", "decision", "reasons", "checkedAt");
        assertThat(data.get("screeningId").asText()).isEqualTo(SCREENING_ID);
        assertThat(data.get("transactionId").asText()).isEqualTo("PAY-77");
        assertThat(data.get("customerId").asText()).isEqualTo("C-42");
        assertThat(data.get("decision").asText()).isEqualTo("FAIL");
        assertThat(data.get("reasons")).hasSize(1);
        assertThat(data.get("reasons").get(0).asText()).isEqualTo("SANCTIONS_HIT");
        assertThat(data.get("checkedAt").asText()).isEqualTo("2026-10-08T09:15:30.123456Z");
    }

    @Test
    void screeningInputsAreNeverPublished() {
        String payload = factory.toOutboxRow(screened(ComplianceDecision.PASS, List.of("COMPLIANT")), "corr-3").getPayload();

        assertThat(payload).doesNotContain("amount", "sanctionsHit", "kycVerified", "\"pep\"");
    }

    @Test
    void serialisationFailureIsReported() throws Exception {
        ObjectMapper broken = mock(ObjectMapper.class);
        when(broken.writeValueAsString(any())).thenThrow(new JsonProcessingException("boom") { });

        assertThatThrownBy(() -> new ComplianceEventEnvelopeFactory(broken)
                .toOutboxRow(screened(ComplianceDecision.PASS, List.of("COMPLIANT")), "corr-4"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot serialise");
    }
}
