package com.bank.compliance.application;

import com.bank.compliance.domain.ComplianceResult;
import com.bank.compliance.domain.command.ComplianceScreeningCommand;
import com.bank.compliance.domain.port.in.ComplianceScreeningUseCase;
import com.bank.compliance.domain.port.out.ComplianceEventPublisher;
import com.bank.compliance.domain.port.out.ComplianceResultRepository;
import com.bank.compliance.domain.service.ComplianceRuleService;

import java.util.Optional;

/**
 * Screens a transaction once and records the result as evidence.
 *
 * Not transactional by itself (the application layer has no framework): the
 * caller must run {@link #screen} in one database transaction so the stored
 * result and its outbox event commit together. The infrastructure layer wraps
 * this service in a transactional decorator for that.
 */
public class ComplianceScreeningService implements ComplianceScreeningUseCase {
    private final ComplianceRuleService ruleService;
    private final ComplianceResultRepository repository;
    private final ComplianceEventPublisher eventPublisher;

    public ComplianceScreeningService(ComplianceRuleService ruleService, ComplianceResultRepository repository,
                                      ComplianceEventPublisher eventPublisher) {
        this.ruleService = ruleService;
        this.repository = repository;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public ComplianceResult screen(ComplianceScreeningCommand command) {
        Optional<ComplianceResult> existing = repository.findByTransactionId(command.transactionId());
        if (existing.isPresent()) {
            // A replay (same customer, same facts) gets the recorded result; anything
            // else under this transaction id is refused and the evidence is kept.
            if (!existing.get().isReplayOf(command.customerId(), command.facts())) {
                throw new TransactionAlreadyScreenedException(command.transactionId());
            }
            return existing.get();
        }

        // Only a newly recorded result is published; the retry path above publishes nothing.
        ComplianceResult saved = repository.save(ruleService.screen(command));
        eventPublisher.publish(saved.screenedEvent());
        return saved;
    }

    @Override
    public Optional<ComplianceResult> findByTransactionId(String transactionId) {
        return repository.findByTransactionId(transactionId);
    }
}
