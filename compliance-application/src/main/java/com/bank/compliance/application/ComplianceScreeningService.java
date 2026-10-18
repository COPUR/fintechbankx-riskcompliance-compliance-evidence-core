package com.bank.compliance.application;

import com.bank.compliance.domain.ComplianceResult;
import com.bank.compliance.domain.CustomerNotFoundException;
import com.bank.compliance.domain.CustomerProfile;
import com.bank.compliance.domain.ListVersion;
import com.bank.compliance.domain.NormalizedName;
import com.bank.compliance.domain.ScreeningFacts;
import com.bank.compliance.domain.ScreeningList;
import com.bank.compliance.domain.ScreeningListMatch;
import com.bank.compliance.domain.TransactionAlreadyScreenedException;
import com.bank.compliance.domain.port.in.ComplianceScreeningCommand;
import com.bank.compliance.domain.port.in.ComplianceScreeningUseCase;
import com.bank.compliance.domain.port.in.ComputedFactsScreeningCommand;
import com.bank.compliance.domain.port.out.ComplianceEventPublisher;
import com.bank.compliance.domain.port.out.ComplianceResultRepository;
import com.bank.compliance.domain.port.out.CustomerProfilePort;
import com.bank.compliance.domain.port.out.NameScreeningPort;
import com.bank.compliance.domain.service.ComplianceRuleService;
import com.bank.compliance.domain.service.ListFreshnessPolicy;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Screens a transaction once and records the result as evidence.
 *
 * Two entry points share the rules: {@link #screen} judges facts a caller
 * stated (deprecated v1) and {@link #screenWithComputedFacts} resolves the
 * facts itself (v2): the customer's name and KYC status from the customer
 * context, the sanctions and PEP flags by matching the normalised name
 * against the compliance-owned lists, each list required to be loaded and
 * fresh. A source that cannot answer stops the screening; nothing is recorded
 * and nothing passes by default.
 *
 * Not transactional by itself (the application layer has no framework): the
 * caller must run each screening in one database transaction so the stored
 * result and its outbox event commit together. The infrastructure layer wraps
 * this service in a transactional decorator for that.
 */
public class ComplianceScreeningService implements ComplianceScreeningUseCase {
    private final ComplianceRuleService ruleService;
    private final ComplianceResultRepository repository;
    private final ComplianceEventPublisher eventPublisher;
    private final CustomerProfilePort customerProfiles;
    private final NameScreeningPort nameScreening;
    private final ListFreshnessPolicy listFreshness;
    private final Clock clock;

    public ComplianceScreeningService(ComplianceRuleService ruleService, ComplianceResultRepository repository,
                                      ComplianceEventPublisher eventPublisher, CustomerProfilePort customerProfiles,
                                      NameScreeningPort nameScreening, ListFreshnessPolicy listFreshness, Clock clock) {
        this.ruleService = Objects.requireNonNull(ruleService, "ruleService is required");
        this.repository = Objects.requireNonNull(repository, "repository is required");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher is required");
        this.customerProfiles = Objects.requireNonNull(customerProfiles, "customerProfiles are required");
        this.nameScreening = Objects.requireNonNull(nameScreening, "nameScreening is required");
        this.listFreshness = Objects.requireNonNull(listFreshness, "listFreshness policy is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    @Override
    public ComplianceResult screen(ComplianceScreeningCommand command) {
        Optional<ComplianceResult> existing = repository.findByTransactionId(command.transactionId());
        if (existing.isPresent()) {
            // A replay (same customer, same facts) gets the recorded result; anything
            // else under this transaction id is refused and the evidence is kept.
            if (!existing.get().isReplayOf(command.customerId(), command.facts())) {
                throw new TransactionAlreadyScreenedException();
            }
            return existing.get();
        }

        return record(ruleService.screen(command));
    }

    @Override
    public ComplianceResult screenWithComputedFacts(ComputedFactsScreeningCommand command) {
        Optional<ComplianceResult> existing = repository.findByTransactionId(command.transactionId());
        if (existing.isPresent()) {
            // The stored result answers a replay without consulting any source again;
            // the facts it was made on are evidence of that moment, not of this one.
            if (!existing.get().isReplayOf(command)) {
                throw new TransactionAlreadyScreenedException();
            }
            return existing.get();
        }

        CustomerProfile customer = customerProfiles.findCustomer(command.customerId())
                .orElseThrow(CustomerNotFoundException::new);
        NormalizedName name = customer.fullName();
        Instant now = clock.instant();

        ScreeningListMatch sanctions = nameScreening.match(ScreeningList.SANCTIONS, name);
        listFreshness.requireUsable(ScreeningList.SANCTIONS, sanctions, now);
        ScreeningListMatch pep = nameScreening.match(ScreeningList.PEP, name);
        listFreshness.requireUsable(ScreeningList.PEP, pep, now);

        ScreeningFacts facts = new ScreeningFacts(command.amount(), command.currency(),
                sanctions.hit(), customer.kycVerified(), pep.hit(), command.requestedBy());
        List<ListVersion> consulted = new ArrayList<>(sanctions.sources());
        consulted.addAll(pep.sources());

        return record(ruleService.screen(command.transactionId(), command.customerId(), facts, List.copyOf(consulted)));
    }

    /** Only a newly recorded result is published; the replay paths publish nothing. */
    private ComplianceResult record(ComplianceResult evaluated) {
        ComplianceResult saved = repository.save(evaluated);
        eventPublisher.publish(saved.screenedEvent());
        return saved;
    }

    @Override
    public Optional<ComplianceResult> findByTransactionId(String transactionId) {
        return repository.findByTransactionId(transactionId);
    }
}
