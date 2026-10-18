package com.bank.compliance.domain.port.in;

import com.bank.compliance.domain.ComplianceResult;
import com.bank.compliance.domain.CustomerNotFoundException;
import com.bank.compliance.domain.ScreeningSourceUnavailableException;
import com.bank.compliance.domain.TransactionAlreadyScreenedException;

import java.util.Optional;

public interface ComplianceScreeningUseCase {

    /**
     * Screens caller-attested facts (deprecated v1). A replay of the same
     * transaction with the same customer, facts and caller returns the stored result.
     *
     * @throws TransactionAlreadyScreenedException for a conflicting replay
     */
    ComplianceResult screen(ComplianceScreeningCommand command);

    /**
     * Screens a transaction with the facts computed by compliance (v2): the
     * sanctions and PEP flags from the customer's normalised name against the
     * compliance-owned lists, KYC from the customer context. A replay of the
     * same transaction with the same customer, amount, currency and requester
     * returns the stored result; nothing is looked up again.
     *
     * @throws TransactionAlreadyScreenedException for a conflicting replay
     * @throws CustomerNotFoundException when the customer context has no such customer
     * @throws ScreeningSourceUnavailableException when a fact cannot be resolved (customer context down, list empty or stale)
     */
    ComplianceResult screenWithComputedFacts(ComputedFactsScreeningCommand command);

    Optional<ComplianceResult> findByTransactionId(String transactionId);
}
