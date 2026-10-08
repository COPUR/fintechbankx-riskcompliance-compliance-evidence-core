package com.bank.compliance.infrastructure.outbox;

import com.bank.compliance.domain.ComplianceDecision;
import com.bank.compliance.domain.ComplianceResult;
import com.bank.compliance.infrastructure.web.CorrelationIdFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class OutboxComplianceEventPublisherTest {

    private final SpringDataOutboxRepository outbox = mock(SpringDataOutboxRepository.class);
    private final OutboxComplianceEventPublisher publisher =
        new OutboxComplianceEventPublisher(outbox, new ComplianceEventEnvelopeFactory(new ObjectMapper()));

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void writesOneRowWithTheRequestCorrelationId() {
        MDC.put(CorrelationIdFilter.MDC_KEY, "corr-req");
        ComplianceResult result = ComplianceResult.create("PAY-1", "C-1", ComplianceDecision.PASS, List.of("COMPLIANT"));

        publisher.publish(result.screenedEvent());

        ArgumentCaptor<OutboxEventJpaEntity> row = ArgumentCaptor.forClass(OutboxEventJpaEntity.class);
        verify(outbox).save(row.capture());
        assertThat(row.getValue().getTopic()).isEqualTo("evt.cmp.compliance.screened.v1");
        assertThat(row.getValue().getAggregateId()).isEqualTo(result.getId().getValue());
        assertThat(row.getValue().getCorrelationId()).isEqualTo("corr-req");
    }

    @Test
    void eventsRaisedOutsideARequestGetAFreshCorrelationId() {
        ComplianceResult result = ComplianceResult.create("PAY-2", "C-1", ComplianceDecision.PASS, List.of("COMPLIANT"));

        publisher.publish(result.screenedEvent());

        ArgumentCaptor<OutboxEventJpaEntity> row = ArgumentCaptor.forClass(OutboxEventJpaEntity.class);
        verify(outbox).save(row.capture());
        assertThat(row.getValue().getCorrelationId()).matches("[0-9a-f-]{36}");
    }

    @Test
    void publishingRequiresTheCallersTransaction() throws Exception {
        Transactional tx = OutboxComplianceEventPublisher.class
            .getMethod("publish", com.bank.compliance.domain.ComplianceScreenedEvent.class)
            .getAnnotation(Transactional.class);

        assertThat(tx.propagation()).isEqualTo(Propagation.MANDATORY);
    }
}
