package com.bank.compliance;

import com.bank.compliance.domain.ComplianceDecision;
import com.bank.compliance.domain.ComplianceResult;
import com.bank.compliance.domain.command.ComplianceScreeningCommand;
import com.bank.compliance.domain.port.in.ComplianceScreeningUseCase;
import com.bank.compliance.domain.port.out.ComplianceEventPublisher;
import com.bank.compliance.infrastructure.outbox.OutboxRelay;
import com.bank.compliance.infrastructure.outbox.SpringDataOutboxRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataIntegrityViolationException;
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

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
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
@SpringBootTest(properties = "compliance.outbox.relay.enabled=false")
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
    @Autowired ComplianceScreeningUseCase screening;
    @Autowired ComplianceEventPublisher eventPublisher;
    @Autowired SpringDataOutboxRepository outbox;
    @Autowired PlatformTransactionManager transactionManager;
    @MockBean KafkaTemplate<String, String> kafka;

    @BeforeEach
    void cleanTables() {
        jdbc.update("delete from sc_cmp_evidence.outbox_event");
        jdbc.update("delete from sc_cmp_evidence.compliance_screening");
    }

    @Test
    void flywayCreatesOnlyTheTablesThisServiceOwns() {
        List<String> tables = jdbc.queryForList("""
            select table_name from information_schema.tables
            where table_schema = 'sc_cmp_evidence' and table_name <> 'flyway_schema_history'
            order by table_name
            """, String.class);

        assertThat(tables).containsExactly("compliance_screening", "legacy_compliance_report", "outbox_event");
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
                   payload->'data'->'reasons'->>0 as data_reason,
                   jsonb_exists(payload->'data', 'amount') as has_amount,
                   published_at
            from sc_cmp_evidence.outbox_event
            """);
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.getFirst();
        assertThat(row).containsEntry("topic", "evt.cmp.compliance.screened.v1")
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
            .containsEntry("data_reason", "PEP_HIGH_VALUE_REVIEW")
            .containsEntry("has_amount", false)
            .containsEntry("published_at", null);
        assertThat(response).contains(screeningId);

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
     * unique transaction_id index. When the other commits, our insert fails and
     * our whole transaction rolls back: no second result and no outbox row.
     */
    @Test
    void losingAConcurrentFirstScreeningLeavesNoOutboxRow() throws Exception {
        CountDownLatch otherInserted = new CountDownLatch(1);
        CountDownLatch commitOther = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<?> other = threads.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                jdbc.update("""
                    insert into sc_cmp_evidence.compliance_screening
                        (screening_id, transaction_id, customer_id, decision, reasons, checked_at)
                    values ('CMP-race-winner', 'PAY-RACE', 'C-1', 'PASS', '["COMPLIANT"]'::jsonb, now())
                    """);
                otherInserted.countDown();
                await(commitOther);
            }));
            assertThat(otherInserted.await(10, TimeUnit.SECONDS)).isTrue();

            Future<ComplianceResult> ours = threads.submit(() -> screening.screen(
                new ComplianceScreeningCommand("PAY-RACE", "C-1", new BigDecimal("10.00"), false, true, false)));
            waitUntilABackendIsBlockedOnALock();
            commitOther.countDown();
            other.get(10, TimeUnit.SECONDS);

            assertThatThrownBy(() -> ours.get(10, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(DataIntegrityViolationException.class);
        } finally {
            commitOther.countDown();
            threads.shutdownNow();
        }

        assertThat(jdbc.queryForList(
            "select screening_id from sc_cmp_evidence.compliance_screening where transaction_id = 'PAY-RACE'", String.class))
            .containsExactly("CMP-race-winner");
        assertThat(outboxRows()).isZero();
    }

    @Test
    void outboxPublisherRefusesToWriteOutsideATransaction() {
        ComplianceResult result = ComplianceResult.create("PAY-NO-TX", "C-1", ComplianceDecision.PASS, List.of("COMPLIANT"));

        assertThatThrownBy(() -> eventPublisher.publish(result.screenedEvent()))
            .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(outboxRows()).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void relayPublishesThePendingEventKeyedByScreeningId() throws Exception {
        screen("PAY-RELAY-1", "C-3", "50.00", false, true, false).andExpect(status().isCreated());
        String screeningId = jdbc.queryForObject(
            "select screening_id from sc_cmp_evidence.compliance_screening where transaction_id = 'PAY-RELAY-1'", String.class);
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));
        OutboxRelay relay = new OutboxRelay(outbox, kafka, new TransactionTemplate(transactionManager),
            Clock.systemUTC(), 100, Duration.ofSeconds(5), Duration.ofDays(7));

        assertThat(relay.relayOnce()).isEqualTo(1);

        assertThat(outbox.countByPublishedAtIsNull()).isZero();
        ArgumentCaptor<ProducerRecord<String, String>> record = ArgumentCaptor.forClass(ProducerRecord.class);
        Mockito.verify(kafka).send(record.capture());
        assertThat(record.getValue().topic()).isEqualTo("evt.cmp.compliance.screened.v1");
        assertThat(record.getValue().key()).isEqualTo(screeningId);
        // The value is the envelope as stored in jsonb: same content, Postgres key order.
        JsonNode envelope = new ObjectMapper().readTree(record.getValue().value());
        assertThat(envelope.get("eventType").asText()).isEqualTo("Compliance.ComplianceScreening.Screened.v1");
        assertThat(envelope.at("/data/screeningId").asText()).isEqualTo(screeningId);
        assertThat(envelope.at("/data/decision").asText()).isEqualTo("PASS");
        assertThat(relay.relayOnce()).isZero();
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
