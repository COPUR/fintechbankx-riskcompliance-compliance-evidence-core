package com.bank.compliance.infrastructure.outbox;

import com.bank.compliance.domain.ComplianceScreenedEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns {@link ComplianceScreenedEvent} into the public envelope of the
 * AsyncAPI contract api/asyncapi/svc-cmp-evidence.yaml: topic
 * evt.cmp.compliance.screened.v1, eventType
 * Compliance.ComplianceScreening.Screened.v1, keyed by the screening id.
 *
 * Only ids, the decision, reason codes and the screening time are published.
 * The screening inputs (sanctions, PEP and KYC flags, amount) never leave the
 * service on events; entitled readers get them through the compliance API.
 */
public class ComplianceEventEnvelopeFactory {

    public static final String PRODUCER = "svc-cmp-evidence";
    public static final String AGGREGATE_TYPE = "ComplianceScreening";
    public static final String SCREENED_TOPIC = "evt.cmp.compliance.screened.v1";
    public static final String SCREENED_EVENT_TYPE = "Compliance.ComplianceScreening.Screened.v1";
    /** Screening results are insert-only, so the one event of a screening is always version 0. */
    static final long AGGREGATE_VERSION = 0L;

    private final ObjectMapper objectMapper;

    public ComplianceEventEnvelopeFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public OutboxEventJpaEntity toOutboxRow(ComplianceScreenedEvent event, String correlationId) {
        String aggregateId = event.screeningId().getValue();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("screeningId", aggregateId);
        data.put("transactionId", event.transactionId());
        data.put("customerId", event.customerId());
        data.put("decision", event.decision().name());
        data.put("reasons", List.copyOf(event.reasons()));
        data.put("checkedAt", event.checkedAt().toString());

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", event.eventId().toString());
        envelope.put("eventType", SCREENED_EVENT_TYPE);
        envelope.put("occurredAt", event.occurredAt().toString());
        envelope.put("aggregateId", aggregateId);
        envelope.put("aggregateVersion", AGGREGATE_VERSION);
        envelope.put("correlationId", correlationId);
        envelope.put("causationId", null);
        envelope.put("producer", PRODUCER);
        envelope.put("data", data);

        return new OutboxEventJpaEntity(event.eventId(), AGGREGATE_TYPE, aggregateId, AGGREGATE_VERSION,
            SCREENED_EVENT_TYPE, SCREENED_TOPIC, toJson(envelope), correlationId, event.occurredAt());
    }

    private String toJson(Map<String, Object> envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise compliance event envelope", e);
        }
    }
}
