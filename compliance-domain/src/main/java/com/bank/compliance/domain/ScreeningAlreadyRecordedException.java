package com.bank.compliance.domain;

/**
 * Raised by the result repository when a result for the transaction was
 * stored by a concurrent request first. Nothing of the losing request is kept;
 * a retry returns the stored result. The message carries no ids.
 */
public class ScreeningAlreadyRecordedException extends RuntimeException {

    public ScreeningAlreadyRecordedException(Throwable cause) {
        super("A screening for this transaction was recorded concurrently", cause);
    }
}
