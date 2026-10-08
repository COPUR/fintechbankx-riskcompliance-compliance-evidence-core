package com.bank.compliance.application;

/**
 * Thrown when a transaction id that was already screened is sent again for a
 * different customer or with different screening facts. The recorded result
 * is never replaced.
 */
public class TransactionAlreadyScreenedException extends RuntimeException {

    public TransactionAlreadyScreenedException(String transactionId) {
        super("Transaction " + transactionId + " was already screened for a different customer or different facts");
    }
}
