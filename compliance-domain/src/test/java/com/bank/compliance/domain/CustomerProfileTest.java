package com.bank.compliance.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What compliance reads from the customer context: the name to screen and the KYC status. */
class CustomerProfileTest {

    @Test
    void theFullNameIsNormalisedForListMatching() {
        CustomerProfile profile = new CustomerProfile("C-1", "Test", "Sanctioned Pérson", KycStatus.VERIFIED);

        assertThat(profile.fullName()).isEqualTo(NormalizedName.of("TEST SANCTIONED PERSON"));
    }

    @Test
    void onlyAVerifiedStatusCountsAsKycVerified() {
        assertThat(new CustomerProfile("C-1", "Test", "Dummy", KycStatus.VERIFIED).kycVerified()).isTrue();
        assertThat(new CustomerProfile("C-1", "Test", "Dummy", KycStatus.PENDING).kycVerified()).isFalse();
        assertThat(new CustomerProfile("C-1", "Test", "Dummy", KycStatus.REJECTED).kycVerified()).isFalse();
    }

    @Test
    void aProfileWithoutANameOrStatusCannotBeScreened() {
        assertThatThrownBy(() -> new CustomerProfile("C-1", " ", "Dummy", KycStatus.VERIFIED))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("firstName");
        assertThatThrownBy(() -> new CustomerProfile("C-1", "Test", null, KycStatus.VERIFIED))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("lastName");
        assertThatThrownBy(() -> new CustomerProfile("C-1", "Test", "Dummy", null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("kycStatus");
        assertThatThrownBy(() -> new CustomerProfile("", "Test", "Dummy", KycStatus.VERIFIED))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("customerId");
    }
}
