package com.bank.compliance;

import com.bank.compliance.domain.ComplianceDecision;
import com.bank.compliance.domain.ComplianceResult;
import com.bank.compliance.domain.ComplianceResultFixtures;
import com.bank.compliance.domain.port.out.ComplianceEventPublisher;
import com.bank.compliance.infrastructure.outbox.OutboxRelay;
import com.bank.compliance.infrastructure.outbox.SpringDataOutboxRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.slf4j.MDC;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Boots the whole service against PostgreSQL: Flyway builds sc_cmp_evidence,
 * Hibernate validates the entity against it, and a payment service screens
 * transactions over HTTP. Each new screening writes one event to the
 * transactional outbox in the same transaction; the relay is off here and is
 * driven explicitly against a mocked Kafka.
 */
// The TLS startup assertions are on by default; the test database has no TLS, so the test
// switch (test annotations only, never a shipped application*.yml) turns them off here.
// The deprecated v1 (caller-attested facts) is served here with the shipped default of its flag
// (COMPLIANCE_V1_CALLER_FACTS_ENABLED unset: true, so a deploy does not break payments still on v1);
// ComplianceV1RetiredIT sets it to false. The required list sources match the fixtures loadFreshLists() writes.
@SpringBootTest(properties = {"compliance.outbox.relay.enabled=false", "fintechbankx.tls.enforce=false",
        "compliance.lists.sanctions-required-sources=UN,OFAC-SDN,UAE-LOCAL", "compliance.lists.pep-required-sources=UAE-PEP"})
@AutoConfigureMockMvc
class ComplianceServiceIT {

    private static CustomerServiceStub customerService;

    @BeforeAll
    static void requireDatabase() {
        PostgresTestDatabase.assumeAvailable();
    }

