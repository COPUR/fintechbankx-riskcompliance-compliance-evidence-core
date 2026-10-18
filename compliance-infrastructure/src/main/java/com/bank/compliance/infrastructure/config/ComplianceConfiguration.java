package com.bank.compliance.infrastructure.config;

import com.bank.compliance.application.ComplianceScreeningService;
import com.bank.compliance.domain.port.in.ComplianceScreeningUseCase;
import com.bank.compliance.domain.port.out.ComplianceEventPublisher;
import com.bank.compliance.domain.port.out.ComplianceResultRepository;
import com.bank.compliance.domain.port.out.CustomerProfilePort;
import com.bank.compliance.domain.port.out.NameScreeningPort;
import com.bank.compliance.domain.service.ComplianceRuleService;
import com.bank.compliance.domain.service.ListFreshnessPolicy;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;

@Configuration
public class ComplianceConfiguration {

    @Bean
    ComplianceRuleService complianceRuleService() {
        return new ComplianceRuleService();
    }

    /**
     * How old a list load may be before screening against it is refused
     * (COMPLIANCE_LIST_MAX_AGE_SANCTIONS, default P1D; COMPLIANCE_LIST_MAX_AGE_PEP, default P35D).
     */
    @Bean
    ListFreshnessPolicy listFreshnessPolicy(@Value("${compliance.lists.sanctions-max-age}") Duration sanctionsMaxAge,
                                            @Value("${compliance.lists.pep-max-age}") Duration pepMaxAge) {
        return new ListFreshnessPolicy(sanctionsMaxAge, pepMaxAge);
    }

    /** The clock the freshness check reads; UTC like the loaded_at it is compared with. */
    @Bean("screeningClock")
    public Clock screeningClock() {
        return Clock.systemUTC();
    }

    /**
     * The only {@link ComplianceScreeningUseCase} bean: the application service
     * wrapped in its transaction boundary. The bare service is deliberately not
     * a bean, so nothing can call it outside a transaction (the outbox
     * publisher requires one).
     */
    @Bean
    ComplianceScreeningUseCase complianceScreeningUseCase(
            ComplianceRuleService complianceRuleService,
            ComplianceResultRepository complianceResultRepository,
            ComplianceEventPublisher complianceEventPublisher,
            CustomerProfilePort customerProfilePort,
            NameScreeningPort nameScreeningPort,
            ListFreshnessPolicy listFreshnessPolicy,
            @Qualifier("screeningClock") Clock screeningClock,
            PlatformTransactionManager transactionManager
    ) {
        ComplianceScreeningService service = new ComplianceScreeningService(complianceRuleService, complianceResultRepository,
                complianceEventPublisher, customerProfilePort, nameScreeningPort, listFreshnessPolicy, screeningClock);
        TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);
        return new TransactionalComplianceScreening(service, new TransactionTemplate(transactionManager), readOnly);
    }
}
