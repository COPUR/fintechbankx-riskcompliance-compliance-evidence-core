package com.bank.compliance;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Boots the whole service against PostgreSQL: Flyway builds sc_cmp_evidence,
 * Hibernate validates the entity against it, and a payment service screens
 * transactions over HTTP.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ComplianceServiceIT {

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

    @BeforeEach
    void cleanTables() {
        jdbc.update("delete from sc_cmp_evidence.compliance_screening");
    }

    @Test
    void flywayCreatesOnlyTheTablesThisServiceOwns() {
        List<String> tables = jdbc.queryForList("""
            select table_name from information_schema.tables
            where table_schema = 'sc_cmp_evidence' and table_name <> 'flyway_schema_history'
            order by table_name
            """, String.class);

        assertThat(tables).containsExactly("compliance_screening", "legacy_compliance_report");
    }

    @Test
    void screeningIsStoredOnceAndReturnedOnRetry() throws Exception {
        String first = screen("PAY-CMP-1", "C-1", "12000.00", false, true, true)
            .andExpect(status().isCreated())
            .andExpect(header().string("x-fapi-interaction-id", "it-interaction-1"))
            .andExpect(jsonPath("$.decision").value("REVIEW"))
            .andReturn().getResponse().getContentAsString();
        String retry = screen("PAY-CMP-1", "C-1", "12000.00", false, true, true)
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        assertThat(retry).isEqualTo(first);
        assertThat(jdbc.queryForObject("select count(*) from sc_cmp_evidence.compliance_screening", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select reasons::text from sc_cmp_evidence.compliance_screening", String.class))
            .contains("PEP_HIGH_VALUE_REVIEW");

        mvc.perform(asService(get("/api/v1/compliance/screenings/{id}", "PAY-CMP-1")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.decision").value("REVIEW"));
    }

    @Test
    void reusedTransactionIdForAnotherCustomerIsRefused() throws Exception {
        screen("PAY-CMP-2", "C-1", "100.00", true, true, false).andExpect(status().isCreated())
            .andExpect(jsonPath("$.decision").value("FAIL"));

        screen("PAY-CMP-2", "C-2", "100.00", false, true, false)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("TRANSACTION_ALREADY_SCREENED"));
    }

    @Test
    void unknownTransactionIsA404WithTheInteractionId() throws Exception {
        mvc.perform(asService(get("/api/v1/compliance/screenings/{id}", "PAY-MISSING")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("SCREENING_NOT_FOUND"))
            .andExpect(jsonPath("$.interactionId").value("it-interaction-1"));
    }

    @Test
    void onlyServicesAndComplianceStaffMayScreenAndAuditorsMayRead() throws Exception {
        mvc.perform(post("/api/v1/compliance/screen")
                .with(jwt().jwt(j -> j.subject("customer-1")).authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("PAY-CMP-3", "C-1", "10.00", false, true, false)))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/compliance/screen")
                .with(jwt().jwt(j -> j.subject("auditor-1")).authorities(new SimpleGrantedAuthority("ROLE_AUDITOR")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("PAY-CMP-3", "C-1", "10.00", false, true, false)))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/compliance/screenings/{id}", "PAY-ANY")
                .with(jwt().jwt(j -> j.subject("auditor-1")).authorities(new SimpleGrantedAuthority("ROLE_AUDITOR"))))
            .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/compliance/screenings/{id}", "PAY-ANY")).andExpect(status().isUnauthorized());
    }

    private ResultActions screen(String transactionId, String customerId, String amount,
                                 boolean sanctionsHit, boolean kycVerified, boolean pep) throws Exception {
        return mvc.perform(asService(post("/api/v1/compliance/screen"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(transactionId, customerId, amount, sanctionsHit, kycVerified, pep)));
    }

    private static String body(String transactionId, String customerId, String amount,
                               boolean sanctionsHit, boolean kycVerified, boolean pep) {
        return """
            {"transactionId": "%s", "customerId": "%s", "amount": %s, "sanctionsHit": %s, "kycVerified": %s, "pep": %s}
            """.formatted(transactionId, customerId, amount, sanctionsHit, kycVerified, pep);
    }

    private static MockHttpServletRequestBuilder asService(MockHttpServletRequestBuilder request) {
        return request.header("x-fapi-interaction-id", "it-interaction-1")
            .with(jwt().jwt(j -> j.subject("svc-pay-initiation-settlement")).authorities(new SimpleGrantedAuthority("ROLE_SERVICE")));
    }
}
