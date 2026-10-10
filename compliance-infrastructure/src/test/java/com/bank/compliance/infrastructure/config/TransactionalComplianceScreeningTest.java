package com.bank.compliance.infrastructure.config;

import com.bank.compliance.domain.ComplianceDecision;
import com.bank.compliance.domain.ComplianceResult;
import com.bank.compliance.domain.ComplianceResultFixtures;
import com.bank.compliance.domain.port.in.ComplianceScreeningCommand;
import com.bank.compliance.domain.port.in.ComplianceScreeningUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TransactionalComplianceScreeningTest {

    private final ComplianceScreeningUseCase delegate = mock(ComplianceScreeningUseCase.class);
    private final RecordingTransactions readWrite = new RecordingTransactions();
    private final RecordingTransactions readOnly = new RecordingTransactions();
    private final TransactionalComplianceScreening useCase =
            new TransactionalComplianceScreening(delegate, readWrite, readOnly);

    private final ComplianceScreeningCommand command =
            new ComplianceScreeningCommand("TX-TX", "C-1", new BigDecimal("10.00"), "AED", false, true, false, com.bank.compliance.domain.ComplianceResultFixtures.PAYMENTS);

    @Test
    void screeningRunsInsideTheReadWriteTransaction() {
        ComplianceResult result = ComplianceResultFixtures.result("TX-TX", "C-1", ComplianceDecision.PASS, List.of("COMPLIANT"));
        when(delegate.screen(command)).thenAnswer(inv -> {
            assertThat(readWrite.active).as("delegate runs inside the transaction").isTrue();
            return result;
        });

        assertThat(useCase.screen(command)).isSameAs(result);
        assertThat(readWrite.calls).isEqualTo(1);
        assertThat(readOnly.calls).isZero();
    }

    @Test
    void aFailureInsideTheTransactionPropagatesSoTheTransactionRollsBack() {
        when(delegate.screen(command)).thenThrow(new IllegalStateException("duplicate transaction_id"));

        assertThatThrownBy(() -> useCase.screen(command)).hasMessage("duplicate transaction_id");
        assertThat(readWrite.failed).containsExactly(IllegalStateException.class);
    }

    @Test
    void lookupsRunInTheReadOnlyTransaction() {
        when(delegate.findByTransactionId("TX-TX")).thenReturn(Optional.empty());

        assertThat(useCase.findByTransactionId("TX-TX")).isEmpty();
        assertThat(readOnly.calls).isEqualTo(1);
        assertThat(readWrite.calls).isZero();
    }

    @Test
    void collaboratorsAreRequired() {
        assertThatThrownBy(() -> new TransactionalComplianceScreening(null, readWrite, readOnly))
                .hasMessageContaining("delegate");
        assertThatThrownBy(() -> new TransactionalComplianceScreening(delegate, null, readOnly))
                .hasMessageContaining("readWrite");
        assertThatThrownBy(() -> new TransactionalComplianceScreening(delegate, readWrite, null))
                .hasMessageContaining("readOnly");
    }

    /** Runs the callback inline and records how it ended, like TransactionTemplate would. */
    private static final class RecordingTransactions implements TransactionOperations {
        int calls;
        boolean active;
        final List<Class<?>> failed = new ArrayList<>();

        @Override
        public <T> T execute(TransactionCallback<T> action) {
            calls++;
            active = true;
            TransactionStatus status = new SimpleTransactionStatus();
            try {
                return action.doInTransaction(status);
            } catch (RuntimeException e) {
                failed.add(e.getClass());
                throw e;
            } finally {
                active = false;
            }
        }
    }
}
