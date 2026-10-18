package com.bank.compliance.infrastructure.external;

import com.bank.compliance.domain.ScreeningSourceUnavailableException;

/**
 * Supplies the bearer token this service presents to another context's API.
 * Shipped implementation: {@link ClientCredentialsTokenProvider}.
 */
@FunctionalInterface
public interface ServiceTokenProvider {

    /** @throws ScreeningSourceUnavailableException when no token can be obtained */
    String bearerToken();
}
