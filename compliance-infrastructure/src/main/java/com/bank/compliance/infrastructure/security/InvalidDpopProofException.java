package com.bank.compliance.infrastructure.security;

/**
 * A DPoP proof is missing or fails verification. The message names the
 * failed check and never echoes token or proof contents.
 */
public class InvalidDpopProofException extends RuntimeException {

    public InvalidDpopProofException(String message) {
        super(message);
    }
}