    @AfterAll
    static void stopCustomerServiceStub() {
        if (customerService != null) {
            customerService.close();
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws IOException {
        PostgresTestDatabase.register(registry);
        // v2 reads the name and KYC status from the customer context over HTTP with the service's own token.
        customerService = CustomerServiceStub.start();
        registry.add("compliance.customer.base-url", () -> customerService.baseUrl());
        registry.add("compliance.identity.token-uri", () -> customerService.tokenUri());
        registry.add("compliance.identity.client-id", () -> "svc-cmp-evidence");
        registry.add("compliance.identity.client-secret", () -> "it-client-credential");
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ComplianceEventPublisher eventPublisher;
    @Autowired SpringDataOutboxRepository outbox;
    @Autowired PlatformTransactionManager transactionManager;
    @MockBean KafkaTemplate<String, String> kafka;

    @BeforeEach
    void cleanTables() {
        // TRUNCATE as the migration owner: screening rows are insert-only (row-level DELETE is refused by a
        // trigger) and the runtime role the service connects as has no TRUNCATE.
        PostgresTestDatabase.owner().execute("truncate sc_cmp_evidence.outbox_event, sc_cmp_evidence.compliance_screening");
        loadFreshLists();
        customerService.clear();
    }

    /** Fresh list loads, as the operator script leaves them (dummy names only). */
    private static void loadFreshLists() {
        JdbcTemplate owner = PostgresTestDatabase.owner();
        owner.execute("truncate sc_cmp_evidence.sanctions_list_entry, sc_cmp_evidence.pep_register_entry");
        owner.update("""
            insert into sc_cmp_evidence.sanctions_list_entry (normalized_name, display_name, list_source, list_version, loaded_at) values
              ('TEST SANCTIONED PERSON', 'Test Sanctioned Pérson', 'UN', '2026-10-17', now() - interval '3 hours'),
              ('TEST OFAC DUMMY', 'Test OFAC Dummy', 'OFAC-SDN', '1842', now() - interval '2 hours'),
              ('TEST UAE LOCAL DUMMY', 'Test UAE-Local Dummy', 'UAE-LOCAL', '2026-10-18', now() - interval '1 hour')
            """);
        owner.update("""
            insert into sc_cmp_evidence.pep_register_entry (normalized_name, display_name, list_source, list_version, loaded_at) values
              ('TEST PEP PERSON', 'Test Pep Person', 'UAE-PEP', '2026-10', now() - interval '10 days')
            """);
    }

    @Test
    void flywayCreatesOnlyTheTablesThisServiceOwns() {
        List<String> tables = jdbc.queryForList("""
            select table_name from information_schema.tables
            where table_schema = 'sc_cmp_evidence' and table_name <> 'flyway_schema_history'
            order by table_name
            """, String.class);

        assertThat(tables).containsExactly("compliance_screening", "legacy_compliance_report", "outbox_event",
            "pep_register_entry", "sanctions_list_entry");
    }

    // ---- v2: compliance computes sanctions, PEP and KYC from their owners ----

    @Test
    void v2ScreensWithFactsComputedFromTheCustomerAndTheListsAndPublishesTheOutcomeOnly() throws Exception {
        customerService.customer("C-CLEAN", "Test", "Clean Person", "VERIFIED");

        String response = screenV2("PAY-V2-CLEAN", "C-CLEAN", "12000.00", "USD")
            .andExpect(status().isCreated())
            .andExpect(header().string("x-fapi-interaction-id", "it-interaction-1"))
            .andExpect(jsonPath("$.decision").value("PASS"))
            .andExpect(jsonPath("$.reasons[0]").value("COMPLIANT"))
            .andExpect(jsonPath("$.attestation").value("COMPUTED"))
            .andExpect(jsonPath("$.attestedBy").value("svc-pay-initiation-settlement"))
            .andExpect(jsonPath("$.listVersions.length()").value(4))
            .andExpect(jsonPath("$.listVersions[?(@.list == 'SANCTIONS')].source", org.hamcrest.Matchers.containsInAnyOrder("UN", "OFAC-SDN", "UAE-LOCAL")))
            .andExpect(jsonPath("$.listVersions[?(@.list == 'PEP')].version").value("2026-10"))
            .andExpect(jsonPath("$.sanctionsHit").doesNotExist())
            .andReturn().getResponse().getContentAsString();

        // The customer context was read with the service's own token and the request's interaction id.
        assertThat(customerService.lastAuthorization.get()).isEqualTo("Bearer " + CustomerServiceStub.TOKEN);
        assertThat(customerService.lastInteractionId.get()).isEqualTo("it-interaction-1");
        assertThat(customerService.lastTokenRequestBody.get()).contains("grant_type=client_credentials").contains("client_id=svc-cmp-evidence");

        assertThat(jdbc.queryForMap("""
                select sanctions_hit, kyc_verified, pep, attestation_source, attested_by, rule_set_version,
                       jsonb_array_length(list_versions) as lists, list_versions::text as list_versions
                from sc_cmp_evidence.compliance_screening where transaction_id = 'PAY-V2-CLEAN'
                """))
            .containsEntry("sanctions_hit", false).containsEntry("kyc_verified", true).containsEntry("pep", false)
            .containsEntry("attestation_source", "COMPUTED").containsEntry("attested_by", "svc-pay-initiation-settlement")
            .containsEntry("rule_set_version", "cmp-screening-rules-v2").containsEntry("lists", 4)
            .hasEntrySatisfying("list_versions", v -> assertThat(v.toString()).contains("\"UN\"", "\"2026-10-17\"", "\"UAE-PEP\"", "\"loadedAt\""));

        Map<String, Object> event = jdbc.queryForMap("""
            select payload->'data'->>'decision' as decision,
                   jsonb_exists(payload->'data', 'reasons') as has_reasons,
                   jsonb_exists(payload->'data', 'reasonCodes') as has_reason_codes,
                   jsonb_exists(payload->'data', 'facts') as has_facts,
                   jsonb_exists(payload->'data', 'amount') as has_amount,
                   jsonb_exists(payload->'data', 'listVersions') as has_list_versions,
                   payload::text as payload
            from sc_cmp_evidence.outbox_event
            """);
        assertThat(event).containsEntry("decision", "PASS").containsEntry("has_reasons", false).containsEntry("has_reason_codes", false)
            .containsEntry("has_facts", false).containsEntry("has_amount", false).containsEntry("has_list_versions", false);
        assertThat(event.get("payload").toString()).doesNotContain("12000", "USD", "sanctions", "kyc", "\"pep\"", "COMPUTED", "UN", "2026-10-17", "COMPLIANT");
        assertThat(response).contains("COMPLIANT");
    }

    /** The list entry carries diacritics and a hyphen; the customer name is written differently. Both normalise to one value. */
    @Test
    void v2MatchesTheNormalisedNameAgainstTheSanctionsListAndFails() throws Exception {
        customerService.customer("C-SANC", "tést", "Sanctioned-Person", "VERIFIED");

        screenV2("PAY-V2-SANC", "C-SANC", "10.00", "USD")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.decision").value("FAIL"))
            .andExpect(jsonPath("$.reasons[0]").value("SANCTIONS_HIT"))
            .andExpect(jsonPath("$.attestation").value("COMPUTED"));

        assertThat(jdbc.queryForMap("select sanctions_hit, pep from sc_cmp_evidence.compliance_screening where transaction_id = 'PAY-V2-SANC'"))
            .containsEntry("sanctions_hit", true).containsEntry("pep", false);
        assertThat(jdbc.queryForObject("select payload->'data'->>'decision' from sc_cmp_evidence.outbox_event", String.class)).isEqualTo("FAIL");
    }

    @Test
    void v2PepAndKycFactsComeFromTheRegisterAndTheCustomerContext() throws Exception {
        customerService.customer("C-PEP", "Test", "Pep Person", "VERIFIED");
        customerService.customer("C-PENDING", "Test", "Pending Person", "PENDING");

        screenV2("PAY-V2-PEP", "C-PEP", "10000.01", "USD")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.decision").value("REVIEW"))
            .andExpect(jsonPath("$.reasons[0]").value("PEP_HIGH_VALUE_REVIEW"));
        screenV2("PAY-V2-PEP-LOW", "C-PEP", "10000.00", "USD")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.decision").value("PASS"));
        screenV2("PAY-V2-PENDING", "C-PENDING", "10.00", "USD")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.decision").value("FAIL"))
            .andExpect(jsonPath("$.reasons[0]").value("KYC_NOT_VERIFIED"));

        assertThat(jdbc.queryForMap("select pep, kyc_verified from sc_cmp_evidence.compliance_screening where transaction_id = 'PAY-V2-PEP'"))
            .containsEntry("pep", true).containsEntry("kyc_verified", true);
        assertThat(jdbc.queryForMap("select pep, kyc_verified from sc_cmp_evidence.compliance_screening where transaction_id = 'PAY-V2-PENDING'"))
            .containsEntry("pep", false).containsEntry("kyc_verified", false);
    }

    /** Never PASS by default: a stale or empty list is a 503 and nothing is recorded or published. */
    @Test
    void v2StaleOrEmptyListsAreA503AndNothingIsStored() throws Exception {
        customerService.customer("C-CLEAN", "Test", "Clean Person", "VERIFIED");
        JdbcTemplate owner = PostgresTestDatabase.owner();

        // One sanctions source older than COMPLIANCE_LIST_MAX_AGE_SANCTIONS (P1D) makes the whole list stale.
        owner.update("update sc_cmp_evidence.sanctions_list_entry set loaded_at = now() - interval '25 hours' where list_source = 'OFAC-SDN'");
        screenV2("PAY-V2-STALE", "C-CLEAN", "10.00", "USD")
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("SCREENING_SOURCE_UNAVAILABLE"))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("sanctions list is stale")))
            .andExpect(jsonPath("$.interactionId").value("it-interaction-1"));

        // An empty PEP register.
        loadFreshLists();
        owner.execute("truncate sc_cmp_evidence.pep_register_entry");
        screenV2("PAY-V2-EMPTY", "C-CLEAN", "10.00", "USD")
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("SCREENING_SOURCE_UNAVAILABLE"))
            .andExpect(jsonPath("$.message").value("The PEP register is empty"));

