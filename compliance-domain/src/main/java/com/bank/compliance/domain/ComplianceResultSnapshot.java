package com.bank.compliance.domain;

import java.time.Instant;
import java.util.List;

/**
 * Persisted state of a {@link ComplianceResult}, used by persistence adapters
 * to rebuild the screening result exactly as it was recorded.
 */
public record ComplianceResultSnapshot(
        ComplianceResultId id,
        String transactionId,
        String customerId,
        ScreeningFacts facts,
        ComplianceDecision decision,
        List<String> reasons,
        String ruleSetVersion,
        Instant checkedAt
) {
}
