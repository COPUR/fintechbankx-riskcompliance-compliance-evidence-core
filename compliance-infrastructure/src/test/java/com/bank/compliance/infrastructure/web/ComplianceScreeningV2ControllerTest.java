package com.bank.compliance.infrastructure.web;

import com.bank.compliance.domain.Attestation;
import com.bank.compliance.domain.ComplianceDecision;
import com.bank.compliance.domain.ComplianceResult;
import com.bank.compliance.domain.CustomerNotFoundException;
import com.bank.compliance.domain.ListVersion;
import com.bank.compliance.domain.ScreeningFacts;
import com.bank.compliance.domain.ScreeningList;
import com.bank.compliance.domain.ScreeningSourceUnavailableException;
import com.bank.compliance.domain.TransactionAlreadyScreenedException;
import com.bank.compliance.domain.port.in.ComplianceScreeningCommand;
import com.bank.compliance.domain.port.in.ComplianceScreeningUseCase;
import com.bank.compliance.domain.port.in.ComputedFactsScreeningCommand;
import com.bank.compliance.domain.service.ComplianceRuleService;
import com.bank.compliance.infrastructure.config.ServiceCallerPolicy;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /api/v2/compliance/screen: ids and the amount only. The sanctions, PEP
 * and KYC facts are computed by compliance; the caller may not state them.
 */
class ComplianceScreeningV2ControllerTest {

    private static final Instant LOADED = Instant.parse("2026-10-18T01:00:00Z");
    private static final ListVersion UN = new ListVersion(ScreeningList.SANCTIONS, "UN", "2026-10-17", LOADED);
    private static final ListVersion UAE_PEP = new ListVersion(ScreeningList.PEP, "UAE-LOCAL", "2026-10", LOADED);

