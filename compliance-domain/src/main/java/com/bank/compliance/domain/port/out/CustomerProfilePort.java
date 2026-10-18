package com.bank.compliance.domain.port.out;

import com.bank.compliance.domain.CustomerProfile;
import com.bank.compliance.domain.ScreeningSourceUnavailableException;

import java.util.Optional;

/**
 * Reads a customer's name and KYC status from the customer context
 * (svc-cus-profile-kyc) through its published API. An anti-corruption port:
 * the adapter translates the customer contract into {@link CustomerProfile}.
 */
public interface CustomerProfilePort {

    /**
     * @return the profile, or empty when the customer context has no such customer
     * @throws ScreeningSourceUnavailableException when the customer context cannot give a definite answer
     */
    Optional<CustomerProfile> findCustomer(String customerId);
}
