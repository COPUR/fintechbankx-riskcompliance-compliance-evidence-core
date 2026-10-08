package com.bank.compliance.domain;

/**
 * Where the screening facts came from. Today every fact (sanctions, PEP and
 * KYC flags, amount) is attested by the calling service; see
 * docs/architecture/decisions/0001-screening-facts-are-caller-attested.md.
 * New sources (for example KYC resolved from svc-cus-profile-kyc) are added
 * here when compliance looks the facts up itself.
 */
public enum AttestationSource {
    CALLER_ATTESTED
}
