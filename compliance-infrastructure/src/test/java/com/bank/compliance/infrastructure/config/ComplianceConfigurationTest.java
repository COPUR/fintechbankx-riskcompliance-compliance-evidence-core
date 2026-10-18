package com.bank.compliance.infrastructure.config;

import com.bank.compliance.domain.ComplianceResult;
import com.bank.compliance.domain.port.in.ComplianceScreeningCommand;
import com.bank.compliance.domain.port.in.ComplianceScreeningUseCase;
import com.bank.compliance.domain.port.out.ComplianceEventPublisher;
import com.bank.compliance.domain.service.ComplianceRuleService;
import com.bank.compliance.infrastructure.persistence.InMemoryComplianceResultRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ComplianceConfigurationTest {

    private final ComplianceConfiguration configuration = new ComplianceConfiguration();

    @Test
    void shouldCreateComplianceRuleServiceBean() {
        assertThat(configuration.complianceRuleService()).isNotNull();
    }

    @Test
    void theUseCaseBeanRunsScreeningInAReadWriteTransactionAndLookupsReadOnly() {
        ComplianceRuleService ruleService = configuration.complianceRuleService();
        ComplianceEventPublisher publisher = mock(ComplianceEventPublisher.class);
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());

        ComplianceScreeningUseCase useCase = configuration.complianceScreeningUseCase(
                ruleService, new InMemoryComplianceResultRepository(), publisher,
                mock(com.bank.compliance.domain.port.out.CustomerProfilePort.class),
                mock(com.bank.compliance.domain.port.out.NameScreeningPort.class),
                configuration.listFreshnessPolicy(java.time.Duration.ofDays(1), java.time.Duration.ofDays(35)),
                configuration.screeningClock(), transactions);
        ComplianceResult result = useCase.screen(
                new ComplianceScreeningCommand("TX-CFG", "C-1", new BigDecimal("50.00"), "AED", false, true, false, com.bank.compliance.domain.ComplianceResultFixtures.PAYMENTS));
        useCase.findByTransactionId("TX-CFG");

        assertThat(useCase).isInstanceOf(TransactionalComplianceScreening.class);
        assertThat(result.getReasons()).containsExactly("COMPLIANT");
        verify(publisher).publish(any());
        verify(transactions).getTransaction(argThat((TransactionDefinition d) -> d != null && !d.isReadOnly()));
        verify(transactions).getTransaction(argThat((TransactionDefinition d) -> d != null && d.isReadOnly()));
    }

    /** The maximum list ages come from configuration (COMPLIANCE_LIST_MAX_AGE_SANCTIONS / _PEP) and the clock is UTC. */
    @Test
    void theFreshnessPolicyAndClockBeansFollowConfiguration() {
        var policy = configuration.listFreshnessPolicy(java.time.Duration.parse("PT12H"), java.time.Duration.parse("P7D"));

        assertThat(policy.maxAgeOf(com.bank.compliance.domain.ScreeningList.SANCTIONS)).isEqualTo(java.time.Duration.ofHours(12));
        assertThat(policy.maxAgeOf(com.bank.compliance.domain.ScreeningList.PEP)).isEqualTo(java.time.Duration.ofDays(7));
        assertThat(configuration.screeningClock().getZone()).isEqualTo(java.time.ZoneOffset.UTC);
    }
}
