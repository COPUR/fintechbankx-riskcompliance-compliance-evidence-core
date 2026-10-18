package com.bank.compliance.domain;

/**
 * A fact the screening needs cannot be resolved right now: the customer
 * context is unreachable or failing, or a list is empty or stale. The
 * screening is not made and nothing is recorded; the caller retries later
 * (503 SCREENING_SOURCE_UNAVAILABLE). Never a PASS by default. The message
 * names the source, never a customer or transaction id.
 */
public class ScreeningSourceUnavailableException extends RuntimeException {

    public ScreeningSourceUnavailableException(String reason) {
        super(reason);
    }
}
