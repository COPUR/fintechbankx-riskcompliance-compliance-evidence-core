package com.bank.compliance.domain.port.in;

import com.bank.compliance.domain.ComplianceResult;

import java.util.Optional;

public interface ComplianceScreeningUseCase {
    ComplianceResult screen(ComplianceScreeningCommand command);
    Optional<ComplianceResult> findByTransactionId(String transactionId);
}
