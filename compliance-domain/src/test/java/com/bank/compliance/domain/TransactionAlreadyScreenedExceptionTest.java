package com.bank.compliance.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionAlreadyScreenedExceptionTest {

    @Test
    void namesTheTransactionWhoseEvidenceIsKept() {
        assertThat(new TransactionAlreadyScreenedException("TX-7"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Transaction TX-7 was already screened for a different customer or different facts");
    }
}
