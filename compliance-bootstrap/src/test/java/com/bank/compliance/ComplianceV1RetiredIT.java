package com.bank.compliance;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * With the shipped default (COMPLIANCE_V1_CALLER_FACTS_ENABLED unset, so
 * false) the caller-attested v1 screening is gone: payments must send ids and
 * amounts to v2. Reading stored results on v1 is unaffected.
 */
@SpringBootTest(properties = {"compliance.outbox.relay.enabled=false", "fintechbankx.tls.enforce=false"})
@AutoConfigureMockMvc
class ComplianceV1RetiredIT {

    @BeforeAll
    static void requireDatabase() {
        PostgresTestDatabase.assumeAvailable();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        PostgresTestDatabase.register(registry);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @MockBean KafkaTemplate<String, String> kafka;

    @Test
    void theV1ScreeningIsGoneByDefaultAndNamesV2() throws Exception {
        int before = jdbc.queryForObject("select count(*) from sc_cmp_evidence.compliance_screening", Integer.class);

        mvc.perform(post("/api/v1/compliance/screen").with(payments())
                .header("x-fapi-interaction-id", "it-v1-gone")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"transactionId": "PAY-V1-GONE", "customerId": "C-1", "amount": 10.00, "currency": "USD",
                     "sanctionsHit": false, "kycVerified": true, "pep": false}
                    """))
            .andExpect(status().isGone())
            .andExpect(jsonPath("$.code").value("ENDPOINT_GONE"))
            .andExpect(jsonPath("$.message").value(containsString("POST /api/v2/compliance/screen")))
            .andExpect(jsonPath("$.interactionId").value("it-v1-gone"));

        assertThat(jdbc.queryForObject("select count(*) from sc_cmp_evidence.compliance_screening", Integer.class)).isEqualTo(before);
        assertThat(jdbc.queryForObject(
            "select count(*) from sc_cmp_evidence.compliance_screening where transaction_id = 'PAY-V1-GONE'", Integer.class)).isZero();
    }

    @Test
    void readingResultsOnV1StaysAvailable() throws Exception {
        mvc.perform(get("/api/v1/compliance/screenings/{id}", "PAY-V1-UNKNOWN").with(payments()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("SCREENING_NOT_FOUND"));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor payments() {
        return jwt().jwt(j -> j.subject("svc-pay-initiation-settlement").claim("azp", "svc-pay-initiation-settlement"))
            .authorities(new SimpleGrantedAuthority("ROLE_SERVICE"));
    }
}
