package com.bank.compliance.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Exception messages reach logs and API error bodies; they never carry transaction or customer ids. */
class TransactionAlreadyScreenedExceptionTest {

    @Test
    void explainsTheConflictWithoutTheTransactionId() {
        assertThat(new TransactionAlreadyScreenedException())
                .isInstanceOf(RuntimeException.class)
                .hasMessage("This transaction was already screened for a different customer, different facts or a different caller");
    }

    @Test
    void aConcurrentDuplicateKeepsTheCauseButNotTheTransactionId() {
        IllegalStateException cause = new IllegalStateException("unique violation");

        assertThat(new ScreeningAlreadyRecordedException(cause))
                .hasMessage("A screening for this transaction was recorded concurrently")
                .hasCause(cause);
    }
}
