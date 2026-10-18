package com.bank.compliance.domain;

/**
 * Where the screening facts came from.
 * <ul>
 * <li>CALLER_ATTESTED: stated by a calling service (deprecated v1 API, served only
 *     while COMPLIANCE_V1_CALLER_FACTS_ENABLED is true);</li>
 * <li>STAFF_ATTESTED: stated by compliance staff screening by hand (v1);</li>
 * <li>COMPUTED: resolved by compliance itself (v2): sanctions and PEP by matching the
 *     customer's normalised name against the compliance-owned lists, KYC from the
 *     customer context. {@code attestedBy} then names the caller that requested the
 *     screening, and the evidence carries the list versions matched against.</li>
 * </ul>
 * See docs/architecture/decisions/0001-screening-facts-are-caller-attested.md and
 * 0003-compliance-computes-sanctions-pep-and-kyc.md.
 */
public enum AttestationSource {
    CALLER_ATTESTED,
    STAFF_ATTESTED,
    COMPUTED
}