    private ComplianceScreeningUseCase service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(ComplianceScreeningUseCase.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new ComplianceScreeningV2Controller(service,
                        new CallerAttestation(new ServiceCallerPolicy("svc-pay-initiation-settlement"))))
                .setControllerAdvice(new ApiExceptionHandler())
                .defaultRequest(get("/").principal(token("service-account-uuid", "svc-pay-initiation-settlement", "SERVICE")))
                .build();
    }

    private static ComplianceResult computed(String transactionId, ComplianceDecision decision, List<String> reasons) {
        return ComplianceResult.create(transactionId, "C1",
                new ScreeningFacts(new BigDecimal("12000.00"), "USD", decision == ComplianceDecision.FAIL, true, true,
                        Attestation.computed("svc-pay-initiation-settlement")),
                decision, reasons, ComplianceRuleService.RULE_SET_VERSION, List.of(UN, UAE_PEP));
    }

    @Test
    void screensWithIdsAndAmountOnlyAndAnswersTheComputedEvidence() throws Exception {
        when(service.screenWithComputedFacts(any(ComputedFactsScreeningCommand.class)))
                .thenReturn(computed("TX-1", ComplianceDecision.FAIL, List.of("SANCTIONS_HIT")));

        mockMvc.perform(post("/api/v2/compliance/screen").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"transactionId":"TX-1","customerId":"C1","amount":12000.00,"currency":"USD"}
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transactionId").value("TX-1"))
                .andExpect(jsonPath("$.decision").value("FAIL"))
                .andExpect(jsonPath("$.reasons[0]").value("SANCTIONS_HIT"))
                .andExpect(jsonPath("$.attestation").value("COMPUTED"))
                .andExpect(jsonPath("$.attestedBy").value("svc-pay-initiation-settlement"))
                .andExpect(jsonPath("$.listVersions[0].list").value("SANCTIONS"))
                .andExpect(jsonPath("$.listVersions[0].source").value("UN"))
                .andExpect(jsonPath("$.listVersions[0].version").value("2026-10-17"))
                .andExpect(jsonPath("$.listVersions[0].loadedAt").value("2026-10-18T01:00:00Z"))
                .andExpect(jsonPath("$.listVersions[1].list").value("PEP"))
                .andExpect(jsonPath("$.sanctionsHit").doesNotExist())
                .andExpect(jsonPath("$.pep").doesNotExist())
                .andExpect(jsonPath("$.kycVerified").doesNotExist());

        ArgumentCaptor<ComputedFactsScreeningCommand> command = ArgumentCaptor.forClass(ComputedFactsScreeningCommand.class);
        verify(service).screenWithComputedFacts(command.capture());
        assertThat(command.getValue().transactionId()).isEqualTo("TX-1");
        assertThat(command.getValue().customerId()).isEqualTo("C1");
        assertThat(command.getValue().amount()).isEqualByComparingTo("12000.00");
        assertThat(command.getValue().currency()).isEqualTo("USD");
        assertThat(command.getValue().requestedBy()).isEqualTo(Attestation.computed("svc-pay-initiation-settlement"));
        verify(service, never()).screen(any(ComplianceScreeningCommand.class));
    }

    @Test
    void staffRequestsAreRecordedUnderTheirSubject() throws Exception {
        when(service.screenWithComputedFacts(any())).thenReturn(computed("TX-2", ComplianceDecision.PASS, List.of("COMPLIANT")));
        String officer = "8d0c6a4e-2f7b-4c1e-9a43-5b2f0d9e7c11";

        mockMvc.perform(post("/api/v2/compliance/screen").contentType(MediaType.APPLICATION_JSON).content(body("TX-2", "C1", "10"))
                        .principal(token(officer, "fintechbankx-web", "COMPLIANCE_OFFICER")))
                .andExpect(status().isCreated());

        ArgumentCaptor<ComputedFactsScreeningCommand> command = ArgumentCaptor.forClass(ComputedFactsScreeningCommand.class);
        verify(service).screenWithComputedFacts(command.capture());
        assertThat(command.getValue().requestedBy()).isEqualTo(Attestation.computed(officer));
    }

    @Test
    void callerStatedFactsAreRefusedOnV2() throws Exception {
        expectInvalid("""
                {"transactionId":"TX-3","customerId":"C1","amount":10,"currency":"USD","sanctionsHit":false}
                """, "sanctionsHit is not accepted: compliance computes it");
        expectInvalid("""
                {"transactionId":"TX-3","customerId":"C1","amount":10,"currency":"USD","pep":true}
                """, "pep is not accepted: compliance computes it");
        expectInvalid("""
                {"transactionId":"TX-3","customerId":"C1","amount":10,"currency":"USD","kycVerified":true}
                """, "kycVerified is not accepted: compliance computes it");
        verify(service, never()).screenWithComputedFacts(any());
    }

    @Test
    void invalidRequestsAreA400WithAStableCode() throws Exception {
        expectInvalid("""
                {"transactionId":"TX-4","customerId":"C1","amount":10}
                """, "currency is required");
        expectInvalid("""
                {"transactionId":"TX-4","customerId":"C1","amount":10,"currency":null}
                """, "currency is required");
        expectInvalid(body("TX-4", "C1", "-1"), "amount must be positive");
        expectInvalid(body("TX-4", "C1", "10.00001"), "amount must have at most 4 decimal places");
        expectInvalid(body("T".repeat(129), "C1", "10"), "transactionId must be at most 128 characters");
        expectInvalid("""
                {"transactionId":"TX-4","customerId":"C1","amount":10,"currency":"usd"}
                """, "currency must be an upper-case ISO 4217 code");
        mockMvc.perform(post("/api/v2/compliance/screen").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed request body"));
        verify(service, never()).screenWithComputedFacts(any());
    }

    @Test
    void anUnknownCustomerIsA422WithoutTheIds() throws Exception {
        when(service.screenWithComputedFacts(any())).thenThrow(new CustomerNotFoundException());

        mockMvc.perform(post("/api/v2/compliance/screen").contentType(MediaType.APPLICATION_JSON).content(body("TX-5", "C-unknown", "10")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CUSTOMER_NOT_FOUND"))
                .andExpect(content().string(Matchers.not(Matchers.containsString("C-unknown"))))
                .andExpect(content().string(Matchers.not(Matchers.containsString("TX-5"))));
    }

    @Test
    void anUnavailableSourceIsA503ThatNeverPassesByDefault() throws Exception {
        when(service.screenWithComputedFacts(any()))
                .thenThrow(new ScreeningSourceUnavailableException("The sanctions list is stale: source UN was loaded more than PT24H ago"));

        mockMvc.perform(post("/api/v2/compliance/screen").contentType(MediaType.APPLICATION_JSON).content(body("TX-6", "C1", "10")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SCREENING_SOURCE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("The sanctions list is stale: source UN was loaded more than PT24H ago"))
                .andExpect(jsonPath("$.decision").doesNotExist());
    }

    @Test
    void aConflictingReplayIsA409() throws Exception {
        when(service.screenWithComputedFacts(any())).thenThrow(new TransactionAlreadyScreenedException());

        mockMvc.perform(post("/api/v2/compliance/screen").contentType(MediaType.APPLICATION_JSON).content(body("TX-7", "C1", "10")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRANSACTION_ALREADY_SCREENED"))
                .andExpect(content().string(Matchers.not(Matchers.containsString("TX-7"))));
    }

    private void expectInvalid(String body, String message) throws Exception {
        mockMvc.perform(post("/api/v2/compliance/screen").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value(message));
    }

    private static String body(String transactionId, String customerId, String amount) {
        return """
                {"transactionId":"%s","customerId":"%s","amount":%s,"currency":"USD"}
                """.formatted(transactionId, customerId, amount);
    }

    private static JwtAuthenticationToken token(String subject, String azp, String role) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject(subject).claim("azp", azp)
                .claim("customer_id", "CUST-should-not-be-used").build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_" + role)), subject);
    }
}
