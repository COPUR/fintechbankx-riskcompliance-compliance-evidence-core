package com.bank.compliance.infrastructure.external;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** The token endpoint's answer to a client-credentials grant (RFC 6749 section 5.1); only what is used. */
@JsonIgnoreProperties(ignoreUnknown = true)
record TokenResponseDto(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("expires_in") Long expiresIn
) {
}
