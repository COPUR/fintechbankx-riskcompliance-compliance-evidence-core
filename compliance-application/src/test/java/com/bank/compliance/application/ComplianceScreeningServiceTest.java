package com.bank.compliance.application;

import com.bank.compliance.domain.ComplianceDecision;
import com.bank.compliance.domain.ComplianceResult;
import com.bank.compliance.domain.ComplianceScreenedEvent;
import com.bank.compliance.domain.command.ComplianceScreeningCommand;
import com.bank.compliance.domain.port.out.ComplianceEventPublisher;
import com.bank.compliance.domain.port.out.ComplianceResultRepository;
import com.bank.compliance.domain.service.ComplianceRuleService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ComplianceScreeningServiceTest {

    @Mock
    private ComplianceRuleService ruleService;

    @Mock
    private ComplianceResultRepository repository;

    @Mock
    private ComplianceEventPublisher eventPublisher;

    @InjectMocks
    private ComplianceScreeningService service;

    @Test
    void shouldReturnExistingResultWhenAlreadyScreened() {
        ComplianceScreeningCommand command = new ComplianceScreeningCommand("TX-1", "C1", new BigDecimal("100"), false, true, false);
        ComplianceResult existing = ComplianceResult.create("TX-1", "C1", ComplianceDecision.PASS, List.of("COMPLIANT"));

        when(repository.findByTransactionId("TX-1")).thenReturn(Optional.of(existing));

        ComplianceResult result = service.screen(command);

        assertThat(result).isEqualTo(existing);
        verify(ruleService, never()).screen(any());
        verify(repository, never()).save(any());
        verify(eventPublisher, never()).publish(any());
    }

    @Test
    void shouldEvaluateAndPersistWhenNoExistingResult() {
        ComplianceScreeningCommand command = new ComplianceScreeningCommand("TX-2", "C1", new BigDecimal("11000"), false, true, true);
        ComplianceResult evaluated = ComplianceResult.create("TX-2", "C1", ComplianceDecision.REVIEW, List.of("PEP_HIGH_VALUE_REVIEW"));

        when(repository.findByTransactionId("TX-2")).thenReturn(Optional.empty());
        when(ruleService.screen(command)).thenReturn(evaluated);
        when(repository.save(evaluated)).thenReturn(evaluated);

        ComplianceResult result = service.screen(command);

        assertThat(result).isEqualTo(evaluated);
        verify(ruleService).screen(command);
        verify(repository).save(evaluated);
    }

    @Test
    void newScreeningPublishesOneScreenedEventAfterTheResultIsSaved() {
        ComplianceScreeningCommand command = new ComplianceScreeningCommand("TX-5", "C7", new BigDecimal("250.00"), true, true, false);
        ComplianceResult evaluated = ComplianceResult.create("TX-5", "C7", ComplianceDecision.FAIL, List.of("SANCTIONS_HIT"));
        when(repository.findByTransactionId("TX-5")).thenReturn(Optional.empty());
        when(ruleService.screen(command)).thenReturn(evaluated);
        when(repository.save(evaluated)).thenReturn(evaluated);

        service.screen(command);

        InOrder order = inOrder(repository, eventPublisher);
        order.verify(repository).save(evaluated);
        ArgumentCaptor<ComplianceScreenedEvent> published = ArgumentCaptor.forClass(ComplianceScreenedEvent.class);
        order.verify(eventPublisher).publish(published.capture());
        verifyNoMoreInteractions(eventPublisher);
        assertThat(published.getValue().screeningId()).isEqualTo(evaluated.getId());
        assertThat(published.getValue().transactionId()).isEqualTo("TX-5");
        assertThat(published.getValue().decision()).isEqualTo(ComplianceDecision.FAIL);
        assertThat(published.getValue().reasons()).containsExactly("SANCTIONS_HIT");
    }

    @Test
    void aFailedSavePublishesNothing() {
        ComplianceScreeningCommand command = new ComplianceScreeningCommand("TX-6", "C7", new BigDecimal("10.00"), false, true, false);
        ComplianceResult evaluated = ComplianceResult.create("TX-6", "C7", ComplianceDecision.PASS, List.of("COMPLIANT"));
        when(repository.findByTransactionId("TX-6")).thenReturn(Optional.empty());
        when(ruleService.screen(command)).thenReturn(evaluated);
        when(repository.save(evaluated)).thenThrow(new IllegalStateException("unique transaction_id"));

        assertThatThrownBy(() -> service.screen(command)).isInstanceOf(IllegalStateException.class);
        verify(eventPublisher, never()).publish(any());
    }

    @Test
    void findByTransactionIdShouldDelegate() {
        when(repository.findByTransactionId("TX-3")).thenReturn(Optional.empty());

        assertThat(service.findByTransactionId("TX-3")).isEmpty();
        verify(repository).findByTransactionId("TX-3");
    }

    @Test
    void shouldRefuseAReusedTransactionIdForAnotherCustomer() {
        ComplianceScreeningCommand command = new ComplianceScreeningCommand("TX-9", "C2", new BigDecimal("100"), false, true, false);
        ComplianceResult existing = ComplianceResult.create("TX-9", "C1", ComplianceDecision.PASS, List.of("COMPLIANT"));
        when(repository.findByTransactionId("TX-9")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.screen(command))
                .isInstanceOf(TransactionAlreadyScreenedException.class)
                .hasMessageContaining("TX-9");
        verify(repository, never()).save(any());
        verify(eventPublisher, never()).publish(any());
    }
}
