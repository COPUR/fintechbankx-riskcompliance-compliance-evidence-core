package com.bank.compliance.infrastructure.config;

import com.bank.compliance.domain.ComplianceResult;
import com.bank.compliance.domain.port.in.ComplianceScreeningCommand;
import com.bank.compliance.domain.port.in.ComplianceScreeningUseCase;
import org.springframework.transaction.support.TransactionOperations;

import java.util.Objects;
import java.util.Optional;

/**
 * Transaction boundary of the screening use case. The application service is
 * framework-free, so the boundary lives here: the lookup, the insert of the
 * result and the outbox row run in one database transaction.
 *
 * If a concurrent screening of the same transaction wins the unique
 * transaction_id index, the insert fails, the whole transaction rolls back and
 * no outbox row is left behind for the losing request.
 */
public class TransactionalComplianceScreening implements ComplianceScreeningUseCase {

    private final ComplianceScreeningUseCase delegate;
    private final TransactionOperations readWrite;
    private final TransactionOperations readOnly;

    public TransactionalComplianceScreening(ComplianceScreeningUseCase delegate,
                                            TransactionOperations readWrite,
                                            TransactionOperations readOnly) {
        this.delegate = Objects.requireNonNull(delegate, "delegate is required");
        this.readWrite = Objects.requireNonNull(readWrite, "readWrite is required");
        this.readOnly = Objects.requireNonNull(readOnly, "readOnly is required");
    }

    @Override
    public ComplianceResult screen(ComplianceScreeningCommand command) {
        return readWrite.execute(status -> delegate.screen(command));
    }

    @Override
    public Optional<ComplianceResult> findByTransactionId(String transactionId) {
        return readOnly.execute(status -> delegate.findByTransactionId(transactionId));
    }
}
