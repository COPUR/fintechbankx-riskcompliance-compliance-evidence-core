package com.bank.compliance.infrastructure.config;

import com.bank.compliance.application.ComplianceScreeningService;
import com.bank.compliance.domain.port.in.ComplianceScreeningUseCase;
import com.bank.compliance.domain.port.out.ComplianceEventPublisher;
import com.bank.compliance.domain.port.out.ComplianceResultRepository;
import com.bank.compliance.domain.service.ComplianceRuleService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
public class ComplianceConfiguration {

    @Bean
    ComplianceRuleService complianceRuleService() {
        return new ComplianceRuleService();
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
            PlatformTransactionManager transactionManager
    ) {
        ComplianceScreeningService service =
                new ComplianceScreeningService(complianceRuleService, complianceResultRepository, complianceEventPublisher);
        TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);
        return new TransactionalComplianceScreening(service, new TransactionTemplate(transactionManager), readOnly);
    }
}
