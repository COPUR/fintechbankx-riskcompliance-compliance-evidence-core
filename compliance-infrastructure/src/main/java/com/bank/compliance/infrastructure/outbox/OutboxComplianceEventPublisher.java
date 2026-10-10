package com.bank.compliance.infrastructure.outbox;

import com.bank.compliance.domain.ComplianceScreenedEvent;
import com.bank.compliance.domain.port.out.ComplianceEventPublisher;
import com.bank.compliance.infrastructure.web.CorrelationIdFilter;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Transactional outbox: writes the event's envelope in the caller's
 * transaction (MANDATORY), so the screening result and its event commit or
 * roll back together. {@link OutboxRelay} ships it to Kafka afterwards.
 */
@Component
public class OutboxComplianceEventPublisher implements ComplianceEventPublisher {

    private final SpringDataOutboxRepository outbox;
    private final ComplianceEventEnvelopeFactory envelopes;

    public OutboxComplianceEventPublisher(SpringDataOutboxRepository outbox, ComplianceEventEnvelopeFactory envelopes) {
        this.outbox = outbox;
        this.envelopes = envelopes;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(ComplianceScreenedEvent event) {
        outbox.save(envelopes.toOutboxRow(event, currentCorrelationId(), TraceContext.currentTraceparent()));
    }

    private static String currentCorrelationId() {
        String fromRequest = MDC.get(CorrelationIdFilter.MDC_KEY);
        return fromRequest != null ? fromRequest : UUID.randomUUID().toString();
    }
}
