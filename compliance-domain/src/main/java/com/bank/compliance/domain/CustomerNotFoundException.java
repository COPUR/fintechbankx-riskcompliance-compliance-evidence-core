package com.bank.compliance.domain;

/**
 * The customer context has no customer with the id the caller sent. A
 * definite answer (404 from the owner), so a client error (422
 * CUSTOMER_NOT_FOUND), not a source outage; nothing is recorded. The message
 * carries no id.
 */
public class CustomerNotFoundException extends RuntimeException {

    public CustomerNotFoundException() {
        super("The customer context has no customer with this id");
    }
}
