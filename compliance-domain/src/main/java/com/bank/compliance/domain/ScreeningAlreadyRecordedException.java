package com.bank.compliance.domain;

/**
 * Raised by the result repository when a result for the transaction was
 * stored by a concurrent request first. Nothing of the losing request is kept;
 * a retry returns the stored result.
 */
public class ScreeningAlreadyRecordedException extends RuntimeException {

    public ScreeningAlreadyRecordedException(String transactionId, Throwable cause) {
        super("A screening for transaction " + transactionId + " was recorded concurrently", cause);
    }
}
