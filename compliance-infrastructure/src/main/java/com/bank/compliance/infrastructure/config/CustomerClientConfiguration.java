package com.bank.compliance.infrastructure.config;

import com.bank.compliance.domain.port.out.CustomerProfilePort;
import com.bank.compliance.infrastructure.external.ClientCredentialsTokenProvider;
import com.bank.compliance.infrastructure.external.CustomerProfileHttpAdapter;
import com.bank.compliance.infrastructure.external.ServiceTokenProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;

/**
 * The outbound client to the customer context (compliance.customer.*) and
 * the client-credentials token it presents (compliance.identity.*). Short
 * timeouts: a screening waits for the customer context at most this long
 * before it is refused as SCREENING_SOURCE_UNAVAILABLE and the caller retries.
 */
@Configuration
public class CustomerClientConfiguration {

    @Bean
    ServiceTokenProvider customerServiceTokenProvider(
            RestClient.Builder restClientBuilder,
            @Value("${compliance.identity.token-uri}") String tokenUri,
            @Value("${compliance.identity.client-id}") String clientId,
            @Value("${compliance.identity.client-secret}") String clientSecret,
            @Value("${compliance.customer.connect-timeout}") Duration connectTimeout,
            @Value("${compliance.customer.read-timeout}") Duration readTimeout,
            @Qualifier("screeningClock") Clock clock
    ) {
        RestClient http = restClientBuilder.clone().requestFactory(requestFactory(connectTimeout, readTimeout)).build();
        return new ClientCredentialsTokenProvider(http, tokenUri, clientId, clientSecret, clock);
    }

    @Bean
    CustomerProfilePort customerProfilePort(
            RestClient.Builder restClientBuilder,
            @Value("${compliance.customer.base-url}") String baseUrl,
            @Value("${compliance.customer.connect-timeout}") Duration connectTimeout,
            @Value("${compliance.customer.read-timeout}") Duration readTimeout,
            ServiceTokenProvider customerServiceTokenProvider
    ) {
        RestClient http = restClientBuilder.clone()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory(connectTimeout, readTimeout))
                .build();
        return new CustomerProfileHttpAdapter(http, customerServiceTokenProvider);
    }

    private static ClientHttpRequestFactory requestFactory(Duration connectTimeout, Duration readTimeout) {
        return ClientHttpRequestFactories.get(ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(connectTimeout)
                .withReadTimeout(readTimeout));
    }
}
