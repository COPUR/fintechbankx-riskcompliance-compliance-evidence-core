package com.bank.compliance.domain;

/**
 * Domain rule violation: thrown when a transaction id that was already screened is sent again for a
 * different customer, with different screening facts or by a different caller.
 * The recorded result is never replaced. The message carries no ids: it
 * reaches logs and the API error body.
 */
public class TransactionAlreadyScreenedException extends RuntimeException {

    public TransactionAlreadyScreenedException() {
        super("This transaction was already screened for a different customer, different facts or a different caller");
    }
}