        // A PEP register older than COMPLIANCE_LIST_MAX_AGE_PEP (P35D).
        loadFreshLists();
        owner.update("update sc_cmp_evidence.pep_register_entry set loaded_at = now() - interval '36 days'");
        screenV2("PAY-V2-OLD-PEP", "C-CLEAN", "10.00", "USD")
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("PEP register is stale")));

        assertThat(jdbc.queryForObject("select count(*) from sc_cmp_evidence.compliance_screening", Integer.class)).isZero();
        assertThat(outboxRows()).isZero();
    }

    /** A partial set of lists never screens as complete: a required source that is missing is a 503, hit or no hit. */
    @Test
    void v2AMissingRequiredSourceIsA503ForAHitAndForANoHit() throws Exception {
        customerService.customer("C-CLEAN", "Test", "Clean Person", "VERIFIED");
        customerService.customer("C-SANC", "Test", "Sanctioned Person", "VERIFIED");
        PostgresTestDatabase.owner().update("delete from sc_cmp_evidence.sanctions_list_entry where list_source = 'OFAC-SDN'");

        screenV2("PAY-V2-NO-OFAC", "C-CLEAN", "10.00", "USD")
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("SCREENING_SOURCE_UNAVAILABLE"))
            .andExpect(jsonPath("$.message").value("The sanctions list is missing the required source OFAC-SDN"));
        screenV2("PAY-V2-NO-OFAC-HIT", "C-SANC", "10.00", "USD")
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.message").value("The sanctions list is missing the required source OFAC-SDN"));

        PostgresTestDatabase.owner().update("delete from sc_cmp_evidence.pep_register_entry where list_source = 'UAE-PEP'");
        PostgresTestDatabase.owner().update("""
            insert into sc_cmp_evidence.pep_register_entry (normalized_name, display_name, list_source, list_version, loaded_at)
            values ('TEST OTHER PEP', 'Test Other Pep', 'OTHER-PEP', '1', now())
            """);
        loadFreshLists();
        PostgresTestDatabase.owner().update("update sc_cmp_evidence.pep_register_entry set list_source = 'OTHER-PEP' where list_source = 'UAE-PEP'");
        screenV2("PAY-V2-NO-UAE-PEP", "C-CLEAN", "10.00", "USD")
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.message").value("The PEP register is missing the required source UAE-PEP"));

        assertThat(jdbc.queryForObject("select count(*) from sc_cmp_evidence.compliance_screening", Integer.class)).isZero();
        assertThat(outboxRows()).isZero();
    }

    /** v2 has the same gate as v1: a verified token, and a listed SERVICE client or compliance staff. */
    @Test
    void v2NeedsATokenAndAListedServiceOrComplianceStaff() throws Exception {
        customerService.customer("C-CLEAN", "Test", "Clean Person", "VERIFIED");
        String body = bodyV2("PAY-V2-AUTHZ", "C-CLEAN", "10.00", "USD");

        mvc.perform(post("/api/v2/compliance/screen").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v2/compliance/screen").contentType(MediaType.APPLICATION_JSON).content(body)
                .with(jwt().jwt(j -> j.subject("auditor-1")).authorities(new SimpleGrantedAuthority("ROLE_AUDITOR"))))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/v2/compliance/screen").contentType(MediaType.APPLICATION_JSON).content(body)
                .with(jwt().jwt(j -> j.subject("customer-1")).authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/v2/compliance/screen").contentType(MediaType.APPLICATION_JSON).content(body)
                .with(jwt().jwt(j -> j.subject("svc-rsk-decisioning").claim("azp", "svc-rsk-decisioning"))
                    .authorities(new SimpleGrantedAuthority("ROLE_SERVICE"))))
            .andExpect(status().isForbidden());

        assertThat(customerService.lastAuthorization.get()).as("no source consulted for a refused caller").isNull();
        assertThat(jdbc.queryForObject("select count(*) from sc_cmp_evidence.compliance_screening", Integer.class)).isZero();
        assertThat(outboxRows()).isZero();
    }

    @Test
    void v2UnknownCustomerIsA422AndAFailingCustomerServiceIsA503NothingStored() throws Exception {
        screenV2("PAY-V2-NOCUST", "C-UNKNOWN", "10.00", "USD")
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("CUSTOMER_NOT_FOUND"))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("C-UNKNOWN"))));

        customerService.customer("C-CLEAN", "Test", "Clean Person", "VERIFIED");
        customerService.failNextWith(500);
        screenV2("PAY-V2-DOWN", "C-CLEAN", "10.00", "USD")
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("SCREENING_SOURCE_UNAVAILABLE"))
            .andExpect(jsonPath("$.message").value("The customer profile service answered HTTP 500"));

        assertThat(jdbc.queryForObject("select count(*) from sc_cmp_evidence.compliance_screening", Integer.class)).isZero();
        assertThat(outboxRows()).isZero();
    }

    @Test
    void v2ReplayReturnsTheStoredResultAndAConflictingReplayIsRefused() throws Exception {
        customerService.customer("C-PEP", "Test", "Pep Person", "VERIFIED");
        String first = screenV2("PAY-V2-REPLAY", "C-PEP", "12000.00", "USD")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.decision").value("REVIEW"))
            .andReturn().getResponse().getContentAsString();

        // The replay is answered from the evidence: the lists are not consulted again, even stale or empty.
        PostgresTestDatabase.owner().execute("truncate sc_cmp_evidence.sanctions_list_entry");
        customerService.clear();
        String replay = screenV2("PAY-V2-REPLAY", "C-PEP", "12000", "USD")
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        assertThat(replay).isEqualTo(first);

        screenV2("PAY-V2-REPLAY", "C-PEP", "12000.01", "USD")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("TRANSACTION_ALREADY_SCREENED"));
        screenV2("PAY-V2-REPLAY", "C-PEP", "12000.00", "AED")
            .andExpect(status().isConflict());
        screenV2("PAY-V2-REPLAY", "C-OTHER", "12000.00", "USD")
            .andExpect(status().isConflict());
        // Another requester for the same transaction is not handed the stored result.
        mvc.perform(post("/api/v2/compliance/screen").with(officer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyV2("PAY-V2-REPLAY", "C-PEP", "12000.00", "USD")))
            .andExpect(status().isConflict());

        assertThat(jdbc.queryForObject("select count(*) from sc_cmp_evidence.compliance_screening", Integer.class)).isEqualTo(1);
        assertThat(outboxRows()).isEqualTo(1);
    }

    /** A transaction screened on v1 (caller-attested) is not replayable on v2, and the other way round. */
    @Test
    void v1AndV2ResultsForOneTransactionDoNotMix() throws Exception {
        customerService.customer("C-1", "Test", "Clean Person", "VERIFIED");
        screen("PAY-MIX-1", "C-1", "10.00", false, true, false).andExpect(status().isCreated());
        screenV2("PAY-MIX-1", "C-1", "10.00", "USD")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("TRANSACTION_ALREADY_SCREENED"));

        screenV2("PAY-MIX-2", "C-1", "10.00", "USD").andExpect(status().isCreated());
        screen("PAY-MIX-2", "C-1", "10.00", false, true, false)
            .andExpect(status().isConflict());

        assertThat(jdbc.queryForObject("select count(*) from sc_cmp_evidence.compliance_screening", Integer.class)).isEqualTo(2);
    }

    @Test
    void v2CallerStatedFactsAreRefusedWithA400() throws Exception {
        customerService.customer("C-1", "Test", "Clean Person", "VERIFIED");

        mvc.perform(asService(post("/api/v2/compliance/screen"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"transactionId": "PAY-V2-OLD-SHAPE", "customerId": "C-1", "amount": 10.00, "currency": "USD", "sanctionsHit": false}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.message").value("sanctionsHit is not accepted: compliance computes it"));
        assertThat(jdbc.queryForObject("select count(*) from sc_cmp_evidence.compliance_screening", Integer.class)).isZero();
    }

    /** The lists are loaded by the operator script as the owner; the service only reads them. */
    @Test
    void theRuntimeRoleOnlyReadsTheScreeningLists() {
        JdbcTemplate runtime = PostgresTestDatabase.runtime();

        assertThat(runtime.queryForObject("select count(*) from sc_cmp_evidence.sanctions_list_entry", Integer.class)).isEqualTo(3);
        assertThat(runtime.queryForObject("select count(*) from sc_cmp_evidence.pep_register_entry", Integer.class)).isEqualTo(1);
        for (String table : List.of("sanctions_list_entry", "pep_register_entry")) {
            assertThatThrownBy(() -> runtime.update("insert into sc_cmp_evidence." + table
                    + " (normalized_name, display_name, list_source, list_version, loaded_at) values ('TEST PLANTED', 'Test Planted', 'X', '1', now())"))
                .rootCause().hasMessageContaining("permission denied for table " + table);
            assertThatThrownBy(() -> runtime.update("delete from sc_cmp_evidence." + table))
                .rootCause().hasMessageContaining("permission denied for table " + table);
            assertThatThrownBy(() -> runtime.update("update sc_cmp_evidence." + table + " set loaded_at = now()"))
                .rootCause().hasMessageContaining("permission denied for table " + table);
            assertThatThrownBy(() -> runtime.execute("truncate sc_cmp_evidence." + table))
                .rootCause().hasMessageContaining("permission denied for table " + table);
        }
    }

    /** The schema is unreleased: COMPUTED rows must carry list versions, enforced by a validated check. */
    @Test
    void aComputedRowWithoutListVersionsIsRefusedByTheDatabase() {
        assertThatThrownBy(() -> PostgresTestDatabase.owner().update("""
                insert into sc_cmp_evidence.compliance_screening
                    (screening_id, transaction_id, customer_id, amount, currency, sanctions_hit, kyc_verified, pep,
                     attestation_source, attested_by, decision, reasons, rule_set_version, checked_at, list_versions)
                values ('CMP-no-lists', 'PAY-NO-LISTS', 'C-1', 10.00, 'USD', false, true, false,
                        'COMPUTED', 'svc-pay-initiation-settlement', 'PASS', '["COMPLIANT"]'::jsonb, 'cmp-screening-rules-v2', now(), '[]'::jsonb)
                """))
            .rootCause().hasMessageContaining("ck_compliance_screening_computed_lists");
    }

    private ResultActions screenV2(String transactionId, String customerId, String amount, String currency) throws Exception {
        return mvc.perform(asService(post("/api/v2/compliance/screen"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(bodyV2(transactionId, customerId, amount, currency)));
    }

    private static String bodyV2(String transactionId, String customerId, String amount, String currency) {
        return """
            {"transactionId": "%s", "customerId": "%s", "amount": %s, "currency": "%s"}
            """.formatted(transactionId, customerId, amount, currency);
    }

    /** The schema is unreleased: no NULL-tolerant compatibility path, every check validated. */
    @Test
    void attestedByAndTheReportCurrencyAreMandatoryAndTheirChecksValidated() {
        List<Map<String, Object>> columns = jdbc.queryForList("""
            select table_name, column_name, is_nullable from information_schema.columns
            where table_schema = 'sc_cmp_evidence'
              and (table_name, column_name) in (('compliance_screening', 'attested_by'),
                                                ('legacy_compliance_report', 'total_amount_currency'))
            order by table_name
            """);
        assertThat(columns).extracting(c -> c.get("column_name") + "=" + c.get("is_nullable"))
            .containsExactly("attested_by=NO", "total_amount_currency=NO");

        List<String> notValidated = jdbc.queryForList("""
            select conname from pg_constraint c join pg_namespace n on n.oid = c.connamespace
            where n.nspname = 'sc_cmp_evidence' and not c.convalidated
            """, String.class);
        assertThat(notValidated).as("NOT VALID constraints").isEmpty();
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
    void newScreeningWritesExactlyOneOutboxEventAndARetryWritesNone() throws Exception {
        String response = screen("PAY-EVT-1", "C-7", "12000.00", false, true, true)
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        String screeningId = jdbc.queryForObject(
            "select screening_id from sc_cmp_evidence.compliance_screening where transaction_id = 'PAY-EVT-1'", String.class);

        List<Map<String, Object>> rows = jdbc.queryForList("""
            select topic, event_type, aggregate_type, aggregate_id, aggregate_version, correlation_id,
                   payload->>'producer' as producer, payload->>'eventType' as payload_event_type,
                   payload->'data'->>'screeningId' as data_screening_id,
                   payload->'data'->>'transactionId' as data_transaction_id,
                   payload->'data'->>'decision' as data_decision,
                   jsonb_exists(payload->'data', 'reasons') as has_reasons,
                   jsonb_exists(payload->'data', 'reasonCodes') as has_reason_codes,
                   jsonb_exists(payload->'data', 'amount') as has_amount,
                   jsonb_exists(payload->'data', 'facts') as has_facts,
                   jsonb_exists(payload->'data', 'listVersions') as has_list_versions,
                   published_at
            from sc_cmp_evidence.outbox_event
            """);
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.getFirst();
        assertThat(row.keySet()).contains("has_facts", "has_list_versions");
        assertThat(row).containsEntry("has_facts", false).containsEntry("has_list_versions", false)
            .containsEntry("topic", "evt.cmp.compliance.v1")
            .containsEntry("event_type", "Compliance.ComplianceScreening.Screened.v1")
            .containsEntry("payload_event_type", "Compliance.ComplianceScreening.Screened.v1")
            .containsEntry("producer", "svc-cmp-evidence")
            .containsEntry("aggregate_type", "ComplianceScreening")
            .containsEntry("aggregate_id", screeningId)
            .containsEntry("aggregate_version", 0L)
            .containsEntry("correlation_id", "it-interaction-1")
            .containsEntry("data_screening_id", screeningId)
            .containsEntry("data_transaction_id", "PAY-EVT-1")
            .containsEntry("data_decision", "REVIEW")
            .containsEntry("has_reasons", false)
            .containsEntry("has_reason_codes", false)
            .containsEntry("has_amount", false)
            .containsEntry("published_at", null);
        assertThat(response).contains(screeningId).contains("PEP_HIGH_VALUE_REVIEW");

        screen("PAY-EVT-1", "C-7", "12000.00", false, true, true).andExpect(status().isCreated());
        screen("PAY-EVT-1", "C-7", "12000.00", false, true, true).andExpect(status().isCreated());

        assertThat(outboxRows()).isEqualTo(1);
    }

    @Test
    void reusedTransactionIdForAnotherCustomerIsRefused() throws Exception {
        screen("PAY-CMP-2", "C-1", "100.00", true, true, false).andExpect(status().isCreated())
            .andExpect(jsonPath("$.decision").value("FAIL"));

        screen("PAY-CMP-2", "C-2", "100.00", false, true, false)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("TRANSACTION_ALREADY_SCREENED"));

        assertThat(outboxRows()).isEqualTo(1);
    }

    /**
     * Two first screenings of one transaction race: the other one holds an
     * uncommitted insert, so ours finds nothing, evaluates and blocks on the
     * unique transaction_id index. When the other commits, our insert fails,
     * the client gets 409 DUPLICATE_REQUEST and our whole transaction rolls
     * back: no second result and no outbox row.
     */
    @Test
    void losingAConcurrentFirstScreeningIsADuplicateRequestAndLeavesNoOutboxRow() throws Exception {
        CountDownLatch otherInserted = new CountDownLatch(1);
        CountDownLatch commitOther = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<?> other = threads.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                jdbc.update("""
                    insert into sc_cmp_evidence.compliance_screening
                        (screening_id, transaction_id, customer_id, amount, currency, sanctions_hit, kyc_verified, pep,
                         attestation_source, attested_by, decision, reasons, rule_set_version, checked_at)
                    values ('CMP-race-winner', 'PAY-RACE', 'C-1', 10.00, 'USD', false, true, false,
                            'CALLER_ATTESTED', 'svc-pay-initiation-settlement', 'PASS', '["COMPLIANT"]'::jsonb,
                            'cmp-screening-rules-v1', now())
                    """);
                otherInserted.countDown();
                await(commitOther);
            }));
            assertThat(otherInserted.await(10, TimeUnit.SECONDS)).isTrue();

            Future<ResultActions> ours = threads.submit(() -> screen("PAY-RACE", "C-1", "10.00", false, true, false));
            waitUntilABackendIsBlockedOnALock();
            commitOther.countDown();
            other.get(10, TimeUnit.SECONDS);

            ours.get(10, TimeUnit.SECONDS)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_REQUEST"));
        } finally {
            commitOther.countDown();
            threads.shutdownNow();
        }

        assertThat(jdbc.queryForList(
            "select screening_id from sc_cmp_evidence.compliance_screening where transaction_id = 'PAY-RACE'", String.class))
            .containsExactly("CMP-race-winner");
        assertThat(outboxRows()).isZero();
        // The loser's retry is a replay of the winner's facts and gets the stored result.
        screen("PAY-RACE", "C-1", "10", false, true, false)
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.screeningId").value("CMP-race-winner"));
    }

    @Test
    void replayWithAFlippedSanctionsFlagIsRefusedAndTheEvidenceIsUnchanged() throws Exception {
        screen("PAY-FLIP", "C-1", "500.00", false, true, false)
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.decision").value("PASS"));

        screen("PAY-FLIP", "C-1", "500.00", true, true, false)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("TRANSACTION_ALREADY_SCREENED"));
        screen("PAY-FLIP", "C-1", "500.01", false, true, false)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("TRANSACTION_ALREADY_SCREENED"));

        assertThat(jdbc.queryForMap("select decision, sanctions_hit from sc_cmp_evidence.compliance_screening where transaction_id = 'PAY-FLIP'"))
            .containsEntry("decision", "PASS").containsEntry("sanctions_hit", false);
        assertThat(outboxRows()).isEqualTo(1);
    }

    @Test
    void screenedFactsAreStoredAsEvidenceButNotPublished() throws Exception {
        mvc.perform(asService(post("/api/v1/compliance/screen"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"transactionId": "PAY-FACTS", "customerId": "C-5", "amount": 12000.5, "currency": "USD",
                     "sanctionsHit": false, "kycVerified": true, "pep": true}
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.attestation").value("CALLER_ATTESTED"));

        assertThat(jdbc.queryForMap("""
                select amount::text as amount, currency, sanctions_hit, kyc_verified, pep, attestation_source, rule_set_version
                from sc_cmp_evidence.compliance_screening where transaction_id = 'PAY-FACTS'
                """))
            .containsEntry("amount", "12000.5000").containsEntry("currency", "USD")
            .containsEntry("sanctions_hit", false).containsEntry("kyc_verified", true).containsEntry("pep", true)
            .containsEntry("attestation_source", "CALLER_ATTESTED").containsEntry("rule_set_version", "cmp-screening-rules-v2");
        String payload = jdbc.queryForObject("select payload::text from sc_cmp_evidence.outbox_event", String.class);
        assertThat(payload).doesNotContain("12000", "USD", "sanctions", "kyc", "\"pep\"", "CALLER_ATTESTED");
    }

    @Test
    void screeningEvidenceCannotBeUpdatedOrDeletedEvenByTheOwner() throws Exception {
        screen("PAY-LOCKED", "C-1", "10.00", false, true, false).andExpect(status().isCreated());
        JdbcTemplate owner = PostgresTestDatabase.owner();

        assertThatThrownBy(() -> owner.update(
                "update sc_cmp_evidence.compliance_screening set decision = 'FAIL' where transaction_id = 'PAY-LOCKED'"))
            .hasMessageContaining("insert-only evidence; UPDATE refused");
        assertThatThrownBy(() -> owner.update(
                "delete from sc_cmp_evidence.compliance_screening where transaction_id = 'PAY-LOCKED'"))
            .hasMessageContaining("insert-only evidence; DELETE refused");
        assertThat(jdbc.queryForObject(
            "select decision from sc_cmp_evidence.compliance_screening where transaction_id = 'PAY-LOCKED'", String.class))
            .isEqualTo("PASS");
    }

    @Test
    void outboxPublisherRefusesToWriteOutsideATransaction() {
        ComplianceResult result = ComplianceResultFixtures.result("PAY-NO-TX", "C-1", ComplianceDecision.PASS, List.of("COMPLIANT"));

        assertThatThrownBy(() -> eventPublisher.publish(result.screenedEvent()))
            .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(outboxRows()).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void relayPublishesThePendingEventKeyedByScreeningId() throws Exception {
        // A traced request: the tracing bridge puts the current span in the MDC.
        MDC.put("traceId", "4bf92f3577b34da6a3ce929d0e0e4736");
        MDC.put("spanId", "00f067aa0ba902b7");
        try {
            screen("PAY-RELAY-1", "C-3", "50.00", false, true, false).andExpect(status().isCreated());
        } finally {
            MDC.remove("traceId");
            MDC.remove("spanId");
        }
        String screeningId = jdbc.queryForObject(
            "select screening_id from sc_cmp_evidence.compliance_screening where transaction_id = 'PAY-RELAY-1'", String.class);
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));
        OutboxRelay relay = new OutboxRelay(outbox, kafka, new TransactionTemplate(transactionManager),
            Clock.systemUTC(), 100, Duration.ofSeconds(5), Duration.ofDays(7), 10, Duration.ofSeconds(1), Duration.ofMinutes(5), new io.micrometer.core.instrument.simple.SimpleMeterRegistry());

        assertThat(relay.relayOnce()).isEqualTo(1);

        assertThat(outbox.countByPublishedAtIsNullAndParkedAtIsNull()).isZero();
        ArgumentCaptor<ProducerRecord<String, String>> record = ArgumentCaptor.forClass(ProducerRecord.class);
        Mockito.verify(kafka).send(record.capture());
        assertThat(record.getValue().topic()).isEqualTo("evt.cmp.compliance.v1");
        assertThat(record.getValue().key()).isEqualTo(screeningId);
        assertThat(new String(record.getValue().headers().lastHeader("traceparent").value(), java.nio.charset.StandardCharsets.UTF_8))
            .isEqualTo("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        // The value is the envelope as stored in jsonb: same content, Postgres key order.
        JsonNode envelope = new ObjectMapper().readTree(record.getValue().value());
        assertThat(envelope.get("eventType").asText()).isEqualTo("Compliance.ComplianceScreening.Screened.v1");
        // ADR-019 section 3: the headers repeat the envelope so consumers route without parsing the value.
        assertThat(recordHeader(record.getValue(), "eventType")).isEqualTo(envelope.get("eventType").asText());
        assertThat(recordHeader(record.getValue(), "eventId")).isEqualTo(envelope.get("eventId").asText());
        assertThat(recordHeader(record.getValue(), "correlationId")).isEqualTo(envelope.get("correlationId").asText());
        assertThat(envelope.at("/data/screeningId").asText()).isEqualTo(screeningId);
        assertThat(envelope.at("/data/decision").asText()).isEqualTo("PASS");
        assertThat(relay.relayOnce()).isZero();
    }

    /** The runbook's manual park is counted once by the relay, as OperatorPark, never twice. */
    @Test
    void anOperatorParkIsCountedExactlyOnce() throws Exception {
        screen("PAY-OPPARK-1", "C-1", "10.00", false, true, false).andExpect(status().isCreated());
        String screeningId = jdbc.queryForObject(
            "select screening_id from sc_cmp_evidence.compliance_screening where transaction_id = 'PAY-OPPARK-1'", String.class);
        jdbc.update("""
            update sc_cmp_evidence.outbox_event
            set parked_at = now(), last_error = 'manual: topic will not be granted'
            where published_at is null and parked_at is null and aggregate_id = ?
            """, screeningId);
        io.micrometer.core.instrument.simple.SimpleMeterRegistry meters = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        OutboxRelay relay = new OutboxRelay(outbox, kafka, new TransactionTemplate(transactionManager),
            Clock.systemUTC(), 100, Duration.ofSeconds(5), Duration.ofDays(7), 10, Duration.ofSeconds(1), Duration.ofMinutes(5), meters);

        relay.relayOnce();
        relay.relayOnce();

        assertThat(meters.get("outbox.parked.events").tag("exception", "OperatorPark").counter().count()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "select park_counted from sc_cmp_evidence.outbox_event where aggregate_id = ?", Boolean.class, screeningId)).isTrue();
    }

    /**
     * A parked row is skipped by the relay, counted by the parked gauge and
     * sent again once the runbook's un-park statement clears parked_at.
     */
    @Test
    @SuppressWarnings("unchecked")
    void parkedRowsAreSkippedUntilTheRunbookUnparksThem() throws Exception {
        screen("PAY-PARK-1", "C-1", "10.00", false, true, false).andExpect(status().isCreated());
        screen("PAY-PARK-2", "C-1", "20.00", false, true, false).andExpect(status().isCreated());
        String parkedScreening = jdbc.queryForObject(
            "select screening_id from sc_cmp_evidence.compliance_screening where transaction_id = 'PAY-PARK-1'", String.class);
        jdbc.update("""
            update sc_cmp_evidence.outbox_event
            set parked_at = now(), attempts = 1, last_error = 'RecordTooLargeException: too large'
            where aggregate_id = ?
            """, parkedScreening);
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));
        OutboxRelay relay = new OutboxRelay(outbox, kafka, new TransactionTemplate(transactionManager),
            Clock.systemUTC(), 100, Duration.ofSeconds(5), Duration.ofDays(7), 10, Duration.ofSeconds(1), Duration.ofMinutes(5), new io.micrometer.core.instrument.simple.SimpleMeterRegistry());

        assertThat(relay.relayOnce()).as("only the row behind the parked one").isEqualTo(1);
        assertThat(outbox.countByPublishedAtIsNullAndParkedAtIsNotNull()).isEqualTo(1);
        assertThat(outbox.countByPublishedAtIsNullAndParkedAtIsNull()).isZero();

        // Manual replay, as in the runbook.
        jdbc.update("""
            update sc_cmp_evidence.outbox_event
            set parked_at = null, park_counted = false, first_failed_at = null, attempts = 0, last_error = null
            where parked_at is not null and published_at is null and aggregate_id = ?
            """, parkedScreening);

        assertThat(relay.relayOnce()).isEqualTo(1);
        assertThat(outbox.countByPublishedAtIsNullAndParkedAtIsNotNull()).isZero();
        ArgumentCaptor<ProducerRecord<String, String>> records = ArgumentCaptor.forClass(ProducerRecord.class);
        Mockito.verify(kafka, Mockito.times(2)).send(records.capture());
        assertThat(records.getAllValues().getLast().key()).isEqualTo(parkedScreening);
    }

    @Test
    void theEvidenceNamesWhoAttestedAndAnotherCallersReplayIsRefused() throws Exception {
        screen("PAY-ATT-1", "C-1", "10.00", false, true, false)
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.attestation").value("CALLER_ATTESTED"))
            .andExpect(jsonPath("$.attestedBy").value("svc-pay-initiation-settlement"));

        assertThat(jdbc.queryForMap(
                "select attestation_source, attested_by from sc_cmp_evidence.compliance_screening where transaction_id = 'PAY-ATT-1'"))
            .containsEntry("attestation_source", "CALLER_ATTESTED").containsEntry("attested_by", "svc-pay-initiation-settlement");

        // The same facts from a compliance officer are not a replay of the payment service's screening.
        mvc.perform(post("/api/v1/compliance/screen").with(officer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("PAY-ATT-1", "C-1", "10.00", false, true, false)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("TRANSACTION_ALREADY_SCREENED"));
        assertThat(outboxRows()).isEqualTo(1);
    }

    @Test
    void staffScreeningsAreStaffAttestedUnderTheOfficersSubject() throws Exception {
        mvc.perform(post("/api/v1/compliance/screen").with(officer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("PAY-ATT-2", "C-1", "10.00", false, true, false)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.attestation").value("STAFF_ATTESTED"))
            .andExpect(jsonPath("$.attestedBy").value(OFFICER_SUBJECT));

        assertThat(jdbc.queryForMap(
                "select attestation_source, attested_by from sc_cmp_evidence.compliance_screening where transaction_id = 'PAY-ATT-2'"))
            .containsEntry("attestation_source", "STAFF_ATTESTED").containsEntry("attested_by", OFFICER_SUBJECT);
    }

    private static final String OFFICER_SUBJECT = "8d0c6a4e-2f7b-4c1e-9a43-5b2f0d9e7c11";

    private static org.springframework.test.web.servlet.request.RequestPostProcessor officer() {
        return jwt().jwt(j -> j.subject(OFFICER_SUBJECT).claim("azp", "fintechbankx-web"))
            .authorities(new SimpleGrantedAuthority("ROLE_COMPLIANCE_OFFICER"));
    }

    /**
     * The service connects as a runtime role that may read and insert
     * evidence but not change it: UPDATE, DELETE and TRUNCATE are not granted,
     * and only the owner may disable the insert-only trigger.
     */
    @Test
    void theRuntimeRoleCannotChangeOrRemoveEvidence() throws Exception {
        screen("PAY-RUNTIME", "C-1", "10.00", false, true, false).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("select current_user", String.class)).isEqualTo(PostgresTestDatabase.RUNTIME_ROLE);
        JdbcTemplate runtime = PostgresTestDatabase.runtime();

        assertThatThrownBy(() -> runtime.update(
                "update sc_cmp_evidence.compliance_screening set decision = 'FAIL' where transaction_id = 'PAY-RUNTIME'"))
            .rootCause().hasMessageContaining("permission denied for table compliance_screening");
        assertThatThrownBy(() -> runtime.update(
                "delete from sc_cmp_evidence.compliance_screening where transaction_id = 'PAY-RUNTIME'"))
            .rootCause().hasMessageContaining("permission denied for table compliance_screening");
        assertThatThrownBy(() -> runtime.execute("truncate sc_cmp_evidence.compliance_screening"))
            .rootCause().hasMessageContaining("permission denied for table compliance_screening");
        assertThatThrownBy(() -> runtime.execute(
                "alter table sc_cmp_evidence.compliance_screening disable trigger tr_compliance_screening_insert_only"))
            .rootCause().hasMessageContaining("must be owner of").hasMessageContaining("compliance_screening");
        assertThatThrownBy(() -> runtime.execute(
                "drop trigger tr_compliance_screening_insert_only on sc_cmp_evidence.compliance_screening"))
            .rootCause().hasMessageContaining("must be owner of").hasMessageContaining("compliance_screening");
        assertThatThrownBy(() -> runtime.execute("truncate sc_cmp_evidence.legacy_compliance_report"))
            .rootCause().hasMessageContaining("permission denied for table legacy_compliance_report");
        assertThatThrownBy(() -> runtime.execute("create table sc_cmp_evidence.shadow (id int)"))
            .rootCause().hasMessageContaining("permission denied for schema sc_cmp_evidence");

        assertThat(runtime.queryForObject(
                "select decision from sc_cmp_evidence.compliance_screening where transaction_id = 'PAY-RUNTIME'", String.class))
            .isEqualTo("PASS");
    }

    /**
     * Two replicas relay at once. The first holds the advisory lock while its
     * send is in flight; the second must not read and send the same rows, so
     * every event reaches Kafka exactly once.
     */
    @Test
    @SuppressWarnings("unchecked")
    void twoRelaysRunningConcurrentlySendEachRowExactlyOnce() throws Exception {
        for (int i = 1; i <= 3; i++) {
            screen("PAY-CONC-" + i, "C-1", "10.00", false, true, false).andExpect(status().isCreated());
        }
        List<String> sentKeys = new java.util.concurrent.CopyOnWriteArrayList<>();
        CountDownLatch firstSendStarted = new CountDownLatch(1);
        CountDownLatch releaseSends = new CountDownLatch(1);
        when(kafka.send(any(ProducerRecord.class))).thenAnswer(call -> {
            sentKeys.add(((ProducerRecord<String, String>) call.getArgument(0)).key());
            firstSendStarted.countDown();
            return CompletableFuture.supplyAsync(() -> {
                await(releaseSends);
                return (SendResult<String, String>) null;
            });
        });
        OutboxRelay first = relay();
        OutboxRelay second = relay();
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> firstRun = threads.submit(first::relayOnce);
            assertThat(firstSendStarted.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Integer> secondRun = threads.submit(second::relayOnce);
            // With the lock the second relay returns at once; without it, it would be sending the same rows.
            Integer secondSent = null;
            try {
                secondSent = secondRun.get(2, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException stillSending) {
                // fall through: release the sends and count what reached Kafka
            }
            releaseSends.countDown();
            assertThat(firstRun.get(20, TimeUnit.SECONDS)).isEqualTo(3);
            if (secondSent == null) {
                secondSent = secondRun.get(20, TimeUnit.SECONDS);
            }
            assertThat(secondSent).isZero();
        } finally {
            releaseSends.countDown();
            threads.shutdownNow();
        }

        assertThat(sentKeys).hasSize(3).doesNotHaveDuplicates();
        assertThat(outbox.countByPublishedAtIsNullAndParkedAtIsNull()).isZero();
    }

    private OutboxRelay relay() {
        return new OutboxRelay(outbox, kafka, new TransactionTemplate(transactionManager),
            Clock.systemUTC(), 100, Duration.ofSeconds(15), Duration.ofDays(7), 10, Duration.ofSeconds(1), Duration.ofMinutes(5), new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    private int outboxRows() {
        return jdbc.queryForObject("select count(*) from sc_cmp_evidence.outbox_event", Integer.class);
    }

    private void waitUntilABackendIsBlockedOnALock() throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(10);
        while (Instant.now().isBefore(deadline)) {
            Integer waiting = jdbc.queryForObject("""
                select count(*) from pg_stat_activity
                where datname = current_database() and wait_event_type = 'Lock'
                """, Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("The racing insert never blocked on the unique transaction_id index");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Test
    void theServiceRoleAloneIsNotEnoughTheCallingClientMustBeListed() throws Exception {
        var unlisted = jwt().jwt(j -> j.subject("svc-rsk-decisioning").claim("azp", "svc-rsk-decisioning"))
            .authorities(new SimpleGrantedAuthority("ROLE_SERVICE"));

        mvc.perform(post("/api/v1/compliance/screen").with(unlisted)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("PAY-UNLISTED", "C-1", "10.00", false, true, false)))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/compliance/screenings/{id}", "PAY-ANY").with(unlisted))
            .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("select count(*) from sc_cmp_evidence.compliance_screening", Integer.class)).isZero();
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
            {"transactionId": "%s", "customerId": "%s", "amount": %s, "currency": "USD", "sanctionsHit": %s, "kycVerified": %s, "pep": %s}
            """.formatted(transactionId, customerId, amount, sanctionsHit, kycVerified, pep);
    }

    private static MockHttpServletRequestBuilder asService(MockHttpServletRequestBuilder request) {
        return request.header("x-fapi-interaction-id", "it-interaction-1")
            .with(jwt().jwt(j -> j.subject("svc-pay-initiation-settlement").claim("azp", "svc-pay-initiation-settlement"))
                .authorities(new SimpleGrantedAuthority("ROLE_SERVICE")));
    }

    private static String recordHeader(ProducerRecord<String, String> record, String name) {
        org.apache.kafka.common.header.Header header = record.headers().lastHeader(name);
        assertThat(header).as("record header %s", name).isNotNull();
        return new String(header.value(), java.nio.charset.StandardCharsets.UTF_8);
    }
}
