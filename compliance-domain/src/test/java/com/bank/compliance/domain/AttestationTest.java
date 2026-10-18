package com.bank.compliance.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AttestationTest {

    @Test
    void aServiceAttestsAsCallerUnderItsClientId() {
        Attestation attestation = Attestation.byService("svc-pay-initiation-settlement");

        assertThat(attestation.source()).isEqualTo(AttestationSource.CALLER_ATTESTED);
        assertThat(attestation.attestedBy()).isEqualTo("svc-pay-initiation-settlement");
    }

    @Test
    void staffAttestUnderTheirSubject() {
        Attestation attestation = Attestation.byStaff("8d0c6a4e-2f7b-4c1e-9a43-5b2f0d9e7c11");

        assertThat(attestation.source()).isEqualTo(AttestationSource.STAFF_ATTESTED);
        assertThat(attestation.attestedBy()).isEqualTo("8d0c6a4e-2f7b-4c1e-9a43-5b2f0d9e7c11");
    }

    /**
     * v2: compliance computes the sanctions, PEP and KYC facts itself; the
     * attestation records that (COMPUTED) and who asked for the screening.
     */
    @Test
    void computedFactsNameTheRequesterNotAFactSource() {
        Attestation attestation = Attestation.computed("svc-pay-initiation-settlement");

        assertThat(attestation.source()).isEqualTo(AttestationSource.COMPUTED);
        assertThat(attestation.attestedBy()).isEqualTo("svc-pay-initiation-settlement");
        assertThat(AttestationSource.values()).containsExactly(AttestationSource.CALLER_ATTESTED,
                AttestationSource.STAFF_ATTESTED, AttestationSource.COMPUTED);
    }

    @Test
    void whoAttestedIsRequiredAndFitsItsColumn() {
        assertThatThrownBy(() -> Attestation.byService(null)).hasMessage("attestedBy is required");
        assertThatThrownBy(() -> Attestation.byStaff(" ")).hasMessage("attestedBy is required");
        assertThatThrownBy(() -> Attestation.byService("s".repeat(129))).hasMessage("attestedBy must be at most 128 characters");
        assertThatThrownBy(() -> new Attestation(null, "svc-pay-initiation-settlement")).hasMessageContaining("source");
    }
}
